package com.campusmarket.app.feature.auth

import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.data.bearer
import com.campusmarket.app.data.jsonBody
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.data.withMainDispatcher
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.OkResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SUSPENDED = "Your account is suspended: Violation of community guidelines"

/** Suspended accounts: the notice, and deleting the account with email + password (no session). */
class SuspendedAccountTest {

    @Test
    fun deletionAsksForThePasswordAgainThenConfirmsTwice() = withMainDispatcher {
        runTest {
            val h = Harness(initial = null) { respondJson(OkResponse.serializer(), OkResponse()) }
            val vm = DeleteSuspendedAccountViewModel(h.api, SessionManager(h.api, h.tokens, backgroundScope))
            vm.prepare("ada@uni.edu")
            assertEquals(DeleteAccountStep.Info, vm.state.value.step)
            assertEquals("ada@uni.edu", vm.state.value.email, "prefilled")

            vm.startDeletion()
            assertEquals(DeleteAccountStep.Credentials, vm.state.value.step)
            vm.continueToConfirm()
            assertEquals("Enter your password", vm.state.value.fieldErrors["password"], "the password is asked for again")
            assertEquals(DeleteAccountStep.Credentials, vm.state.value.step)

            vm.onPassword("hunter2hunter2")
            vm.continueToConfirm()
            assertEquals(DeleteAccountStep.Confirm, vm.state.value.step)
            assertTrue(h.requests.isEmpty(), "nothing sent before the final confirmation")

            vm.delete()
            vm.state.awaitValue { it.step == DeleteAccountStep.Done }
            val req = h.requestsTo(ApiRoutes.DELETE_ACCOUNT).single()
            assertNull(req.bearer, "no session is used (a suspended user has none)")
            assertEquals(JsonPrimitive("ada@uni.edu"), req.jsonBody()["email"])
            assertEquals(JsonPrimitive("hunter2hunter2"), req.jsonBody()["password"])
            assertEquals("", vm.state.value.password, "not kept around")
        }
    }

    @Test
    fun wrongCredentialsGoBackToThePasswordStep() = withMainDispatcher {
        runTest {
            val h = Harness(initial = null) { respondError(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_CREDENTIALS, "Incorrect email or password") }
            val vm = DeleteSuspendedAccountViewModel(h.api, SessionManager(h.api, h.tokens, backgroundScope))
            vm.prepare(null)
            vm.startDeletion()
            vm.onEmail("not-an-email")
            vm.onPassword("x")
            vm.continueToConfirm()
            assertTrue(vm.state.value.fieldErrors["email"] != null)
            vm.onEmail("ada@uni.edu")
            vm.continueToConfirm()
            vm.delete()
            val s = vm.state.awaitValue { it.step == DeleteAccountStep.Credentials && !it.submitting }
            assertEquals("Incorrect email or password", s.fieldErrors["password"])
        }
    }

    @Test
    fun ordersInProgressAreExplained() = withMainDispatcher {
        runTest {
            val h = Harness(initial = null) {
                respondError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "You have orders in progress. Complete or cancel them before deleting your account.")
            }
            val vm = DeleteSuspendedAccountViewModel(h.api, SessionManager(h.api, h.tokens, backgroundScope))
            vm.prepare("ada@uni.edu")
            vm.startDeletion()
            vm.onPassword("pw")
            vm.continueToConfirm()
            vm.delete()
            val s = vm.state.awaitValue { it.formError != null && !it.submitting }
            assertEquals(DeleteAccountStep.Confirm, s.step)
            assertTrue(s.formError!!.startsWith("You have orders in progress.") && "released to the seller" in s.formError!!)
            vm.back()
            assertEquals(DeleteAccountStep.Credentials, vm.state.value.step)
            vm.back()
            assertEquals(DeleteAccountStep.Info, vm.state.value.step)
        }
    }

    @Test
    fun aSessionEndedBySuspensionRemembersTheEmailUntilTheNoticeIsDone() = withMainDispatcher {
        runTest {
            var deleted = false
            val h = Harness { req ->
                when (req.url.encodedPath) {
                    ApiRoutes.DELETE_ACCOUNT -> { deleted = true; respondJson(OkResponse.serializer(), OkResponse()) }
                    else -> respondError(HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED, SUSPENDED)
                }
            }
            assertFailsWith<ApiError> { h.api.me() }
            assertEquals(ErrorCodes.ACCOUNT_SUSPENDED, h.api.sessionEndReason.value?.code)
            assertEquals(testUser.email, h.api.sessionEndEmail)

            val vm = DeleteSuspendedAccountViewModel(h.api, SessionManager(h.api, h.tokens, backgroundScope))
            vm.prepare(h.api.sessionEndEmail)
            vm.startDeletion()
            vm.onPassword("pw")
            vm.continueToConfirm()
            vm.delete()
            vm.state.awaitValue { it.step == DeleteAccountStep.Done }
            assertTrue(deleted)
            // the Welcome notice stays until the user closes the "deleted" message
            assertEquals(ErrorCodes.ACCOUNT_SUSPENDED, h.api.sessionEndReason.value?.code)
            vm.finish()
            assertNull(h.api.sessionEndReason.value)
            assertNull(h.api.sessionEndEmail)
        }
    }

    @Test
    fun signingInToASuspendedAccountOffersDeletion() = withMainDispatcher {
        runTest {
            val h = Harness(initial = null) { respondError(HttpStatusCode.Forbidden, ErrorCodes.ACCOUNT_SUSPENDED, SUSPENDED) }
            val session = SessionManager(h.api, h.tokens, backgroundScope)
            val vm = LoginViewModel(LoginRoute(), session, PendingVerification())
            vm.onEmail("ada@uni.edu")
            vm.onPassword("password1")
            vm.submit()
            val s = vm.state.awaitValue { it.suspended != null && !it.submitting }
            assertEquals(SUSPENDED, s.suspended)
            assertEquals(SUSPENDED, s.formError)
            assertTrue(s.showSuspended)

            vm.dismissSuspended(deleted = false)
            assertTrue(!vm.state.value.showSuspended && vm.state.value.suspended != null, "can be reopened")
            vm.showSuspendedDialog()
            assertTrue(vm.state.value.showSuspended)
            vm.dismissSuspended(deleted = true)
            assertNull(vm.state.value.suspended)
            assertNull(vm.state.value.formError)
            assertEquals("", vm.state.value.password)
        }
    }
}
