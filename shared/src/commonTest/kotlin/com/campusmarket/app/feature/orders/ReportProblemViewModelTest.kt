package com.campusmarket.app.feature.orders

import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.feature.chat.VmHarness
import com.campusmarket.app.feature.chat.bodyText
import com.campusmarket.app.feature.chat.close
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.Order
import com.campusmarket.core.OrderStatus
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The buyer's "Report a problem" form: validation, the request it sends, and server errors. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportProblemViewModelTest {
    private var current: Order = order(OrderStatus.PAID)
    private var disputeError: Triple<HttpStatusCode, String, Map<String, String>>? = null

    private fun TestScope.setUp(): Triple<ReportProblemViewModel, VmHarness, OrderUpdates> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = VmHarness(this) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.order("o1") -> respondJson(Order.serializer(), current)
                ApiRoutes.orderDispute("o1") -> {
                    val err = disputeError
                    if (err != null) {
                        respondError(err.first, err.second, "Server says no", err.third)
                    } else {
                        val body = ApiJson.decodeFromString(OpenDisputeRequest.serializer(), req.bodyText())
                        current = order(OrderStatus.DISPUTED, dispute = dispute(reason = body.reason, details = body.details))
                        respondJson(Order.serializer(), current)
                    }
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val updates = OrderUpdates()
        return Triple(ReportProblemViewModel(ReportProblemRoute("o1"), h.api, updates, h.userMessages), h, updates)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aReasonIsRequiredAndSomethingElseNeedsDetails() = runTest {
        val (vm, h, _) = setUp()
        advanceUntilIdle()
        assertEquals(listOf(DisputeReason.SELLER_NO_SHOW, DisputeReason.ITEM_NOT_AS_DESCRIBED, DisputeReason.OTHER), vm.reasons)

        vm.submit()
        assertEquals("Choose what went wrong", vm.state.value.fieldErrors["reason"])

        vm.onReason(DisputeReason.SELLER_SUSPENDED) // platform-only: ignored
        assertNull(vm.state.value.reason)

        vm.onReason(DisputeReason.OTHER)
        assertTrue(vm.state.value.detailsRequired)
        assertNull(vm.state.value.fieldErrors["reason"], "picking a reason clears its error")
        vm.onDetails("   ")
        vm.submit()
        assertEquals("Tell us what went wrong", vm.state.value.fieldErrors["details"])
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.orderDispute("o1")).isEmpty(), "nothing sent while invalid")

        // details are capped at the limit as you type
        vm.onDetails("x".repeat(Limits.DISPUTE_DETAILS_MAX + 50))
        assertEquals(Limits.DISPUTE_DETAILS_MAX, vm.state.value.details.length)
        vm.close()
    }

    @Test
    fun submittingReportsTheProblemAndPublishesTheHeldOrder() = runTest {
        val (vm, h, updates) = setUp()
        advanceUntilIdle()
        val published = mutableListOf<Order>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { updates.updates.collect { published += it } }

        vm.onReason(DisputeReason.ITEM_NOT_AS_DESCRIBED) // details optional
        vm.onDetails("  The screen is cracked  ")
        vm.submit()
        assertTrue(vm.state.value.submitting)
        advanceUntilIdle()

        val sent = ApiJson.decodeFromString(OpenDisputeRequest.serializer(), h.requests(HttpMethod.Post, ApiRoutes.orderDispute("o1")).single().bodyText())
        assertEquals(OpenDisputeRequest(DisputeReason.ITEM_NOT_AS_DESCRIBED, "The screen is cracked"), sent)
        assertEquals(OrderStatus.DISPUTED, published.single().status)
        assertEquals(Unit, vm.done.flow.first())
        assertEquals("Problem reported. Your payment is on hold while it's sorted out.", h.nextMessage()?.text)
        assertTrue(!vm.state.value.submitting)
        vm.close()
    }

    @Test
    fun serverErrorsLandOnTheForm() = runTest {
        val (vm, _, _) = setUp()
        advanceUntilIdle()
        vm.onReason(DisputeReason.SELLER_NO_SHOW)

        // e.g. the payment is already being released
        disputeError = Triple(HttpStatusCode.Conflict, ErrorCodes.INVALID_ORDER_STATE, emptyMap())
        vm.submit()
        advanceUntilIdle()
        assertEquals("Server says no", vm.state.value.formError)

        disputeError = Triple(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, mapOf("details" to "Too long"))
        vm.submit()
        advanceUntilIdle()
        assertEquals("Too long", vm.state.value.fieldErrors["details"])
        vm.onDetails("short")
        assertNull(vm.state.value.fieldErrors["details"], "typing clears it")
        vm.close()
    }

    @Test
    fun theOrderIsLoadedForContext() = runTest {
        val (vm, _, _) = setUp()
        advanceUntilIdle()
        assertNotNull(vm.order.value.let { (it as? com.campusmarket.app.ui.state.UiState.Content)?.value })
        vm.close()
    }
}
