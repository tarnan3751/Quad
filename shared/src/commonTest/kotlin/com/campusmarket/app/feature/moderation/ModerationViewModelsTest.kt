package com.campusmarket.app.feature.moderation

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.data.jsonBody
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.withMainDispatcher
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.UserPublic
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val reporter = UserPublic("r1", "Rita", campusDomain = "uni.edu", joinedAt = 1L)
private val seller = UserPublic("s1", "Sam", campusDomain = "uni.edu", joinedAt = 1L)
private val listing = ListingSummary(
    id = "l1", title = "Bike", priceCents = 5000, category = ListingCategory.BIKES, condition = ListingCondition.GOOD,
    status = ListingStatus.ACTIVE, sellerId = "s1", sellerName = "Sam", createdAt = 1L,
)

private fun report(
    type: ReportTargetType = ReportTargetType.LISTING,
    status: ReportStatus = ReportStatus.OPEN,
    withListing: Boolean = type == ReportTargetType.LISTING,
) = Report(
    id = "rep1", reporter = reporter, targetType = type, targetId = if (type == ReportTargetType.USER) "s1" else "l1",
    reason = ReportReason.SCAM, status = status, targetSummary = "Bike", targetUser = seller,
    targetListing = listing.takeIf { withListing }, openReportsAgainstTargetUser = 2, createdAt = 1L,
)

class ReportFormControllerTest {

    @Test
    fun requiresAReasonBeforeSubmitting() = runTest {
        val h = Harness { error("no request expected") }
        val c = ReportFormController(h.api, ReportTargetType.LISTING, "l1", backgroundScope)
        c.submit()
        assertEquals("Choose a reason", c.state.value.reasonError)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun submitsTheReportAndShowsConfirmation() = runTest {
        val h = Harness { respondJson(OkResponse.serializer(), OkResponse()) }
        val c = ReportFormController(h.api, ReportTargetType.LISTING, "l1", backgroundScope)
        c.onReason(ReportReason.SCAM)
        c.onDetails("  asked me to pay outside the app ")
        c.submit()
        c.state.awaitValue { it.submitted }

        val req = h.requests.single()
        assertEquals(ApiRoutes.REPORTS, req.url.encodedPath)
        val body = req.jsonBody()
        assertEquals(JsonPrimitive("LISTING"), body["targetType"])
        assertEquals(JsonPrimitive("l1"), body["targetId"])
        assertEquals(JsonPrimitive("SCAM"), body["reason"])
        assertEquals(JsonPrimitive("asked me to pay outside the app"), body["details"])
    }

    @Test
    fun capsDetailsAtTheLimit() = runTest {
        val h = Harness { error("no request expected") }
        val c = ReportFormController(h.api, ReportTargetType.USER, "u", backgroundScope)
        c.onDetails("x".repeat(Limits.REPORT_DETAILS_MAX + 50))
        assertEquals(Limits.REPORT_DETAILS_MAX, c.state.value.details.length)
    }

    @Test
    fun serverErrorsStayOnTheFormAndAllowRetry() = runTest {
        var fail = true
        val h = Harness {
            if (fail) respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "You can't report yourself")
            else respondJson(OkResponse.serializer(), OkResponse())
        }
        val c = ReportFormController(h.api, ReportTargetType.USER, "me", backgroundScope)
        c.onReason(ReportReason.SPAM)
        c.submit()
        val failed = c.state.awaitValue { it.formError != null }
        assertEquals("You can't report yourself", failed.formError)
        assertFalse(failed.submitted)

        fail = false
        c.submit()
        c.state.awaitValue { it.submitted }
        assertEquals(2, h.requests.size)
    }
}

class ReportDetailViewModelTest {

    private fun harness(report: Report, onResolve: (io.ktor.client.request.HttpRequestData) -> Unit = {}) = Harness { req ->
        when {
            req.url.encodedPath == ApiRoutes.modReport("rep1") -> respondJson(Report.serializer(), report)
            req.url.encodedPath == ApiRoutes.modReportResolve("rep1") -> {
                onResolve(req)
                respondJson(Report.serializer(), report.copy(status = ReportStatus.RESOLVED, resolutionAction = ModerationAction.SUSPEND_USER))
            }
            else -> respondJson(OkResponse.serializer(), OkResponse())
        }
    }

    @Test
    fun offersOnlyActionsThatApplyToTheReport() {
        assertEquals(
            listOf(ModerationAction.DISMISS, ModerationAction.REMOVE_LISTING, ModerationAction.WARN_USER, ModerationAction.SUSPEND_USER),
            resolveActionsFor(report(ReportTargetType.LISTING)),
        )
        assertEquals(
            listOf(ModerationAction.DISMISS, ModerationAction.WARN_USER, ModerationAction.SUSPEND_USER),
            resolveActionsFor(report(ReportTargetType.USER)),
        )
        assertEquals(emptyList(), resolveActionsFor(report(status = ReportStatus.RESOLVED)))
    }

