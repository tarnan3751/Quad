package com.campusmarket.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.campusmarket.app.data.HOUR
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.data.bodyText
import com.campusmarket.app.data.decode
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.feature.orders.dispute
import com.campusmarket.app.feature.orders.order
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DeleteAccountRequest
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.DisputeOutcome
import com.campusmarket.core.DisputeReason
import com.campusmarket.core.DisputeStatus
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.OpenDisputeRequest
import com.campusmarket.core.Order
import com.campusmarket.core.OrderDispute
import com.campusmarket.core.OrderStatus
import com.campusmarket.core.Page
import com.campusmarket.core.Report
import com.campusmarket.core.ResolveDisputeRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The dispute flows in the real app UI against a fake server: buyer report, moderator resolution, suspended account. */
@OptIn(ExperimentalTestApi::class)
class DisputesUiTest {

    private lateinit var server: FakeServer

    private fun startApp(user: UserPrivate?, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        server = FakeServer(handler)
        startTestApp(server, user?.let(::signedIn) ?: InMemoryTokenStore())
    }

    private fun lastBody(path: String) = server.requestsTo(path).last().bodyText

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    // ---------------------------------------------------------------------------------------------------- buyer

    @Test
    fun buyerReportsAProblemAndSeesTheHold() = runComposeUiTest {
        var current: Order = order(OrderStatus.PAID)
        startApp(testUser) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.order("o1") -> respondJson(Order.serializer(), current)
                ApiRoutes.orderDispute("o1") -> {
                    val r = req.decode(OpenDisputeRequest.serializer())
                    current = order(OrderStatus.DISPUTED, dispute = dispute(reason = r.reason, details = r.details))
                    respondJson(Order.serializer(), current)
                }
                ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        setContent { App() }
        awaitText("Browse")
        DeepLinks.handle("campusmarket://orders/o1")

        awaitText("Released to Maya automatically")
        click("Report a problem")
        awaitText("What went wrong with")
        click("Report problem")
        awaitText("Choose what went wrong")
        click(DisputeReason.SELLER_NO_SHOW.label)
        type("What happened? (optional)", "Waited at the library, no show")
        click("Report problem")

        // back on the order: on hold, the report shown to the buyer, and they can withdraw it
        awaitText("You reported a problem")
        awaitText("Waited at the library, no show")
        awaitText("Withdraw my report")
        assertEquals(1, server.requestsTo(ApiRoutes.orderDispute("o1")).size)
        assertEquals(
            OpenDisputeRequest(DisputeReason.SELLER_NO_SHOW, "Waited at the library, no show"),
            ApiJson.decodeFromString(OpenDisputeRequest.serializer(), lastBody(ApiRoutes.orderDispute("o1"))),
        )
    }

    // ---------------------------------------------------------------------------------------------------- moderator

    private val bea = UserPublic("b1", "Bea Buyer", campusDomain = "uni.edu", joinedAt = 1L)
    private val sam = UserPublic("s1", "Sam Seller", campusDomain = "uni.edu", joinedAt = 1L)
    private val case = DisputeCase(
        orderId = "o9",
        listing = ListingSummary(
            "l9", "Mountain bike, barely used", 15000, category = ListingCategory.BIKES, condition = ListingCondition.GOOD,
            status = ListingStatus.RESERVED, sellerId = "s1", sellerName = "Sam Seller", createdAt = 1L,
        ),
        buyer = bea, seller = sam, amountCents = 15000, feeCents = 750, orderStatus = OrderStatus.DISPUTED,
        dispute = OrderDispute("d9", DisputeReason.ITEM_NOT_AS_DESCRIBED, "Gears are broken", DisputeStatus.OPEN, openedAt = System.currentTimeMillis() - HOUR),
        orderCreatedAt = 1L, paidAt = 2L, sellerSuspended = true,
    )

