package com.campusmarket.app.feature.moderation

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.feature.awaitMatch
import com.campusmarket.app.feature.withMainDispatcher
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DisputeCase
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.Report
import com.campusmarket.core.ReportReason
import com.campusmarket.core.ReportStatus
import com.campusmarket.core.ReportTargetType
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val who = UserPublic("r1", "Rita", campusDomain = "uni.edu", joinedAt = 1L)

private fun report(id: String, type: ReportTargetType) = Report(
    id = id, reporter = who, targetType = type, targetId = "t-$id", reason = ReportReason.SPAM, status = ReportStatus.OPEN,
    targetSummary = id, createdAt = 1L,
)

class QueueKindFilterTest {

    @Test
    fun filteringByKindSkipsPagesWithoutAMatch() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                val page = when (req.url.parameters["cursor"]) {
                    null -> Page(listOf(report("a", ReportTargetType.LISTING), report("b", ReportTargetType.LISTING)), nextCursor = "c2")
                    else -> Page(listOf(report("c", ReportTargetType.MESSAGE)), nextCursor = null)
                }
                respondJson(Page.serializer(Report.serializer()), page)
            }
            val vm = ModerationQueueViewModel(h.api, UserMessages())
            vm.selectType(ReportTargetType.MESSAGE)
            val s = vm.reports.state.awaitMatch { it.loaded }
            assertEquals(listOf("c"), s.items.map { it.id })
            assertTrue(s.endReached)
            assertEquals(2, h.requests.size)

            vm.selectType(null)
            val all = vm.reports.state.awaitMatch { it.loaded && it.items.size == 2 }
            assertEquals(listOf("a", "b"), all.items.map { it.id })
        }
    }
}

class ReportedTargetsTest {

    @Test
    fun aSecondSheetForTheSameTargetOpensAsAlreadyReported() = runTest {
        val h = Harness { respondJson(OkResponse.serializer(), OkResponse()) }
        val reported = ReportedTargets()
        val first = ReportFormController(h.api, ReportTargetType.LISTING, "l1", backgroundScope, reported)
        assertFalse(first.state.value.alreadyReported)
        first.onReason(ReportReason.SCAM)
        first.submit()
        first.state.awaitMatch { it.submitted }

        assertTrue(ReportFormController(h.api, ReportTargetType.LISTING, "l1", backgroundScope, reported).state.value.alreadyReported)
        assertFalse(ReportFormController(h.api, ReportTargetType.USER, "l1", backgroundScope, reported).state.value.alreadyReported)
        assertEquals(1, h.requestsTo(ApiRoutes.REPORTS).size)
    }

    @Test
    fun everyReasonHasAHint() {
        ReportReason.entries.forEach { assertTrue(it.hint.isNotBlank()) }
    }
}

class StaffSearchAsYouTypeTest {

    @Test
    fun typingSearchesWithoutPressingEnter() = withMainDispatcher {
        runTest {
            val found = StaffMember(UserPublic("u1", "Uma", campusDomain = "uni.edu", joinedAt = 1L), UserRole.USER, "uma@uni.edu")
            val h = Harness { req ->
                when (req.url.encodedPath) {
                    ApiRoutes.MOD_USER_SEARCH -> respondJson(ListSerializer(StaffMember.serializer()), listOf(found))
                    else -> respondJson(ListSerializer(StaffMember.serializer()), emptyList())
                }
            }
            val vm = StaffViewModel(h.api, UserMessages())
            vm.onQuery("um")
            val r = vm.search.awaitMatch { it is StaffSearch.Results } as StaffSearch.Results
            assertEquals(listOf(found), r.members)
            assertEquals("um", h.requestsTo(ApiRoutes.MOD_USER_SEARCH).single().url.parameters["q"])

            vm.onQuery("u") // too short: back to the staff list, nothing sent
            assertEquals(StaffSearch.Idle, vm.search.value)
            assertEquals(1, h.requestsTo(ApiRoutes.MOD_USER_SEARCH).size)
        }
    }
}

class DisputeQueueResumeTest {

    @Test
    fun comingBackFromADisputeReloadsTheQueue() = withMainDispatcher {
        runTest {
            val h = Harness { respondJson(Page.serializer(DisputeCase.serializer()), Page(emptyList())) }
            val vm = DisputesViewModel(h.api, UserMessages())
            vm.disputes.refresh()
            vm.disputes.state.awaitMatch { it.loaded }
            vm.onResume()
            assertEquals(1, h.requests.size)
            vm.onResume()
            vm.disputes.state.awaitMatch { it.loaded && !it.isRefreshing }
            assertEquals(2, h.requests.size)
        }
    }
}
