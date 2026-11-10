package com.campusmarket.server.payments

import com.campusmarket.core.OnboardingLinkResponse
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.server.AppDeps
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.UserThrottle
import com.campusmarket.server.notFound
import com.campusmarket.server.queryOne
import com.campusmarket.server.update
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant

/**
 * Applies a connected account's state to its user, unless a newer state was already applied (webhooks can be
 * delivered out of order). Returns false when [asOf] is older than what we have.
 */
internal fun Connection.syncPayoutAccount(accountId: String, state: PayoutAccountState, asOf: Instant): Boolean {
    val newer = update(
        """INSERT INTO payout_account_sync (account_id, state_at) VALUES (?, ?)
           ON CONFLICT (account_id) DO UPDATE SET state_at = EXCLUDED.state_at
           WHERE payout_account_sync.state_at <= EXCLUDED.state_at""",
        accountId, asOf,
    ) == 1
    if (newer) {
        update(
            "UPDATE users SET stripe_details_submitted = ?, payouts_enabled = ? WHERE stripe_account_id = ?",
            state.detailsSubmitted, state.payoutsEnabled, accountId,
        )
    }
    return newer
}

/** Seller payout accounts: Express account creation, onboarding links and status. */
internal class PayoutService(private val deps: AppDeps, private val gateway: PaymentGateway) {
    private val db = deps.db
    private val urls = PaymentUrls(deps.config.publicBaseUrl, deps.config.appDeepLinkBase)
    private val log = LoggerFactory.getLogger(PayoutService::class.java)

    private data class Account(val accountId: String?, val email: String, val detailsSubmitted: Boolean, val payoutsEnabled: Boolean) {
        fun toStatus() = PayoutAccountStatus(hasAccount = accountId != null, detailsSubmitted = detailsSubmitted, payoutsEnabled = payoutsEnabled)
    }

    private fun Connection.account(user: UserPrincipal): Account =
        queryOne(
            "SELECT stripe_account_id, email, stripe_details_submitted, payouts_enabled FROM users WHERE id = ? AND deleted_at IS NULL",
            user.id,
        ) { Account(it.getString(1), it.getString(2), it.getBoolean(3), it.getBoolean(4)) } ?: notFound("User")

    /** The caller's payout status, refreshed from the processor when an account exists (stale local state if that fails). */
    suspend fun status(user: UserPrincipal): PayoutAccountStatus {
        val local = db.tx { it.account(user) }
        val accountId = local.accountId ?: return local.toStatus()
        // Each refresh is a processor API call: past the caller's budget, serve the local state (webhooks still update it).
        if (!deps.throttle.tryAcquire(UserThrottle.Action.PROCESSOR_REFRESH, user.id)) return local.toStatus()
        val fresh = try {
            io { gateway.retrieveAccount(accountId) }
        } catch (e: PaymentGatewayException) {
            log.warn("Couldn't refresh payout account {}", accountId, e)
            null
        } ?: return local.toStatus()
        return db.tx { c ->
            c.syncPayoutAccount(accountId, fresh, Instant.now())
            c.account(user).toStatus()
        }
    }

    /** Creates the caller's Express account on first use and returns a fresh hosted onboarding link. */
    suspend fun onboardingLink(user: UserPrincipal): OnboardingLinkResponse {
        val local = db.tx { it.account(user) }
        val accountId = local.accountId ?: run {
            val created = try {
                io { gateway.createExpressAccount(user.id, local.email) }
            } catch (e: PaymentGatewayException) {
                log.warn("Couldn't create a payout account for user {}", user.id, e)
                paymentFailed("We couldn't set up payouts right now. Please try again.")
            }
            // A concurrent request may have stored one first (same idempotency key, so normally the same account).
            db.tx { c ->
                c.update("UPDATE users SET stripe_account_id = ? WHERE id = ? AND stripe_account_id IS NULL", created, user.id)
                c.account(user).accountId!!
            }
        }
        val url = try {
            io { gateway.createOnboardingLink(accountId, refreshUrl = urls.onboardingRefresh(), returnUrl = urls.onboardingReturn()) }
        } catch (e: PaymentGatewayException) {
            log.warn("Couldn't create an onboarding link for account {}", accountId, e)
            paymentFailed("We couldn't start payout setup right now. Please try again.")
        }
        return OnboardingLinkResponse(url)
    }
}
