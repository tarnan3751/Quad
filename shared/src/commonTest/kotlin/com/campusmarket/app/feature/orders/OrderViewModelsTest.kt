package com.campusmarket.app.feature.orders

import com.campusmarket.app.data.BASE
import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.bodyText
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.session
import com.campusmarket.app.data.testUser
import com.campusmarket.app.feature.chat.close
import com.campusmarket.app.feature.chat.listingSummary
import com.campusmarket.app.feature.chat.publicUser
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.valueOrNull
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Conversation
import com.campusmarket.core.CreateRatingRequest
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.OnboardingLinkResponse
import com.campusmarket.core.Order
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.PayoutAccountStatus
import com.campusmarket.core.Photo
import com.campusmarket.core.Rating
import com.campusmarket.core.UserPrivate
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.mainOnTestScheduler() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

// ---------------------------------------------------------------------------------------------------- detail

/** Order detail action state: confirm / cancel / pay / message, conflicts, resume refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderDetailViewModelTest {
    private var current: Order = order(OrderStatus.PAID)
    private var actionError: Pair<HttpStatusCode, String>? = null
    private val conversations = mutableListOf<Conversation>()

    private fun TestScope.setUp(user: UserPrivate = testUser, fromCheckout: Boolean = false): Pair<OrderDetailViewModel, Harness> {
        mainOnTestScheduler()
        val h = Harness(session(user = user), scope = this) { req ->
            val err = actionError
            when (req.url.encodedPath) {
                ApiRoutes.order("o1") -> respondJson(Order.serializer(), current)
                ApiRoutes.orderConfirm("o1"), ApiRoutes.orderCancel("o1") -> if (err != null) {
                    respondError(err.first, err.second, "Only a paid order can be confirmed")
                } else {
                    current = current.copy(
                        status = if (req.url.encodedPath.endsWith("confirm")) OrderStatus.COMPLETED
                        else if (current.status == OrderStatus.PAID) OrderStatus.REFUNDED else OrderStatus.CANCELLED,
                        checkoutUrl = null,
                    )
                    respondJson(Order.serializer(), current)
                }
                ApiRoutes.orderDisputeWithdraw("o1") -> if (err != null) {
                    respondError(err.first, err.second, "There's no open problem report on this order")
                } else {
                    current = current.copy(
                        status = OrderStatus.PAID,
                        dispute = current.dispute?.copy(status = DisputeStatus.CLOSED, outcome = DisputeOutcome.WITHDRAWN),
                    )
                    respondJson(Order.serializer(), current)
                }
                ApiRoutes.CONVERSATIONS -> if (req.method == HttpMethod.Post) {
                    respondJson(Conversation.serializer(), Conversation("conv-1", current.listing, sellerUser, createdAt = 0))
                } else {
                    respondJson(Page.serializer(Conversation.serializer()), Page(conversations.toList(), null))
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val updates = OrderUpdates()
        return OrderDetailViewModel(OrderDetailRoute("o1", fromCheckout), h.api, h.links, updates, h.messages, testScheduler.timeSource) to h
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun buyerConfirmsReceiptAfterTheDialog() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        assertEquals(OrderStatus.PAID, vm.order.value.valueOrNull?.status)

        vm.onAction(OrderAction.ConfirmReceipt)
        assertEquals(OrderAction.ConfirmReceipt, vm.ui.value.confirming, "asks first")
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.orderConfirm("o1")).isEmpty())

        vm.confirm()
        assertEquals(OrderAction.ConfirmReceipt, vm.ui.value.running)
        advanceUntilIdle()
        assertEquals(OrderStatus.COMPLETED, vm.order.value.valueOrNull?.status)
        assertNull(vm.ui.value.running)
        assertNull(vm.ui.value.confirming)
        assertEquals("Thanks! Payment released to Maya Chen.", h.nextMessage()?.text)
        assertEquals(listOf(OrderAction.Rate, OrderAction.Message), vm.order.value.valueOrNull?.availableActions())
        vm.close()
    }

    @Test
    fun dismissingTheDialogDoesNothing() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.onAction(OrderAction.Cancel)
        vm.dismissConfirm()
        vm.confirm()
        advanceUntilIdle()
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.orderCancel("o1")).isEmpty())
        vm.close()
    }

    @Test
    fun sellerCancellingAPaidOrderRefunds() = runTest {
        current = order(OrderStatus.PAID, OrderRole.SELLER)
        val (vm, h) = setUp(user = testUser.copy(id = "u2", displayName = "Maya Chen"))
        advanceUntilIdle()
        assertTrue(OrderAction.Cancel in vm.order.value.valueOrNull!!.availableActions())
        vm.onAction(OrderAction.Cancel)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(OrderStatus.REFUNDED, vm.order.value.valueOrNull?.status)
        assertEquals("Order cancelled and the buyer refunded.", h.nextMessage()?.text)
        vm.close()
    }

    @Test
    fun buyerOfAPaidOrderReportsAProblemInsteadOfCancelling() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        val actions = vm.order.value.valueOrNull!!.availableActions()
        assertTrue(OrderAction.Cancel !in actions, "no self-refund once paid")
        assertTrue(OrderAction.ReportProblem in actions)

        vm.onAction(OrderAction.ReportProblem)
        assertEquals(OrderDetailEvent.OpenReportProblem("o1"), vm.events.flow.first())
        assertNull(vm.ui.value.confirming, "the form is its own screen, no dialog")
        assertEquals(1, h.requests.size, "nothing sent yet")
        vm.close()
    }

    @Test
    fun forbiddenRefreshesTheOrder() = runTest {
        // e.g. an outdated screen still offered a cancel the server no longer allows
        val (vm, h) = setUp()
        advanceUntilIdle()
        actionError = HttpStatusCode.Forbidden to ErrorCodes.FORBIDDEN
        vm.onAction(OrderAction.Cancel)
        vm.confirm()
        advanceUntilIdle()
        assertNotNull(vm.ui.value.error)
        assertEquals(2, h.requests(HttpMethod.Get, ApiRoutes.order("o1")).size)
        vm.close()
    }

    @Test
    fun buyerWithdrawsTheirReportAfterTheDialog() = runTest {
        current = order(OrderStatus.DISPUTED)
        val (vm, h) = setUp()
        advanceUntilIdle()
        assertTrue(OrderAction.WithdrawDispute in vm.order.value.valueOrNull!!.availableActions())

        vm.onAction(OrderAction.WithdrawDispute)
        assertEquals(OrderAction.WithdrawDispute, vm.ui.value.confirming)
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.orderDisputeWithdraw("o1")).isEmpty(), "asks first")
        vm.confirm()
        advanceUntilIdle()
        val o = vm.order.value.valueOrNull!!
        assertEquals(OrderStatus.PAID, o.status)
        assertEquals(DisputeOutcome.WITHDRAWN, o.dispute?.outcome)
        assertEquals("Report withdrawn. Your payment is back on hold.", h.nextMessage()?.text)
        vm.close()
    }

    @Test
    fun buyerCanStillConfirmADisputedOrder() = runTest {
        current = order(OrderStatus.DISPUTED)
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.onAction(OrderAction.ConfirmReceipt)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(OrderStatus.COMPLETED, vm.order.value.valueOrNull?.status)
        assertEquals(1, h.requests(HttpMethod.Post, ApiRoutes.orderConfirm("o1")).size)
        vm.close()
    }

    @Test
    fun invalidStateShowsTheErrorAndReloadsTheRealState() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        // Meanwhile the order was refunded elsewhere.
        actionError = HttpStatusCode.Conflict to ErrorCodes.INVALID_ORDER_STATE
        current = current.copy(status = OrderStatus.REFUNDED)

        vm.onAction(OrderAction.ConfirmReceipt)
        vm.confirm()
        advanceUntilIdle()
        assertEquals("Only a paid order can be confirmed", vm.ui.value.error)
        assertNull(vm.ui.value.running)
        assertEquals(OrderStatus.REFUNDED, vm.order.value.valueOrNull?.status, "refreshed from the server")
        assertEquals(2, h.requests(HttpMethod.Get, ApiRoutes.order("o1")).size)
        vm.close()
    }

    @Test
    fun payReopensTheCheckoutUrlAndResumeRefreshes() = runTest {
        current = order(OrderStatus.PENDING_PAYMENT)
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.onResume() // initial display: just loaded, no extra request
        assertEquals(1, h.requests(HttpMethod.Get, ApiRoutes.order("o1")).size)
        vm.onAction(OrderAction.Pay)
        assertEquals(listOf("$BASE/api/v1/dev/fake-stripe/checkout/o1"), h.opened, "loopback URL resolved to the app's server")

        // Paid in the browser; back in the app a while later.
        current = order(OrderStatus.PAID)
        advanceTimeBy(30_000)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(OrderStatus.PAID, vm.order.value.valueOrNull?.status)
        assertEquals(2, h.requests(HttpMethod.Get, ApiRoutes.order("o1")).size)
        vm.close()
    }

    @Test
    fun firstResumeAfterCheckoutOpenedInTheBrowserRefreshes() = runTest {
        // "Continue to payment" opens the browser and then shows this screen behind it: it loads while the browser is
        // in front, and its very first resume is the user coming back from Stripe.
        current = order(OrderStatus.PENDING_PAYMENT)
        val (vm, h) = setUp()
        advanceUntilIdle()
        current = order(OrderStatus.PAID)
        advanceTimeBy(20_000)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(OrderStatus.PAID, vm.order.value.valueOrNull?.status)
        assertEquals(2, h.requests(HttpMethod.Get, ApiRoutes.order("o1")).size)
        vm.close()
    }

    @Test
    fun userRefreshShowsTheIndicator() = runTest {
        val (vm, _) = setUp()
        advanceUntilIdle()
        vm.refresh(userInitiated = true)
        assertTrue(vm.ui.value.refreshing)
        advanceUntilIdle()
        assertTrue(!vm.ui.value.refreshing)
        vm.close()
    }

    @Test
    fun backFromCheckoutConfirmsThePaymentWhenStripeCatchesUp() = runTest {
        current = order(OrderStatus.PENDING_PAYMENT)
        val (vm, h) = setUp(fromCheckout = true)
        advanceUntilIdle()
        vm.onResume() // back from the browser; the webhook hasn't landed yet
        runCurrent()
        assertEquals(PaymentCheck.Checking, vm.ui.value.paymentCheck)

        current = order(OrderStatus.PAID)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(OrderStatus.PAID, vm.order.value.valueOrNull?.status)
        assertNull(vm.ui.value.paymentCheck)
        assertEquals("Payment received. Quad holds it until you confirm pickup.", h.nextMessage()?.text)

        // Settled: later resumes are ordinary refreshes.
        advanceTimeBy(10_000)
        vm.onResume()
        advanceUntilIdle()
        assertNull(vm.ui.value.paymentCheck)
        vm.close()
    }

    @Test
    fun backFromCheckoutWithoutPayingSaysSoAndPayRetries() = runTest {
        current = order(OrderStatus.PENDING_PAYMENT)
        val (vm, h) = setUp(fromCheckout = true)
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(PaymentCheck.NotReceived, vm.ui.value.paymentCheck)
        val checks = h.requests(HttpMethod.Get, ApiRoutes.order("o1")).size
        assertTrue(checks in 4..7, "a handful of checks, then it stops ($checks)")

        vm.onAction(OrderAction.Pay)
        assertNull(vm.ui.value.paymentCheck, "back to plain pending while checkout is open again")
        assertEquals(1, h.opened.size)
        vm.close()
    }

    @Test
    fun checkAgainRunsAnotherRoundAndPicksUpTheLatePayment() = runTest {
        current = order(OrderStatus.PENDING_PAYMENT)
        val (vm, h) = setUp(fromCheckout = true)
        advanceUntilIdle()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(PaymentCheck.NotReceived, vm.ui.value.paymentCheck)

        current = order(OrderStatus.PAID)
        vm.recheckPayment()
        advanceUntilIdle()
        assertEquals(OrderStatus.PAID, vm.order.value.valueOrNull?.status)
        assertNull(vm.ui.value.paymentCheck)
        assertEquals("Payment received. Quad holds it until you confirm pickup.", h.nextMessage()?.text)
        vm.close()
    }

    @Test
    fun browserThatWontOpenIsReported() = runTest {
        current = order(OrderStatus.PENDING_PAYMENT)
        val (vm, h) = setUp()
        h.launcherSucceeds = false
        advanceUntilIdle()
        vm.onAction(OrderAction.Pay)
        assertNotNull(vm.ui.value.error)
        vm.close()
    }

    @Test
    fun buyerMessageStartsOrReusesTheConversation() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.onAction(OrderAction.Message)
        assertEquals(OrderAction.Message, vm.ui.value.running)
        advanceUntilIdle()
        assertEquals(OrderDetailEvent.OpenConversation("conv-1"), vm.events.flow.first())
        assertEquals(1, h.requests(HttpMethod.Post, ApiRoutes.CONVERSATIONS).size)
        assertNull(vm.ui.value.running)
        vm.close()
    }

    @Test
    fun sellerMessageFindsTheBuyersConversation() = runTest {
        current = order(OrderStatus.PAID, OrderRole.SELLER)
        val (vm, h) = setUp(user = testUser.copy(id = "u2", displayName = "Maya Chen"))
        advanceUntilIdle()

        vm.onAction(OrderAction.Message)
        advanceUntilIdle()
        assertTrue(vm.ui.value.error!!.contains("hasn't messaged you"), "no conversation yet")
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.CONVERSATIONS).isEmpty(), "sellers never start conversations")

        conversations += Conversation("conv-other", listingSummary(id = "other"), buyerUser, createdAt = 0)
        conversations += Conversation("conv-liam", current.listing, buyerUser, createdAt = 0)
        vm.onAction(OrderAction.Message)
        advanceUntilIdle()
        assertEquals(OrderDetailEvent.OpenConversation("conv-liam"), vm.events.flow.first())
        vm.close()
    }
}

// ---------------------------------------------------------------------------------------------------- checkout

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private var listing = listingDetail()
    private var createError: Pair<HttpStatusCode, String>? = null
    private var myOrders = emptyList<Order>()

    private fun listingDetail(status: ListingStatus = ListingStatus.ACTIVE, isMine: Boolean = false) = ListingDetail(
        id = "l1", title = "Desk lamp", description = "", priceCents = 2500, category = ListingCategory.DORM,
        condition = ListingCondition.GOOD, status = status, photos = listOf(Photo("p1", "http://localhost:8080/uploads/p1.jpg")),
        seller = sellerUser, isMine = isMine, createdAt = 0, updatedAt = 0,
    )

    private fun TestScope.setUp(): Triple<CheckoutViewModel, Harness, OrderUpdates> {
        mainOnTestScheduler()
        val h = Harness(scope = this) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.listing("l1") -> respondJson(ListingDetail.serializer(), listing)
                ApiRoutes.ORDERS -> if (req.method == HttpMethod.Post) {
                    createError?.let { (status, code) -> respondError(status, code, "nope") }
                        ?: respondJson(Order.serializer(), order(OrderStatus.PENDING_PAYMENT, id = "o-new"))
                } else {
                    respondJson(Page.serializer(Order.serializer()), Page(myOrders, null))
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val updates = OrderUpdates()
        return Triple(CheckoutViewModel(CheckoutRoute("l1"), h.api, h.links, updates, h.messages), h, updates)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun placingTheOrderOpensCheckoutAndShowsTheOrder() = runTest {
        val (vm, h, updates) = setUp()
        advanceUntilIdle()
        val published = mutableListOf<Order>()
        backgroundScope.launch { updates.updates.collect { published += it } }

        vm.placeOrder()
        assertTrue(vm.ui.value.placing)
        advanceUntilIdle()
        runCurrent() // advanceUntilIdle() stops when only backgroundScope work (the collector) is left
        assertEquals(CheckoutEvent.OrderPlaced("o-new"), vm.events.flow.first())
        assertEquals(listOf("$BASE/api/v1/dev/fake-stripe/checkout/o-new"), h.opened)
        assertEquals(listOf("o-new"), published.map { it.id })
        assertNull(vm.ui.value.problem)
        assertTrue(!vm.ui.value.placing)
        vm.close()
    }

    @Test
    fun browserFailureStillShowsTheOrderAndSaysWhatToDo() = runTest {
        val (vm, h, _) = setUp()
        h.launcherSucceeds = false
        advanceUntilIdle()
        vm.placeOrder()
        advanceUntilIdle()
        assertEquals(CheckoutEvent.OrderPlaced("o-new", checkoutOpened = false), vm.events.flow.first())
        assertTrue(h.nextMessage()!!.text.contains("Continue to payment"))
        vm.close()
    }

    @Test
    fun ownListingIsBlockedUpFront() = runTest {
        listing = listingDetail(isMine = true)
        val (vm, h, _) = setUp()
        advanceUntilIdle()
        assertEquals(CheckoutProblem.OwnListing, vm.ui.value.problem)
        vm.placeOrder()
        advanceUntilIdle()
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.ORDERS).isEmpty())
        vm.close()
    }

    @Test
    fun serverErrorsMapToClearProblems() = runTest {
        val cases = listOf(
            (HttpStatusCode.Conflict to ErrorCodes.PAYOUTS_NOT_ENABLED) to CheckoutProblem.PayoutsNotEnabled,
            (HttpStatusCode.Conflict to ErrorCodes.LISTING_UNAVAILABLE) to CheckoutProblem.Unavailable,
            (HttpStatusCode.Forbidden to ErrorCodes.BLOCKED) to CheckoutProblem.Blocked,
            (HttpStatusCode.BadRequest to ErrorCodes.VALIDATION) to CheckoutProblem.OwnListing,
        )
        for ((error, expected) in cases) {
            createError = error
            val (vm, _, _) = setUp()
            advanceUntilIdle()
            vm.placeOrder()
            advanceUntilIdle()
            assertEquals(expected, vm.ui.value.problem, "for ${error.second}")
            vm.close()
        }
    }

    @Test
    fun otherErrorsStayRetryable() = runTest {
        createError = HttpStatusCode.BadGateway to ErrorCodes.PAYMENT_FAILED
        val (vm, _, _) = setUp()
        advanceUntilIdle()
        vm.placeOrder()
        advanceUntilIdle()
        assertNull(vm.ui.value.problem)
        assertEquals("nope", vm.ui.value.error)
        vm.close()
    }

    @Test
    fun reservedListingOffersMyExistingOrder() = runTest {
        listing = listingDetail(status = ListingStatus.RESERVED)
        myOrders = listOf(order(OrderStatus.PENDING_PAYMENT, id = "o-mine"))
        val (vm, _, _) = setUp()
        advanceUntilIdle()
        assertEquals(CheckoutProblem.Unavailable, vm.ui.value.problem)
        assertEquals("o-mine", vm.ui.value.existingOrderId)
        vm.close()
    }
}

// ---------------------------------------------------------------------------------------------------- rating

@OptIn(ExperimentalCoroutinesApi::class)
class RateOrderViewModelTest {
    private var rateStatus: Pair<HttpStatusCode, String>? = null

    private fun TestScope.setUp(stars: Int = 0): Pair<RateOrderViewModel, Harness> {
        mainOnTestScheduler()
        val h = Harness(scope = this) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.order("o1") -> respondJson(Order.serializer(), order(OrderStatus.COMPLETED))
                ApiRoutes.orderRating("o1") -> rateStatus?.let { (s, c) -> respondError(s, c, "Already rated") }
                    ?: respondJson(Rating.serializer(), Rating("r1", "o1", publicUser("u1", "Liam"), "u2", 4, "", createdAt = 0))
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        return RateOrderViewModel(RateOrderRoute("o1", stars), h.api, OrderUpdates(), h.messages) to h
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun needsStarsThenPostsTheRating() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.submit()
        assertEquals("Choose 1 to 5 stars", vm.state.value.fieldErrors["stars"])
        assertTrue(h.requests(HttpMethod.Post, ApiRoutes.orderRating("o1")).isEmpty())

        vm.onStars(4)
        vm.onComment("  Friendly and on time  ")
        vm.submit()
        advanceUntilIdle()
        val sent = ApiJson.decodeFromString(CreateRatingRequest.serializer(), h.requests(HttpMethod.Post, ApiRoutes.orderRating("o1")).single().bodyText)
        assertEquals(CreateRatingRequest(4, "Friendly and on time"), sent)
        assertEquals(Unit, vm.done.flow.first())
        vm.close()
    }

    @Test
    fun alreadyRatedCountsAsDone() = runTest {
        rateStatus = HttpStatusCode.Conflict to ErrorCodes.CONFLICT
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.onStars(5)
        vm.submit()
        advanceUntilIdle()
        assertEquals(Unit, vm.done.flow.first())
        assertEquals("You've already rated this order.", h.nextMessage()?.text)
        vm.close()
    }

    @Test
    fun commentIsCappedAtTheLimit() = runTest {
        val (vm, _) = setUp()
        vm.onComment("x".repeat(com.campusmarket.core.Limits.RATING_COMMENT_MAX + 10))
        assertEquals(com.campusmarket.core.Limits.RATING_COMMENT_MAX, vm.state.value.comment.length)
        vm.close()
    }

    @Test
    fun starsTappedOnTheOrderArePreselected() = runTest {
        val (vm, h) = setUp(stars = 4)
        advanceUntilIdle()
        assertEquals(4, vm.state.value.stars)
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, h.requests(HttpMethod.Post, ApiRoutes.orderRating("o1")).size)
        vm.close()
    }
}

// ---------------------------------------------------------------------------------------------------- payouts

@OptIn(ExperimentalCoroutinesApi::class)
class PayoutsViewModelTest {
    private var status = PayoutAccountStatus(hasAccount = false)

    private fun TestScope.setUp(): Pair<PayoutsViewModel, Harness> {
        mainOnTestScheduler()
        val h = Harness(scope = this) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.PAYOUT_ACCOUNT -> respondJson(PayoutAccountStatus.serializer(), status)
                ApiRoutes.PAYOUT_ONBOARDING -> respondJson(OnboardingLinkResponse.serializer(), OnboardingLinkResponse("http://localhost:8080/api/v1/dev/fake-stripe/onboarding/acct_1"))
                ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser.copy(payoutsEnabled = status.payoutsEnabled))
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        return PayoutsViewModel(h.api, h.session, h.links, h.messages) to h
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun onboardingRoundTripUpdatesStatusAndSession() = runTest {
        val (vm, h) = setUp()
        advanceUntilIdle()
        vm.onResume() // initial display
        assertEquals(PayoutStage.NotStarted, vm.account.value.valueOrNull?.stage)

        vm.startOnboarding()
        advanceUntilIdle()
        assertEquals(listOf("$BASE/api/v1/dev/fake-stripe/onboarding/acct_1"), h.opened)

        // Completed in the browser; the app comes back (deep link or plain resume).
        status = PayoutAccountStatus(hasAccount = true, detailsSubmitted = true, payoutsEnabled = true)
        vm.onResume()
        advanceUntilIdle()
        assertEquals(PayoutStage.Enabled, vm.account.value.valueOrNull?.stage)
        assertEquals(true, h.session.currentUser?.payoutsEnabled, "cached user refreshed")
        assertEquals("Payouts are set up. Buyers can now pay you in the app.", h.nextMessage()?.text)
        vm.close()
    }

    @Test
    fun loadFailureIsAnErrorState() = runTest {
        mainOnTestScheduler()
        val h = Harness(scope = this) { respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL) }
        val vm = PayoutsViewModel(h.api, h.session, h.links, h.messages)
        advanceUntilIdle()
        assertIs<UiState.Error>(vm.account.value)
        vm.close()
    }
}

// ---------------------------------------------------------------------------------------------------- list

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsOnTheRequestedTabAndAppliesUpdates() = runTest {
        mainOnTestScheduler()
        val h = Harness(scope = this) { req ->
            val role = req.url.parameters["role"]
            val items = if (role == "seller") listOf(order(OrderStatus.PAID, OrderRole.SELLER, id = "s1")) else listOf(order(OrderStatus.PAID))
            respondJson(Page.serializer(Order.serializer()), Page(items, null))
        }
        val updates = OrderUpdates()
        val vm = OrdersViewModel(OrdersRoute(role = "seller"), h.api, updates, h.messages)
        assertEquals(OrderRole.SELLER, vm.tab.value)
        vm.buying.refresh()
        vm.selling.refresh()
        advanceUntilIdle()
        assertEquals(listOf("buyer", "seller"), h.requests.map { it.url.parameters["role"] }.sortedBy { it })

        updates.publish(order(OrderStatus.COMPLETED))
        updates.publish(order(OrderStatus.PENDING_PAYMENT, id = "o-new"))
        advanceUntilIdle()
        assertEquals(listOf("o-new:PENDING_PAYMENT", "o1:COMPLETED"), vm.buying.state.value.items.map { "${it.id}:${it.status}" })
        assertEquals(listOf("s1"), vm.selling.state.value.items.map { it.id })

        assertEquals(OrderRole.BUYER, OrdersViewModel(OrdersRoute(), h.api, updates, h.messages).tab.value)
        vm.close()
    }
}
