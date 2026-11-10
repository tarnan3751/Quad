package com.campusmarket.server.payments

import com.campusmarket.core.ErrorCodes
import com.campusmarket.server.AppDeps
import com.campusmarket.server.ApiException
import com.campusmarket.server.badRequest
import com.campusmarket.server.toUuidOrNull
import com.campusmarket.server.update
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.net.Webhook
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** A processor event, reduced to what the order state machine needs. */
sealed interface PaymentEvent {
    val id: String
    val type: String
    val createdAt: Instant

    /** `checkout.session.completed` / `checkout.session.async_payment_succeeded`. */
    data class CheckoutCompleted(
        override val id: String,
        override val type: String,
        override val createdAt: Instant,
        val sessionId: String,
        val orderId: UUID?,
        val paymentIntentId: String?,
        /** false while a delayed payment method is still settling */
        val paid: Boolean,
    ) : PaymentEvent

    /** `checkout.session.expired` / `checkout.session.async_payment_failed`: the order can't be paid any more. */
    data class CheckoutFailed(
        override val id: String,
        override val type: String,
        override val createdAt: Instant,
        val sessionId: String,
        val orderId: UUID?,
    ) : PaymentEvent

    /** `account.updated` for a connected account. */
    data class AccountUpdated(
        override val id: String,
        override val type: String,
        override val createdAt: Instant,
        val accountId: String,
        val detailsSubmitted: Boolean,
        val payoutsEnabled: Boolean,
    ) : PaymentEvent

    /** `charge.refunded`. */
    data class ChargeRefunded(
        override val id: String,
        override val type: String,
        override val createdAt: Instant,
        val chargeId: String,
        val paymentIntentId: String?,
        /** from the charge's transfer_group (= order id) */
        val orderId: UUID?,
        val fullyRefunded: Boolean,
    ) : PaymentEvent

    data class Unhandled(override val id: String, override val type: String, override val createdAt: Instant) : PaymentEvent
}

/** Maps Stripe events to [PaymentEvent]s. Reads the raw `data.object` JSON, so it doesn't depend on the event's API version. */
object StripeEvents {
    private val json = Json { ignoreUnknownKeys = true }

    /** Test/tooling seam: parses an event payload **without** checking its signature. */
    fun parseUnverified(payload: String): PaymentEvent = from(Webhook.constructEventWithoutVerification(payload))

