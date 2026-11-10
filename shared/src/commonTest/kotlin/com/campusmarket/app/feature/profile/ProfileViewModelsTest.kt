package com.campusmarket.app.feature.profile

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.SessionState
import com.campusmarket.app.data.awaitCondition
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.data.jsonBody
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.data.withMainDispatcher
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Photo
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive

class EditProfileViewModelTest {

    private fun edit(h: Harness, scope: kotlinx.coroutines.CoroutineScope): Pair<EditProfileViewModel, SessionManager> {
        val session = SessionManager(h.api, h.tokens, scope)
        return EditProfileViewModel(h.api, session, UserMessages()) to session
    }

    @Test
    fun rejectsInvalidNameAndLongBioWithoutCallingTheServer() = withMainDispatcher {
        runTest {
            val h = Harness { error("no request expected") }
            val (vm, _) = edit(h, backgroundScope)

            vm.onDisplayName("A")
            vm.onBio("x".repeat(Limits.BIO_MAX + 1))
            vm.save()

            val errors = vm.state.value.fieldErrors
            assertTrue("displayName" in errors)
            assertTrue("bio" in errors)
            assertTrue(h.requests.isEmpty())
            assertFalse(vm.state.value.saving)
        }
    }

    @Test
    fun savesOnlyChangedFieldsAndUpdatesTheSession() = withMainDispatcher {
        runTest {
            val updated = testUser.copy(displayName = "Ada L", bio = "Hello")
            val h = Harness { req ->
                assertEquals(ApiRoutes.ME, req.url.encodedPath)
                assertEquals(HttpMethod.Patch, req.method)
                respondJson(UserPrivate.serializer(), updated)
            }
            val (vm, session) = edit(h, backgroundScope)
            assertFalse(vm.state.value.dirty)

            vm.onDisplayName("  Ada L ")
            vm.onBio("Hello")
            assertTrue(vm.state.value.dirty)
            vm.save()
            vm.state.awaitValue { !it.saving && h.requests.isNotEmpty() }
            vm.saved.flow.first()

            val body = h.requests.single().jsonBody()
            assertEquals(JsonPrimitive("Ada L"), body["displayName"])
            assertEquals(JsonPrimitive("Hello"), body["bio"])
            assertFalse("avatarPhotoId" in body)
            assertEquals("Ada L", session.currentUser?.displayName)
        }
    }

    @Test
    fun mapsServerFieldErrorsOntoTheForm() = withMainDispatcher {
        runTest {
            val h = Harness {
                respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "Invalid", mapOf("displayName" to "That name isn't allowed"))
            }
            val (vm, _) = edit(h, backgroundScope)
            vm.onDisplayName("Valid name")
            vm.save()
            val s = vm.state.awaitValue { it.fieldErrors.isNotEmpty() }
            assertEquals("That name isn't allowed", s.fieldErrors["displayName"])
            assertNull(s.formError)
        }
    }

    @Test
    fun uploadsAvatarThenAppliesItOnSave() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                when (req.url.encodedPath) {
                    ApiRoutes.PHOTOS -> respondJson(Photo.serializer(), Photo("p1", "http://x/uploads/p1.jpg"), HttpStatusCode.Created)
                    else -> respondJson(UserPrivate.serializer(), testUser.copy(avatarUrl = "http://x/uploads/p1.jpg"))
                }
            }
            val (vm, session) = edit(h, backgroundScope)

            vm.onAvatarPicked(listOf(PickedImage(byteArrayOf(1, 2, 3), "me.jpg", "image/jpeg")))
            val afterUpload = vm.state.awaitValue { it.pendingAvatarPhotoId == "p1" && !it.uploadingAvatar }
            assertEquals("http://x/uploads/p1.jpg", afterUpload.avatarUrl)

            vm.save()
            vm.saved.flow.first()
            assertEquals(JsonPrimitive("p1"), h.requestsTo(ApiRoutes.ME).single().jsonBody()["avatarPhotoId"])
            assertEquals("http://x/uploads/p1.jpg", session.currentUser?.avatarUrl)
        }
    }

    @Test
    fun removingTheAvatarSendsAnEmptyId() = withMainDispatcher {
        runTest {
            val h = Harness { respondJson(UserPrivate.serializer(), testUser) }
            val session = SessionManager(h.api, h.tokens, backgroundScope)
            val vm = EditProfileViewModel(h.api, session, UserMessages())
            vm.removeAvatar()
            assertNull(vm.state.value.avatarUrl)
            vm.save()
            vm.saved.flow.first()
            assertEquals(JsonPrimitive(""), h.requests.single().jsonBody()["avatarPhotoId"])
        }
    }

    @Test
    fun savingWithoutChangesDoesNotCallTheServer() = withMainDispatcher {
        runTest {
            val h = Harness { error("no request expected") }
            val (vm, _) = edit(h, backgroundScope)
            vm.save()
            vm.saved.flow.first()
            assertTrue(h.requests.isEmpty())
        }
    }
}

class SettingsViewModelTest {

    private fun settings(h: Harness, scope: kotlinx.coroutines.CoroutineScope): Pair<SettingsViewModel, SessionManager> {
        val session = SessionManager(h.api, h.tokens, scope)
        return SettingsViewModel(session, UserMessages()) to session
    }