    @Test
    fun moderatorRefundsTheBuyerFromTheDisputeQueue() = runComposeUiTest {
        var current = case
        val mod = testUser.copy(displayName = "Mo Derator", role = UserRole.MODERATOR)
        startApp(mod) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.ME -> respondJson(UserPrivate.serializer(), mod)
                ApiRoutes.MOD_REPORTS -> respondJson(Page.serializer(Report.serializer()), Page(emptyList()))
                ApiRoutes.MOD_DISPUTES -> respondJson(Page.serializer(DisputeCase.serializer()), Page(listOf(current)))
                ApiRoutes.modDispute("o9") -> respondJson(DisputeCase.serializer(), current)
                ApiRoutes.modDisputeResolve("o9") -> {
                    val r = req.decode(ResolveDisputeRequest.serializer())
                    current = current.copy(
                        orderStatus = OrderStatus.REFUNDED,
                        dispute = current.dispute.copy(status = DisputeStatus.CLOSED, outcome = DisputeOutcome.REFUNDED_BY_MODERATOR, closedAt = System.currentTimeMillis()),
                        resolvedBy = UserPublic(mod.id, mod.displayName, campusDomain = "uni.edu", joinedAt = 1L),
                        resolutionNote = r.note,
                    )
                    respondJson(DisputeCase.serializer(), current)
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        setContent { App() }
        awaitText("Browse")
        click("Profile")
        click("Reports")
        awaitText("The queue is clear")
        clickDescription("Disputes")

        awaitText("Mountain bike, barely used")
        awaitText("Bea Buyer bought from Sam Seller")
        awaitText("Seller suspended")
        click("Mountain bike, barely used")

        awaitText("Gears are broken")
        awaitText("Seller would receive")
        awaitText("Suspended")
        click("Refund buyer")
        awaitText("Refund Bea Buyer?")
        click("Refund buyer", last = true)
        awaitText("Required")
        assertEquals(0, server.requestsTo(ApiRoutes.modDisputeResolve("o9")).size, "a note is required")
        type("Reason for the decision (required)", "Seller admitted it in chat")
        click("Refund buyer", last = true)

        awaitGone("Refund Bea Buyer?")
        awaitText("Buyer refunded after review")
        awaitText("Seller admitted it in chat")
        assertEquals(
            ResolveDisputeRequest(com.campusmarket.core.DisputeResolution.REFUND_BUYER, "Seller admitted it in chat"),
            ApiJson.decodeFromString(ResolveDisputeRequest.serializer(), lastBody(ApiRoutes.modDisputeResolve("o9"))),
        )
    }

    // ---------------------------------------------------------------------------------------------------- suspended

    private val suspendedMessage = "Your account is suspended: Violation of community guidelines"

    @Test
    fun aSuspendedUserCanDeleteTheirAccountFromSignIn() = runComposeUiTest {
        startApp(user = null) { req ->
            when (req.url.encodedPath) {
                ApiRoutes.LOGIN -> respondError(HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED, suspendedMessage)
                ApiRoutes.DELETE_ACCOUNT -> respondJson(OkResponse.serializer(), OkResponse())
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        setContent { App() }
        click("Sign in")
        type("Campus email", "ada@uni.edu")
        type("Password", "password123")
        click("Sign in", last = true)

        awaitText("Account suspended")
        awaitText("Violation of community guidelines")
        click("Delete my account", last = true)
        awaitText("To confirm it's you")
        type("Password", "password123")
        click("Continue")
        awaitText("Delete your account permanently?")
        assertEquals(0, server.requestsTo(ApiRoutes.DELETE_ACCOUNT).size, "two-step: nothing sent yet")
        click("Delete permanently")
        awaitText("Your account has been deleted.")
        val sent = ApiJson.decodeFromString(DeleteAccountRequest.serializer(), lastBody(ApiRoutes.DELETE_ACCOUNT))
        assertEquals(DeleteAccountRequest("ada@uni.edu", "password123"), sent)
        assertEquals(null, server.requests.last().headers["Authorization"])
        click("OK")
        awaitGone("Your account has been deleted.")
    }

    @Test
    fun aSessionEndedBySuspensionShowsTheNoticeWithDeletion() = runComposeUiTest {
        startApp(testUser) { respondError(HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED, suspendedMessage) }
        setContent { App() }
        // the launch-time profile refresh is refused: signed out, and the reason is explained with a way out
        awaitText("Account suspended")
        click("Delete my account", last = true)
        awaitText("To confirm it's you")
        awaitText(testUser.email) // prefilled
        click("Back")
        click("Close")
        awaitGone("To confirm it's you")
        awaitText(suspendedMessage)
        awaitText("Delete my account")
    }
}
