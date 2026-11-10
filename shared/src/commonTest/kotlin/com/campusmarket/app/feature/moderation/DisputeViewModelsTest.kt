package com.campusmarket.app.feature.moderation

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.data.jsonBody
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.withMainDispatcher
import com.campusmarket.app.ui.label
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeResolution
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.OrderDispute
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserPublic
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val buyer = UserPublic("b1", "Bea", campusDomain = "uni.edu", joinedAt = 1L)
private val seller = UserPublic("s1", "Sam", campusDomain = "uni.edu", joinedAt = 1L)
private val listing = ListingSummary(
    id = "l1", title = "Bike", priceCents = 5000, category = ListingCategory.BIKES, condition = ListingCondition.GOOD,
    status = ListingStatus.RESERVED, sellerId = "s1", sellerName = "Sam", createdAt = 1L,
)

private fun case(
    status: DisputeStatus = DisputeStatus.OPEN,
    orderStatus: OrderStatus = OrderStatus.DISPUTED,
    outcome: DisputeOutcome? = null,
) = DisputeCase(
    orderId = "o1", listing = listing, buyer = buyer, seller = seller, amountCents = 5000, feeCents = 250,
    orderStatus = orderStatus,
    dispute = OrderDispute("d1", DisputeReason.SELLER_NO_SHOW, "never came", status, outcome, openedAt = 10L, closedAt = if (outcome != null) 20L else null),
    orderCreatedAt = 1L, paidAt = 5L,
)

/** The moderators' dispute queue and resolutions. */
class DisputeViewModelsTest {

