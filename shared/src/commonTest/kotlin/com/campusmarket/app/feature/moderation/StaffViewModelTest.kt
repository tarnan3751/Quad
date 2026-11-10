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
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ModerationAction
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserPublic
import com.campusmarket.core.UserRole
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun member(id: String, name: String, role: UserRole) = StaffMember(
    UserPublic(id, name, campusDomain = "uni.edu", joinedAt = 1L), role, "$name@uni.edu".lowercase(),
)

private val admin = member("a1", "Ada", UserRole.ADMIN)
private val mod = member("m1", "Max", UserRole.MODERATOR)
private val student = member("u1", "Uma", UserRole.USER)

class StaffRulesTest {
    @Test
    fun toggleTargetsFollowTheServerRules() {
        assertEquals(UserRole.MODERATOR, roleToggleTarget(student))
        assertEquals(UserRole.USER, roleToggleTarget(mod))
        assertNull(roleToggleTarget(admin))
    }

    @Test
    fun cannotChangeYourselfOrAnAdmin() {
        assertTrue(canChangeRole(student, currentUserId = "a1"))
        assertTrue(canChangeRole(mod, currentUserId = "a1"))
        assertFalse(canChangeRole(admin, currentUserId = "someone"))
        assertFalse(canChangeRole(mod, currentUserId = "m1"))
    }

    @Test
    fun changeRoleActionHasALabel() {
        assertEquals("Change role", ModerationAction.CHANGE_ROLE.label)
    }
}

class StaffViewModelTest {
    private fun harness(
        staff: () -> List<StaffMember> = { listOf(admin, mod) },
        onRole: (HttpRequestData) -> Unit = {},
        roleFails: Boolean = false,
    ) = Harness { req ->
        val path = req.url.encodedPath
        when {
            path == ApiRoutes.MOD_STAFF -> respondJson(ListSerializer(StaffMember.serializer()), staff())
            path == ApiRoutes.MOD_USER_SEARCH -> respondJson(
                ListSerializer(StaffMember.serializer()),
                listOf(student, mod).filter { req.url.parameters["q"]!!.lowercase() in it.user.displayName.lowercase() },
            )
            path == ApiRoutes.modUserRole("u1") || path == ApiRoutes.modUserRole("m1") -> {
                onRole(req)
                if (roleFails) respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "An admin's role can't be changed")
                else respondJson(StaffMember.serializer(), student.copy(role = UserRole.MODERATOR))
            }
            else -> error("unexpected $path")
        }
    }

    @Test
    fun loadsTheStaffList() = withMainDispatcher {
        runTest {
            val h = harness()
            val vm = StaffViewModel(h.api, UserMessages())
            val s = vm.staff.awaitValue { it is UiState.Content } as UiState.Content
            assertEquals(listOf("a1", "m1"), s.value.map { it.user.id })
            assertEquals(HttpMethod.Get, h.requestsTo(ApiRoutes.MOD_STAFF).single().method)
        }
    }

    @Test
    fun shortQueriesNeverHitTheServer() = withMainDispatcher {
        runTest {
            val h = harness()
            val vm = StaffViewModel(h.api, UserMessages())
            vm.staff.awaitValue { it is UiState.Content }
            vm.onQuery("u")
            vm.runSearch()
            assertEquals(StaffSearch.Idle, vm.search.value)
            assertTrue(h.requestsTo(ApiRoutes.MOD_USER_SEARCH).isEmpty())
        }
    }

    @Test
    fun searchSendsTheTrimmedQueryAndClearReturnsToTheList() = withMainDispatcher {
        runTest {
            val h = harness()
            val vm = StaffViewModel(h.api, UserMessages())
            vm.onQuery("  uma ")
            vm.runSearch()
            val r = vm.search.awaitValue { it is StaffSearch.Results } as StaffSearch.Results
            assertEquals("uma", r.query)
            assertEquals(listOf("u1"), r.members.map { it.user.id })
            assertEquals("uma", h.requestsTo(ApiRoutes.MOD_USER_SEARCH).single().url.parameters["q"])

            vm.clearSearch()
            assertEquals(StaffSearch.Idle, vm.search.value)
            assertEquals("", vm.query.value)
        }
    }

    @Test
    fun promoteSendsPutWithTheRoleThenReloadsStaff() = withMainDispatcher {
        runTest {
            var promoted = false
            val h = harness(staff = { if (promoted) listOf(admin, mod, student.copy(role = UserRole.MODERATOR)) else listOf(admin, mod) },
                onRole = { promoted = true })
            val vm = StaffViewModel(h.api, UserMessages())
            vm.staff.awaitValue { it is UiState.Content }

            vm.setRole(student, UserRole.MODERATOR)
            vm.actionDone.flow.first()

            val req = h.requestsTo(ApiRoutes.modUserRole("u1")).single()
            assertEquals(HttpMethod.Put, req.method)
            assertEquals(JsonPrimitive("MODERATOR"), req.jsonBody()["role"])
            val s = vm.staff.awaitValue { it is UiState.Content && it.value.size == 3 } as UiState.Content
            assertEquals(UserRole.MODERATOR, s.value.last().role)
        }
    }

    @Test
    fun demoteSendsUserRoleAndRefreshesSearchResults() = withMainDispatcher {
        runTest {
            val h = harness()
            val vm = StaffViewModel(h.api, UserMessages())
            vm.onQuery("max")
            vm.runSearch()
            vm.search.awaitValue { it is StaffSearch.Results }

            vm.setRole(mod, UserRole.USER)
            vm.actionDone.flow.first()
            assertEquals(JsonPrimitive("USER"), h.requestsTo(ApiRoutes.modUserRole("m1")).single().jsonBody()["role"])
            // the visible search is re-run so the row reflects the new role
            vm.search.awaitValue { it is StaffSearch.Results }
            assertTrue(h.requestsTo(ApiRoutes.MOD_USER_SEARCH).size >= 2)
        }
    }

    @Test
    fun serverRefusalIsSurfacedAndNothingReloads() = withMainDispatcher {
        runTest {
            val h = harness(roleFails = true)
            val vm = StaffViewModel(h.api, UserMessages())
            vm.staff.awaitValue { it is UiState.Content }
            vm.setRole(mod, UserRole.USER)
            val s = vm.runner.state.awaitValue { it.error != null }
            assertEquals("An admin's role can't be changed", s.error)
            assertEquals(1, h.requestsTo(ApiRoutes.MOD_STAFF).size)
        }
    }

    @Test
    fun searchFailureIsShown() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                if (req.url.encodedPath == ApiRoutes.MOD_STAFF) respondJson(ListSerializer(StaffMember.serializer()), emptyList())
                else respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "Admin access required")
            }
            val vm = StaffViewModel(h.api, UserMessages())
            vm.onQuery("uma")
            vm.runSearch()
            val f = vm.search.awaitValue { it is StaffSearch.Failed } as StaffSearch.Failed
            assertEquals("Admin access required", f.message)
        }
    }
}