    fun from(event: Event): PaymentEvent {
        val id = event.id ?: throw IllegalArgumentException("Event without id")
        val type = event.type ?: ""
        val created = Instant.ofEpochSecond(event.created ?: 0L)
        val obj = event.dataObjectDeserializer?.rawJson?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        fun orderIdOf(o: JsonObject) = (o.str("client_reference_id") ?: o.obj("metadata")?.str("order_id"))?.toUuidOrNull()
        return when (type) {
            "checkout.session.completed", "checkout.session.async_payment_succeeded" -> PaymentEvent.CheckoutCompleted(
                id, type, created,
                sessionId = obj.str("id") ?: return PaymentEvent.Unhandled(id, type, created),
                orderId = orderIdOf(obj),
                paymentIntentId = obj.idOf("payment_intent"),
                paid = obj.str("payment_status").let { it == "paid" || it == "no_payment_required" },
            )
            "checkout.session.expired", "checkout.session.async_payment_failed" -> PaymentEvent.CheckoutFailed(
                id, type, created,
                sessionId = obj.str("id") ?: return PaymentEvent.Unhandled(id, type, created),
                orderId = orderIdOf(obj),
            )
            "account.updated" -> PaymentEvent.AccountUpdated(
                id, type, created,
                accountId = obj.str("id") ?: return PaymentEvent.Unhandled(id, type, created),
                detailsSubmitted = obj.bool("details_submitted"),
                payoutsEnabled = obj.bool("payouts_enabled"),
            )
            "charge.refunded" -> {
                val amount = obj.long("amount")
                val refunded = obj.long("amount_refunded")
                PaymentEvent.ChargeRefunded(
                    id, type, created,
                    chargeId = obj.str("id") ?: return PaymentEvent.Unhandled(id, type, created),
                    paymentIntentId = obj.idOf("payment_intent"),
                    orderId = (obj.str("transfer_group") ?: obj.obj("metadata")?.str("order_id"))?.toUuidOrNull(),
                    fullyRefunded = obj.bool("refunded") || (amount != null && refunded != null && amount > 0 && refunded >= amount),
                )
            }
            else -> PaymentEvent.Unhandled(id, type, created)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** An expandable reference: either the id string or the expanded object. */
    private fun JsonObject.idOf(key: String): String? = when (val v: JsonElement? = this[key]) {
        is JsonPrimitive -> v.contentOrNull?.takeIf { v.isString }
        is JsonObject -> v.str("id")
        else -> null
    }
}

/**
 * Stripe webhook endpoint logic: signature verification, then exactly-once application of each event
 * (the `stripe_events` insert commits atomically with the state change it causes). Handlers only ever move an
 * order forward from the state they expect, so duplicate and out-of-order deliveries are harmless.
 */
internal class WebhookService(private val deps: AppDeps, private val orders: OrderService) {
    private val log = LoggerFactory.getLogger(WebhookService::class.java)

    /**
     * Signing secrets: `STRIPE_WEBHOOK_SECRET` may list several, comma-separated (the platform endpoint and the
     * Connect endpoint each have their own secret).
     */
    private val secrets: List<String> =
        deps.config.stripeWebhookSecret?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    enum class Result { PROCESSED, DUPLICATE }

    /** Verifies the `Stripe-Signature` header and parses the event. 400 if it doesn't check out. */
    fun verify(payload: String, signatureHeader: String?): PaymentEvent {
        if (secrets.isEmpty()) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Stripe webhooks are not configured")
        }
        if (signatureHeader.isNullOrBlank()) badRequest("Missing Stripe-Signature header")
        for (secret in secrets) {
            val event = try {
                Webhook.constructEvent(payload, signatureHeader, secret)
            } catch (e: SignatureVerificationException) {
                continue
            } catch (e: Exception) {
                log.warn("Unparseable webhook payload", e)
                badRequest("Malformed event")
            }
            return StripeEvents.from(event)
        }
        badRequest("Invalid webhook signature")
    }

    /** Applies [event] once. Throws (→ 5xx, Stripe retries) if it couldn't be applied completely. */
    suspend fun handle(event: PaymentEvent): Result {
        val now = orders.now()
        val followUp = deps.db.tx { c ->
            if (c.update("INSERT INTO stripe_events (id, type) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", event.id, event.type) == 0) {
                return@tx null
            }
            val outcome: PaidOutcome = when (event) {
                is PaymentEvent.CheckoutCompleted -> {
                    val order = c.findOrderForCheckout(event.sessionId, event.orderId)
                    when {
                        order == null -> { log.info("Ignoring {} for unknown checkout session {}", event.type, event.sessionId); PaidOutcome.NoChange }
                        // delayed payment methods: wait for checkout.session.async_payment_succeeded
                        !event.paid -> PaidOutcome.NoChange
                        else -> c.applyCheckoutPaid(order, event.sessionId, event.paymentIntentId, now)
                    }
                }
                is PaymentEvent.CheckoutFailed -> {
                    c.findOrderForCheckout(event.sessionId, event.orderId)?.let { c.cancelPendingOrder(it) }
                    PaidOutcome.NoChange
                }
                is PaymentEvent.AccountUpdated -> {
                    if (!c.syncPayoutAccount(event.accountId, PayoutAccountState(event.detailsSubmitted, event.payoutsEnabled), event.createdAt)) {
                        log.info("Ignoring stale {} for {}", event.id, event.accountId)
                    }
                    PaidOutcome.NoChange
                }
                is PaymentEvent.ChargeRefunded -> {
                    val order = event.paymentIntentId?.let { c.findOrderByPaymentIntent(it, forUpdate = true) }
                        ?: event.orderId?.let { c.findOrder(it, forUpdate = true) }
                    when {
                        order == null -> log.info("Ignoring refund of unknown charge {}", event.chargeId)
                        !event.fullyRefunded -> log.warn("Charge {} of order {} was partially refunded; leaving the order as is", event.chargeId, order.id)
                        else -> c.applyRefunded(order, refundId = null, paymentIntentId = event.paymentIntentId)
                    }
                    PaidOutcome.NoChange
                }
                is PaymentEvent.Unhandled -> PaidOutcome.NoChange
            }
            outcome
        } ?: return Result.DUPLICATE

        if (followUp is PaidOutcome.RefundNeeded) {
            try {
                orders.refundStrayPayment(followUp)
            } catch (e: Exception) {
                // Forget the event so Stripe's retry runs it (and the refund) again.
                log.error("Refunding stray payment {} for order {} failed; awaiting webhook retry", followUp.paymentIntentId, followUp.orderId, e)
                withContext(NonCancellable) { deps.db.tx { it.update("DELETE FROM stripe_events WHERE id = ?", event.id) } }
                throw e
            }
        }
        return Result.PROCESSED
    }
}