    private fun harness(initial: DisputeCase = case(), onResolve: (HttpRequestData) -> Unit = {}): Harness {
        var current = initial
        return Harness { req ->
            when (req.url.encodedPath) {
                ApiRoutes.modDispute("o1") -> respondJson(DisputeCase.serializer(), current)
                ApiRoutes.modDisputeResolve("o1") -> {
                    onResolve(req)
                    val refund = req.jsonBody()["resolution"] == JsonPrimitive("REFUND_BUYER")
                    current = current.copy(
                        orderStatus = if (refund) OrderStatus.REFUNDED else OrderStatus.COMPLETED,
                        dispute = current.dispute.copy(
                            status = DisputeStatus.CLOSED,
                            outcome = if (refund) DisputeOutcome.REFUNDED_BY_MODERATOR else DisputeOutcome.RELEASED_BY_MODERATOR,
                        ),
                        resolutionNote = (req.jsonBody()["note"] as JsonPrimitive).content,
                    )
                    respondJson(DisputeCase.serializer(), current)
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
    }

    @Test
    fun onlyAnOpenDisputeOfSomeoneElsesOrderCanBeResolved() {
        assertTrue(canResolve(case(), viewerId = "mod"))
        assertFalse(canResolve(case(), viewerId = "b1"), "the buyer")
        assertFalse(canResolve(case(), viewerId = "s1"), "the seller")
        assertFalse(canResolve(case(DisputeStatus.CLOSED, OrderStatus.REFUNDED, DisputeOutcome.SELLER_REFUNDED), viewerId = "mod"))
        assertFalse(canResolve(case(orderStatus = OrderStatus.PAID), viewerId = "mod"))
        assertEquals(4750, case().sellerShareCents)
    }

    @Test
    fun refundingTheBuyerSendsTheNoteAndShowsTheOutcome() = withMainDispatcher {
        runTest {
            var sent: HttpRequestData? = null
            val messages = UserMessages()
            val h = harness { sent = it }
            val vm = DisputeDetailViewModel(DisputeDetailRoute("o1"), h.api, messages)
            vm.case.awaitValue { it is UiState.Content }

            vm.resolve(DisputeResolution.REFUND_BUYER, "  seller admitted the no-show  ", viewerId = "mod")
            vm.actionDone.flow.first()

            val body = sent!!.jsonBody()
            assertEquals(JsonPrimitive("REFUND_BUYER"), body["resolution"])
            assertEquals(JsonPrimitive("seller admitted the no-show"), body["note"])
            val state = vm.case.value
            assertTrue(state is UiState.Content && state.value.orderStatus == OrderStatus.REFUNDED)
            assertEquals(DisputeOutcome.REFUNDED_BY_MODERATOR, (state as UiState.Content).value.dispute.outcome)
            assertEquals("Buyer refunded", messages.messages.first().text)
        }
    }

    @Test
    fun releasingToTheSeller() = withMainDispatcher {
        runTest {
            var sent: HttpRequestData? = null
            val h = harness { sent = it }
            val vm = DisputeDetailViewModel(DisputeDetailRoute("o1"), h.api, UserMessages())
            vm.case.awaitValue { it is UiState.Content }
            vm.resolve(DisputeResolution.RELEASE_TO_SELLER, "handoff confirmed in chat", viewerId = "mod")
            vm.actionDone.flow.first()
            assertEquals(JsonPrimitive("RELEASE_TO_SELLER"), sent!!.jsonBody()["resolution"])
            val state = vm.case.value
            assertTrue(state is UiState.Content && state.value.orderStatus == OrderStatus.COMPLETED)
        }
    }

    @Test
    fun aNoteIsRequiredAndPartiesCantResolveTheirOwnOrder() = withMainDispatcher {
        runTest {
            val h = harness()
            val vm = DisputeDetailViewModel(DisputeDetailRoute("o1"), h.api, UserMessages())
            vm.case.awaitValue { it is UiState.Content }

            vm.resolve(DisputeResolution.REFUND_BUYER, "   ", viewerId = "mod")
            assertEquals("Add a note explaining the decision.", vm.runner.state.awaitValue { it.error != null }.error)
            vm.runner.clearError()
            vm.resolve(DisputeResolution.REFUND_BUYER, "x", viewerId = "s1")
            vm.runner.state.awaitValue { it.error != null }
            assertTrue(h.requestsTo(ApiRoutes.modDisputeResolve("o1")).isEmpty())
        }
    }

    @Test
    fun aDisputeSettledMeanwhileShowsTheErrorAndReloads() = withMainDispatcher {
        runTest {
            var settled = false
            val h = Harness { req ->
                when (req.url.encodedPath) {
                    ApiRoutes.modDispute("o1") -> respondJson(
                        DisputeCase.serializer(),
                        if (settled) case(DisputeStatus.CLOSED, OrderStatus.COMPLETED, DisputeOutcome.BUYER_CONFIRMED) else case(),
                    )
                    else -> {
                        settled = true
                        respondError(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE, "This dispute is no longer open")
                    }
                }
            }
            val vm = DisputeDetailViewModel(DisputeDetailRoute("o1"), h.api, UserMessages())
            vm.case.awaitValue { it is UiState.Content }
            vm.resolve(DisputeResolution.REFUND_BUYER, "refund", viewerId = "mod")
            assertEquals("This dispute is no longer open", vm.runner.state.awaitValue { it.error != null }.error)
            vm.case.awaitValue { it is UiState.Content && it.value.dispute.outcome == DisputeOutcome.BUYER_CONFIRMED }
            assertEquals(2, h.requests.count { it.method == HttpMethod.Get })
        }
    }

    @Test
    fun queueFiltersByStatus() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                val status = DisputeStatus.valueOf(req.url.parameters["status"]!!)
                respondJson(
                    Page.serializer(DisputeCase.serializer()),
                    Page(listOf(if (status == DisputeStatus.OPEN) case() else case(DisputeStatus.CLOSED, OrderStatus.REFUNDED, DisputeOutcome.REFUNDED_BY_MODERATOR))),
                )
            }
            val vm = DisputesViewModel(h.api, UserMessages())
            vm.disputes.refresh()
            vm.disputes.state.awaitValue { it.loaded }
            assertEquals("OPEN", h.requests.first().url.parameters["status"])
            assertEquals(ApiRoutes.MOD_DISPUTES, h.requests.first().url.encodedPath)

            vm.select(DisputeStatus.CLOSED)
            vm.disputes.state.awaitValue { it.loaded && it.items.singleOrNull()?.dispute?.status == DisputeStatus.CLOSED }
            assertEquals("CLOSED", h.requests.last().url.parameters["status"])
        }
    }

    @Test
    fun theLogRendersDisputeResolutions() {
        assertEquals("Resolve dispute", ModerationAction.RESOLVE_DISPUTE.label)
        assertEquals("Order", ReportTargetType.ORDER.label)
        assertEquals("Refund buyer", DisputeResolution.REFUND_BUYER.label)
        assertEquals("Withdrawn by the buyer", DisputeOutcome.WITHDRAWN.label)
        // every value has a label (exhaustive whens over the extended enums)
        ModerationAction.entries.forEach { assertTrue(it.label.isNotBlank()) }
        DisputeOutcome.entries.forEach { assertTrue(it.label.isNotBlank()) }
        OrderStatus.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
