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
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.Page
import com.campusmarket.core.Rating
import com.campusmarket.core.UpdateProfileRequest
import com.campusmarket.core.UserPublic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The server renders soft-deleted accounts with this name (`Readers.kt`). */
const val DELETED_USER_NAME = "Deleted user"

val UserPublic.isDeleted: Boolean get() = displayName == DELETED_USER_NAME

// ---------------------------------------------------------------------------------------------------- profile tab

/** Counts for the profile tab's stats card, from the first [Limits.PAGE_MAX] of my listings. */
data class MyListingStats(val active: Int, val sold: Int, val capped: Boolean) {
    fun format(n: Int): String = if (capped) "$n+" else "$n"

    companion object {
        fun of(page: Page<ListingSummary>) = MyListingStats(
            active = page.items.count { it.status == ListingStatus.ACTIVE },
            sold = page.items.count { it.status == ListingStatus.SOLD },
            capped = page.nextCursor != null,
        )
    }
}

data class ProfileTabState(
    /** null until the first load (the card shows dashes). */
    val stats: MyListingStats? = null,
    val refreshing: Boolean = false,
    val confirmSignOut: Boolean = false,
)

/**
 * Profile tab: keeps the cached profile (rating, payouts flag, avatar) fresh when the tab is shown again, loads the
 * listing counts for the stats card, and signs out.
 */
class ProfileViewModel(private val session: SessionManager, private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(ProfileTabState())
    val state: StateFlow<ProfileTabState> = _state.asStateFlow()
    private var job: Job? = null

    /** [pulled] = pull-to-refresh (shows the indicator); otherwise a quiet refresh on resume. */
    fun refresh(pulled: Boolean = false) {
        if (pulled) _state.update { it.copy(refreshing = true) }
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                coroutineScope {
                    launch {
                        try {
                            session.refreshUser()
                        } catch (e: ApiError) {
                            // keep showing the cached user
                        }
                    }
                    launch {
                        try {
                            val stats = MyListingStats.of(api.myListings(limit = Limits.PAGE_MAX))
                            _state.update { it.copy(stats = stats) }
                        } catch (e: ApiError) {
                            // keep the last counts
                        }
                    }
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun askSignOut() = _state.update { it.copy(confirmSignOut = true) }
    fun cancelSignOut() = _state.update { it.copy(confirmSignOut = false) }

    fun signOut() {
        _state.update { it.copy(confirmSignOut = false) }
        viewModelScope.launch { session.signOut() }
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
    val canSave: Boolean get() = dirty && !busy && fieldErrors.isEmpty()
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

    /** Validated as you type, so the problem shows next to the field before Save. */
    fun onDisplayName(v: String) = _state.update {
        it.copy(displayName = v, fieldErrors = it.fieldErrors - "displayName" + fieldErrorsOf("displayName" to Validate.displayName(v)), formError = null)
    }

    fun onBio(v: String) = _state.update {
        it.copy(bio = v, fieldErrors = it.fieldErrors - "bio" + fieldErrorsOf("bio" to Validate.bio(v.trim())), formError = null)
    }

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

    // Pulling either tab to refresh reloads its first page; take the header along so name, rating and bio stay current.
    val listings = Paginator(viewModelScope, key = { it.id }) { cursor, limit ->
        if (cursor == null) refreshUserQuietly()
        api.userListings(userId, cursor, limit)
    }

    /** Big pages: most people's ratings fit in one, which makes the star breakdown exact. */
    val ratings = Paginator<Rating>(viewModelScope, pageSize = Limits.PAGE_MAX, key = { it.id }) { cursor, limit ->
        if (cursor == null) refreshUserQuietly()
        api.userRatings(userId, cursor, limit)
    }

    init {
        load(_user) { api.user(userId) }
        // The header's listing count and the Message sheet need the listings even when the Ratings tab is open.
        listings.refresh()
    }

    fun reload() {
        load(_user) { api.user(userId) }
        listings.reset()
        ratings.reset()
    }

    private fun refreshUserQuietly() {
        if (_user.value is UiState.Content) load(_user, keepContent = true) { api.user(userId) }
    }

    /** Called after [BlockUserDialog] succeeded. */
    fun onBlockChanged(blocked: Boolean) {
        _user.updateContent { it.copy(blockedByMe = blocked) }
    }
}

/**
 * Star breakdown for a ratings summary: count per star (index 0 = 1 star). [complete] is false while only the newest
 * page is loaded, so the screen can say what the bars are based on.
 */
data class RatingBreakdown(val counts: List<Int>, val total: Int, val complete: Boolean) {
    fun share(stars: Int): Float = if (total == 0) 0f else counts[stars - 1].toFloat() / total

    companion object {
        fun of(ratings: List<Rating>, complete: Boolean) = RatingBreakdown(
            counts = (1..5).map { s -> ratings.count { it.stars == s } },
            total = ratings.size,
            complete = complete,
        )
    }
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

    /** Pull-to-refresh: keeps the list on screen while reloading. */
    val refreshing = MutableStateFlow(false)

    fun refresh() {
        refreshing.value = true
        load(_state, keepContent = true) { api.myBlocks() }.invokeOnCompletion { refreshing.value = false }
    }

    /** Unblocks right away (it's harmless) and offers Undo in the snackbar, which blocks them again. */
    fun unblock(user: UserPublic) {
        if (!inFlight.add(user.id)) return
        _unblocking.value = inFlight.toSet()
        viewModelScope.launch {
            try {
                api.unblockUser(user.id)
                _state.updateContent { list -> list.filterNot { it.id == user.id } }
                messages.show("Unblocked ${user.displayName}", actionLabel = "Undo") { reblock(user) }
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

    private fun reblock(user: UserPublic) {
        viewModelScope.launch {
            try {
                api.blockUser(user.id)
                _state.updateContent { list -> if (list.any { it.id == user.id }) list else list + user.copy(blockedByMe = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                messages.showError(e)
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