    @Test
    fun resolveSendsActionNoteAndSuspendDays() = withMainDispatcher {
        runTest {
            var sent: io.ktor.client.request.HttpRequestData? = null
            val h = harness(report(ReportTargetType.USER)) { sent = it }
            val vm = ReportDetailViewModel(ReportDetailRoute("rep1"), h.api, UserMessages())
            vm.report.awaitValue { it is UiState.Content }

            vm.resolve(ModerationAction.SUSPEND_USER, "repeat offender", 30)
            vm.actionDone.flow.first()

            val body = sent!!.jsonBody()
            assertEquals(JsonPrimitive("SUSPEND_USER"), body["action"])
            assertEquals(JsonPrimitive("repeat offender"), body["note"])
            assertEquals(JsonPrimitive(30), body["suspendDays"])
            val state = vm.report.value
            assertTrue(state is UiState.Content && state.value.status == ReportStatus.RESOLVED)
        }
    }

    @Test
    fun indefiniteSuspensionOmitsDaysAndNonSuspendActionsNeverSendThem() = withMainDispatcher {
        runTest {
            val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val h = harness(report(ReportTargetType.USER)) { bodies += it.jsonBody() }
            val vm = ReportDetailViewModel(ReportDetailRoute("rep1"), h.api, UserMessages())
            vm.report.awaitValue { it is UiState.Content }

            vm.resolve(ModerationAction.SUSPEND_USER, "", null)
            vm.actionDone.flow.first()
            assertTrue(bodies.single()["suspendDays"] == null || bodies.single()["suspendDays"] == JsonNull)
        }
    }

    @Test
    fun dismissIgnoresStraySuspendDays() = withMainDispatcher {
        runTest {
            val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val h = harness(report(ReportTargetType.USER)) { bodies += it.jsonBody() }
            val vm = ReportDetailViewModel(ReportDetailRoute("rep1"), h.api, UserMessages())
            vm.report.awaitValue { it is UiState.Content }

            vm.resolve(ModerationAction.DISMISS, "fine", 7)
            vm.actionDone.flow.first()
            assertEquals(JsonPrimitive("DISMISS"), bodies.single()["action"])
            assertTrue(bodies.single()["suspendDays"] == null)
        }
    }

    @Test
    fun removingAListingFromAUserReportIsRejectedLocally() = withMainDispatcher {
        runTest {
            val h = harness(report(ReportTargetType.USER))
            val vm = ReportDetailViewModel(ReportDetailRoute("rep1"), h.api, UserMessages())
            vm.report.awaitValue { it is UiState.Content }

            vm.resolve(ModerationAction.REMOVE_LISTING, "")
            val s = vm.runner.state.awaitValue { it.error != null }
            assertFalse(s.submitting)
            assertTrue(h.requestsTo(ApiRoutes.modReportResolve("rep1")).isEmpty())
        }
    }

    @Test
    fun serverRefusalIsShownAndTheReportStaysOpen() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                if (req.url.encodedPath == ApiRoutes.modReport("rep1")) respondJson(Report.serializer(), report(ReportTargetType.USER))
                else respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "Moderators can't suspend moderators")
            }
            val vm = ReportDetailViewModel(ReportDetailRoute("rep1"), h.api, UserMessages())
            vm.report.awaitValue { it is UiState.Content }

            vm.resolve(ModerationAction.SUSPEND_USER, "x", 7)
            val s = vm.runner.state.awaitValue { it.error != null }
            assertEquals("Moderators can't suspend moderators", s.error)
            val state = vm.report.value
            assertTrue(state is UiState.Content && state.value.status == ReportStatus.OPEN)
        }
    }

    @Test
    fun directSuspendNeedsTheReasonAndHitsTheDirectEndpoint() = withMainDispatcher {
        runTest {
            val h = harness(report(ReportTargetType.USER, status = ReportStatus.RESOLVED))
            val vm = ReportDetailViewModel(ReportDetailRoute("rep1"), h.api, UserMessages())
            vm.report.awaitValue { it is UiState.Content }

            vm.suspendUser("s1", "scam ring", null)
            vm.actionDone.flow.first()
            val req = h.requestsTo(ApiRoutes.modUserSuspend("s1")).single()
            assertEquals(JsonPrimitive("scam ring"), req.jsonBody()["reason"])
        }
    }

    @Test
    fun queueFiltersByStatus() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                respondJson(
                    Page.serializer(Report.serializer()),
                    Page(listOf(report(status = ReportStatus.valueOf(req.url.parameters["status"]!!)))),
                )
            }
            val vm = ModerationQueueViewModel(h.api, UserMessages())
            vm.reports.refresh()
            vm.reports.state.awaitValue { it.loaded }
            assertEquals("OPEN", h.requests.first().url.parameters["status"])

            vm.select(ReportStatus.DISMISSED)
            vm.reports.state.awaitValue { it.loaded && it.items.singleOrNull()?.status == ReportStatus.DISMISSED }
            assertEquals("DISMISSED", h.requests.last().url.parameters["status"])
        }
    }

    @Test
    fun queueReloadsWhenComingBackFromAReport() = withMainDispatcher {
        runTest {
            var open = listOf(report())
            val h = Harness { respondJson(Page.serializer(Report.serializer()), Page(open)) }
            val vm = ModerationQueueViewModel(h.api, UserMessages())
            vm.reports.refresh()
            vm.reports.state.awaitValue { it.loaded }
            vm.onResume() // first resume: the initial load is enough
            assertEquals(1, h.requests.size)

            open = emptyList() // resolved on the detail screen
            vm.onResume() // back on the queue
            vm.reports.state.awaitValue { it.loaded && it.items.isEmpty() }
            assertEquals(2, h.requests.size)
        }
    }
}