    @Test
    fun deletionNeedsTwoStepsAndTheTypedKeyword() = withMainDispatcher {
        runTest {
            val h = Harness { error("no request expected") }
            val (vm, _) = settings(h, backgroundScope)

            vm.confirmDelete() // not even started
            vm.startDelete()
            assertEquals(DeleteStep.Warn, vm.state.value.deleteStep)
            vm.confirmDelete() // still on the warning step
            vm.continueDelete()
            assertEquals(DeleteStep.Confirm, vm.state.value.deleteStep)
            vm.onDeleteText("delet")
            vm.confirmDelete()
            assertTrue(h.requests.isEmpty())

            vm.onDeleteText("delete")
            assertTrue(vm.state.value.deleteConfirmed)
        }
    }

    @Test
    fun successfulDeletionEndsTheSession() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                assertEquals(HttpMethod.Delete, req.method)
                respondJson(OkResponse.serializer(), OkResponse())
            }
            val (vm, session) = settings(h, backgroundScope)
            vm.startDelete(); vm.continueDelete(); vm.onDeleteText("DELETE")
            vm.confirmDelete()

            vm.state.awaitValue { it.deleteStep == DeleteStep.None && !it.deleting && h.requests.isNotEmpty() }
            assertEquals(SessionState.SignedOut, session.state.value)
            assertNull(h.tokens.current())
        }
    }

    @Test
    fun openOrdersConflictIsSurfacedAndKeepsTheSession() = withMainDispatcher {
        runTest {
            val h = Harness {
                respondError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "You have orders in progress")
            }
            val (vm, session) = settings(h, backgroundScope)
            vm.startDelete(); vm.continueDelete(); vm.onDeleteText("DELETE")
            vm.confirmDelete()

            val s = vm.state.awaitValue { it.deleteStep == DeleteStep.BlockedByOrders && !it.deleting }
            assertNull(s.deleteError)
            assertIs<SessionState.SignedIn>(session.state.value)
        }
    }

    @Test
    fun otherDeletionErrorsStayInTheDialog() = withMainDispatcher {
        runTest {
            val h = Harness { respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "Server exploded") }
            val (vm, session) = settings(h, backgroundScope)
            vm.startDelete(); vm.continueDelete(); vm.onDeleteText("DELETE")
            vm.confirmDelete()

            val s = vm.state.awaitValue { it.deleteError != null }
            assertEquals(DeleteStep.Confirm, s.deleteStep)
            assertEquals("Server exploded", s.deleteError)
            assertIs<SessionState.SignedIn>(session.state.value)
        }
    }
}

class BlockedUsersAndBlockTest {
    private val someone = UserPublic(id = "u2", displayName = "Liam", campusDomain = "uni.edu", joinedAt = 1L, blockedByMe = true)

    @Test
    fun unblockRemovesTheRowAfterTheServerAgrees() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                when {
                    req.method == HttpMethod.Get -> respondJson(ListSerializer(UserPublic.serializer()), listOf(someone))
                    else -> respondJson(OkResponse.serializer(), OkResponse())
                }
            }
            val vm = BlockedUsersViewModel(h.api, UserMessages())
            val loaded = vm.state.awaitValue { it is UiState.Content }
            assertEquals(listOf(someone), (loaded as UiState.Content).value)

            vm.unblock(someone)
            val after = vm.state.awaitValue { it is UiState.Content && it.value.isEmpty() }
            assertIs<UiState.Content<List<UserPublic>>>(after)
            val call = h.requestsTo(ApiRoutes.userBlock("u2")).single()
            assertEquals(HttpMethod.Delete, call.method)
        }
    }

    @Test
    fun listRefreshesWhenComingBackFromAProfile() = withMainDispatcher {
        runTest {
            var blocks = listOf(someone)
            val h = Harness { respondJson(ListSerializer(UserPublic.serializer()), blocks) }
            val vm = BlockedUsersViewModel(h.api, UserMessages())
            vm.state.awaitValue { it is UiState.Content }
            vm.onResume() // first resume: nothing to do
            assertEquals(1, h.requests.size)

            blocks = emptyList() // unblocked on their profile
            vm.onResume() // back on the list
            vm.state.awaitValue { it is UiState.Content && it.value.isEmpty() }
            assertEquals(2, h.requests.size)
        }
    }

    @Test
    fun blockControllerPostsAndDeletesAndReportsErrors() = runTest {
        var fail = false
        val h = Harness {
            if (fail) respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "You can't block yourself")
            else respondJson(OkResponse.serializer(), OkResponse())
        }
        val controller = BlockController(h.api, backgroundScope)

        var done = 0
        controller.submit("u2", block = true) { done++ }
        awaitCondition { done == 1 && !controller.state.value.loading }
        assertEquals(HttpMethod.Post, h.requests.last().method)

        controller.submit("u2", block = false) { done++ }
        awaitCondition { done == 2 && !controller.state.value.loading }
        assertEquals(HttpMethod.Delete, h.requests.last().method)

        fail = true
        controller.submit("u2", block = true) { done++ }
        val failed = controller.state.awaitValue { it.error != null }
        assertEquals("You can't block yourself", failed.error)
        assertEquals(2, done)
    }

    @Test
    fun deletedUsersAreRecognised() {
        assertTrue(someone.copy(displayName = DELETED_USER_NAME).isDeleted)
        assertFalse(someone.isDeleted)
    }
}
