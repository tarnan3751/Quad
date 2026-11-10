package com.campusmarket.app.feature.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.core.StaffMember
import com.campusmarket.core.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Minimum characters before the admin lookup is sent (the server returns nothing below this). */
const val STAFF_SEARCH_MIN_CHARS = 2

private const val SEARCH_DEBOUNCE_MS = 350L

/** The role a member can be switched to: moderators go back to student, students become moderators. Null = fixed (admins). */
fun roleToggleTarget(member: StaffMember): UserRole? = when (member.role) {
    UserRole.USER -> UserRole.MODERATOR
    UserRole.MODERATOR -> UserRole.USER
    UserRole.ADMIN -> null
}

/** Mirrors the server rules: not yourself, never an admin. */
fun canChangeRole(member: StaffMember, currentUserId: String?): Boolean =
    member.user.id != currentUserId && roleToggleTarget(member) != null

/** State of the user lookup box. */
sealed interface StaffSearch {
    /** Nothing typed (or too short): show the staff list. */
    data object Idle : StaffSearch
    data object Searching : StaffSearch
    data class Results(val query: String, val members: List<StaffMember>) : StaffSearch
    data class Failed(val message: String) : StaffSearch
}

/** Admin-only "Staff" screen: current moderators/admins, user lookup, promote / demote. */
class StaffViewModel(private val api: ApiClient, private val messages: UserMessages) : ViewModel() {
    private val _staff = MutableStateFlow<UiState<List<StaffMember>>>(UiState.Loading)
    val staff: StateFlow<UiState<List<StaffMember>>> = _staff.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _search = MutableStateFlow<StaffSearch>(StaffSearch.Idle)
    val search: StateFlow<StaffSearch> = _search.asStateFlow()

    /** Shared by the confirmation dialog (only one is open at a time). */
    val runner = ActionRunner(viewModelScope)

    /** Fired after a role change succeeded, so the screen closes its dialog. */
    val actionDone = EventQueue<Unit>()

    private var searchJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        load(_staff) { api.modStaff() }
    }

    /** Searches as you type, once typing pauses (the keyboard's search action skips the wait). */
    fun onQuery(text: String) {
        _query.value = text
        searchJob?.cancel()
        if (text.trim().length < STAFF_SEARCH_MIN_CHARS) {
            _search.value = StaffSearch.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            search(text.trim())
        }
    }

    /** Looks the typed text up now (the keyboard's search action). */
    fun runSearch() {
        val q = _query.value.trim()
        searchJob?.cancel()
        if (q.length < STAFF_SEARCH_MIN_CHARS) {
            _search.value = StaffSearch.Idle
            return
        }
        searchJob = viewModelScope.launch { search(q) }
    }

    private suspend fun search(q: String) {
        _search.value = StaffSearch.Searching
        _search.value = try {
            StaffSearch.Results(q, api.searchUsers(q))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            StaffSearch.Failed(e.fields.values.firstOrNull() ?: e.message)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _query.value = ""
        _search.value = StaffSearch.Idle
    }

    /** `PUT /mod/users/{id}/role` to [role]; refreshes the staff list and any visible search results. */
    fun setRole(member: StaffMember, role: UserRole) {
        runner.run(
            block = { api.setUserRole(member.user.id, role) },
            onSuccess = {
                messages.show(
                    if (role == UserRole.MODERATOR) "${member.user.displayName} is now a moderator"
                    else "${member.user.displayName} is no longer a moderator",
                )
                reloadAfterChange()
                actionDone.send(Unit)
            },
        )
    }

    private fun reloadAfterChange() {
        load(_staff, keepContent = true) { api.modStaff() }
        if (_search.value is StaffSearch.Results) runSearch()
    }
}
