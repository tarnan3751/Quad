package com.campusmarket.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.ui.Validate
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.Paginator
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.state.updateContent
import com.campusmarket.app.ui.toFormErrors
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Rating
import com.campusmarket.core.UpdateProfileRequest
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The server renders soft-deleted accounts with this name (`Readers.kt`). */
const val DELETED_USER_NAME = "Deleted user"

val UserPublic.isDeleted: Boolean get() = displayName == DELETED_USER_NAME

// ---------------------------------------------------------------------------------------------------- profile tab

/** Keeps the cached profile (rating, payouts flag, avatar) fresh when the tab is shown again. */
class ProfileViewModel(private val session: SessionManager) : ViewModel() {
    fun refresh() {
        viewModelScope.launch {
            try {
                session.refreshUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // keep showing the cached user
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- edit profile

data class EditProfileState(
    val displayName: String = "",
    val bio: String = "",
    /** Avatar currently shown (the saved one, a freshly uploaded one, or null after removal). */
    val avatarUrl: String? = null,
    /** null = unchanged, "" = remove, otherwise the id of an uploaded photo to apply on save. */
    val pendingAvatarPhotoId: String? = null,
    val uploadingAvatar: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val saving: Boolean = false,
    val originalName: String = "",
    val originalBio: String = "",
) {
    val hasAvatar: Boolean get() = avatarUrl != null
    val dirty: Boolean
        get() = displayName.trim() != originalName || bio.trim() != originalBio || pendingAvatarPhotoId != null
    val busy: Boolean get() = saving || uploadingAvatar
}

class EditProfileViewModel(
    private val api: ApiClient,
    private val session: SessionManager,
    private val messages: UserMessages,
) : ViewModel() {
    private val _state = MutableStateFlow(
        session.currentUser.let { u ->
            EditProfileState(
                displayName = u?.displayName.orEmpty(),
                bio = u?.bio.orEmpty(),
                avatarUrl = u?.avatarUrl,
                originalName = u?.displayName.orEmpty().trim(),
                originalBio = u?.bio.orEmpty().trim(),
            )
        },
    )
    val state: StateFlow<EditProfileState> = _state.asStateFlow()

    /** Fired after a successful save (or when there was nothing to save). */
    val saved = EventQueue<Unit>()

    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v, fieldErrors = it.fieldErrors - "displayName", formError = null) }
    fun onBio(v: String) = _state.update { it.copy(bio = v, fieldErrors = it.fieldErrors - "bio", formError = null) }

    /** Uploads the picked photo right away; it becomes the avatar when the form is saved. */
    fun onAvatarPicked(images: List<PickedImage>) {
        val image = images.firstOrNull() ?: return
        if (_state.value.busy) return
        _state.update { it.copy(uploadingAvatar = true, formError = null) }
        viewModelScope.launch {
            try {
                val photo = api.uploadPhoto(image.bytes, image.fileName, image.mimeType)
                _state.update { it.copy(avatarUrl = photo.url, pendingAvatarPhotoId = photo.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.update { it.copy(formError = e.fields["file"] ?: e.message) }
            } finally {
                _state.update { it.copy(uploadingAvatar = false) }
            }
        }
    }

    fun onAvatarPickFailed() = _state.update { it.copy(formError = "We couldn't read that image. Try a different photo.") }

    fun removeAvatar() = _state.update { it.copy(avatarUrl = null, pendingAvatarPhotoId = "", formError = null) }

    fun save() {
        val s = _state.value
        if (s.busy) return
        val errors = fieldErrorsOf("displayName" to Validate.displayName(s.displayName), "bio" to Validate.bio(s.bio.trim()))
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        if (!s.dirty) {
            saved.send(Unit)
            return
        }
        val name = s.displayName.trim()
        val bio = s.bio.trim()
        val request = UpdateProfileRequest(
            displayName = name.takeIf { it != s.originalName },
            bio = bio.takeIf { it != s.originalBio },
            avatarPhotoId = s.pendingAvatarPhotoId,
        )
        _state.update { it.copy(saving = true, formError = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val updated = api.updateMe(request)
                session.updateUser(updated)
                messages.show("Profile updated")
                saved.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val (fields, form) = e.toFormErrors()
                val avatarError = fields["avatarPhotoId"]
                _state.update {
                    it.copy(fieldErrors = fields - "avatarPhotoId", formError = form ?: avatarError)
                }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- public profile

class UserProfileViewModel(
    route: UserProfileRoute,
    private val api: ApiClient,
    private val session: SessionManager,
    private val messages: UserMessages,
) : ViewModel() {
    val userId: String = route.userId
    val isMe: Boolean get() = session.currentUser?.id == userId

    private val _user = MutableStateFlow<UiState<UserPublic>>(UiState.Loading)
    val user: StateFlow<UiState<UserPublic>> = _user.asStateFlow()

    val listings = Paginator(viewModelScope, key = { it.id }) { cursor, limit -> api.userListings(userId, cursor, limit) }
    val ratings = Paginator<Rating>(viewModelScope, key = { it.id }) { cursor, limit -> api.userRatings(userId, cursor, limit) }

    init {
        load(_user) { api.user(userId) }
    }

    fun reload() {
        load(_user) { api.user(userId) }
        listings.reset()
        ratings.reset()
    }

    /** Called after [BlockUserDialog] succeeded. */
    fun onBlockChanged(blocked: Boolean) {
        _user.updateContent { it.copy(blockedByMe = blocked) }
    }

    fun onReported() = messages.show("Thanks. Our moderators will take a look.")
}

// ---------------------------------------------------------------------------------------------------- blocked users

class BlockedUsersViewModel(private val api: ApiClient, private val messages: UserMessages) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<UserPublic>>>(UiState.Loading)
    val state: StateFlow<UiState<List<UserPublic>>> = _state.asStateFlow()
    private val inFlight = mutableSetOf<String>()
    private val _unblocking = MutableStateFlow<Set<String>>(emptySet())
    val unblocking: StateFlow<Set<String>> = _unblocking.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        load(_state) { api.myBlocks() }
    }

    private var resumedOnce = false

    /** Back from a profile where the user may have been unblocked (or blocked): refresh quietly. */
    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        load(_state, keepContent = true) { api.myBlocks() }
    }

    fun unblock(user: UserPublic) {
        if (!inFlight.add(user.id)) return
        _unblocking.value = inFlight.toSet()
        viewModelScope.launch {
            try {
                api.unblockUser(user.id)
                _state.updateContent { list -> list.filterNot { it.id == user.id } }
                messages.show("Unblocked ${user.displayName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                messages.showError(e)
            } finally {
                inFlight.remove(user.id)
                _unblocking.value = inFlight.toSet()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- settings

enum class DeleteStep { None, Warn, Confirm, BlockedByOrders }

data class SettingsState(
    val confirmSignOut: Boolean = false,
    val deleteStep: DeleteStep = DeleteStep.None,
    val deleteText: String = "",
    val deleting: Boolean = false,
    val deleteError: String? = null,
) {
    val deleteConfirmed: Boolean get() = deleteText.trim().equals(DELETE_KEYWORD, ignoreCase = true)

    companion object {
        const val DELETE_KEYWORD = "DELETE"
    }
}

class SettingsViewModel(private val session: SessionManager, private val messages: UserMessages) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun askSignOut() = _state.update { it.copy(confirmSignOut = true) }
    fun cancelSignOut() = _state.update { it.copy(confirmSignOut = false) }

    fun signOut() {
        _state.update { it.copy(confirmSignOut = false) }
        viewModelScope.launch { session.signOut() }
    }

    fun startDelete() = _state.update { it.copy(deleteStep = DeleteStep.Warn, deleteText = "", deleteError = null) }
    fun continueDelete() = _state.update { it.copy(deleteStep = DeleteStep.Confirm, deleteText = "", deleteError = null) }
    fun cancelDelete() {
        if (!_state.value.deleting) _state.update { it.copy(deleteStep = DeleteStep.None, deleteText = "", deleteError = null) }
    }

    fun onDeleteText(v: String) = _state.update { it.copy(deleteText = v, deleteError = null) }

    fun confirmDelete() {
        val s = _state.value
        if (s.deleting || s.deleteStep != DeleteStep.Confirm || !s.deleteConfirmed) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            try {
                session.deleteAccount()
                messages.show("Your account has been deleted")
                // The session is gone: the app shell returns to the Welcome screen.
                _state.update { it.copy(deleteStep = DeleteStep.None) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.code == ErrorCodes.CONFLICT || e.status == 409) {
                    _state.update { it.copy(deleteStep = DeleteStep.BlockedByOrders) }
                } else {
                    _state.update { it.copy(deleteError = e.message) }
                }
            } finally {
                _state.update { it.copy(deleting = false) }
            }
        }
    }

    fun dismissBlocked() = _state.update { it.copy(deleteStep = DeleteStep.None, deleteText = "") }
}
