package com.campusmarket.server.users

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.OkResponse
import com.campusmarket.core.UpdateProfileRequest
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import com.campusmarket.server.AUTH_JWT
import com.campusmarket.server.AppDeps
import com.campusmarket.server.UserPrincipal
import com.campusmarket.server.asUuid
import com.campusmarket.server.auth.bioError
import com.campusmarket.server.auth.displayNameError
import com.campusmarket.server.auth.normalizeBio
import com.campusmarket.server.auth.normalizeDisplayName
import com.campusmarket.server.auth.receiveBody
import com.campusmarket.server.auth.throwIfInvalid
import com.campusmarket.server.badRequest
import com.campusmarket.server.invalidField
import com.campusmarket.server.loadUserPrivate
import com.campusmarket.server.loadUserPublic
import com.campusmarket.server.loadUsersPublic
import com.campusmarket.server.notFound
import com.campusmarket.server.query
import com.campusmarket.server.queryOne
import com.campusmarket.server.toUuidOrNull
import com.campusmarket.server.unauthorized
import com.campusmarket.server.update
import com.campusmarket.server.user
import com.campusmarket.server.uuid
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import java.sql.Connection
import java.util.UUID

/**
 * Entry point for the users feature; called once from Application.module. See docs/CONTRACT.md "Users".
 * (`/me/listings`, `/me/favorites`, `/users/{id}/listings` and `/users/{id}/ratings` belong to other features.)
 */
fun Route.userRoutes(deps: AppDeps) {
    val config = deps.config
    val db = deps.db

    authenticate(AUTH_JWT) {
        get(ApiRoutes.ME) {
            val me = call.user
            call.respond<UserPrivate>(db.tx { c -> c.loadUserPrivate(config, me.id) } ?: unauthorized())
        }

        patch(ApiRoutes.ME) {
            val me = call.user
            val update = call.receiveBody<UpdateProfileRequest>().validated()
            val user = db.tx { c ->
                if (update.avatar is AvatarChange.Set) {
                    val owned = c.queryOne("SELECT 1 FROM photos WHERE id = ? AND owner_id = ?", update.avatar.photoId, me.id) { true }
                    if (owned == null) invalidField("avatarPhotoId", AVATAR_NOT_FOUND)
                }
                val sets = ArrayList<String>()
                val params = ArrayList<Any?>()
                update.displayName?.let { sets += "display_name = ?"; params += it }
                update.bio?.let { sets += "bio = ?"; params += it }
                when (update.avatar) {
                    AvatarChange.Keep -> Unit
                    AvatarChange.Clear -> sets += "avatar_photo_id = NULL"
                    is AvatarChange.Set -> { sets += "avatar_photo_id = ?"; params += update.avatar.photoId }
                }
                if (sets.isNotEmpty()) {
                    c.update("UPDATE users SET ${sets.joinToString()} WHERE id = ? AND deleted_at IS NULL", *(params + me.id).toTypedArray())
                }
                c.loadUserPrivate(config, me.id)
            } ?: unauthorized()
            call.respond<UserPrivate>(user)
        }

        delete(ApiRoutes.ME) {
            val me = call.user
            db.tx { c -> deleteAccount(c, me.id) }
            call.respond(OkResponse())
        }

        get(ApiRoutes.MY_BLOCKS) {
            val me = call.user
            val blocked = db.tx { c ->
                val ids = c.query(
                    "SELECT blocked_id FROM blocks WHERE blocker_id = ? ORDER BY created_at DESC, blocked_id",
                    me.id,
                ) { it.uuid("blocked_id") }
                val users = c.loadUsersPublic(config, ids, viewerId = me.id)
                ids.mapNotNull { users[it] }
            }
            call.respond<List<UserPublic>>(blocked)
        }

        get(ApiRoutes.user("{id}")) {
            val me = call.user
            val id = call.parameters["id"].asUuid("User")
            val profile = db.tx { c ->
                if (canSee(c, me, id)) c.loadUserPublic(config, id, viewerId = me.id) else null
            } ?: notFound("User")
            call.respond<UserPublic>(profile)
        }

        post(ApiRoutes.userBlock("{id}")) {
            val me = call.user
            val id = call.parameters["id"].asUuid("User")
            if (id == me.id) badRequest("You can't block yourself")
            db.tx { c ->
                if (!canSee(c, me, id, allowModerator = false)) notFound("User")
                c.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING", me.id, id)
            }
            call.respond(OkResponse())
        }

        delete(ApiRoutes.userBlock("{id}")) {
            val me = call.user
            val id = call.parameters["id"].asUuid("User")
            if (id == me.id) badRequest("You can't block yourself")
            db.tx { c ->
                // No campus check: a block must always be removable, even if the other account has since changed.
                c.queryOne("SELECT 1 FROM users WHERE id = ?", id) { true } ?: notFound("User")
                c.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", me.id, id)
            }
            call.respond(OkResponse())
        }
    }
}

private const val AVATAR_NOT_FOUND = "Photo not found. Upload the photo first."

/**
 * Whether [viewer] may see (and block) user [id]: a verified account on the viewer's campus. Moderators
 * work across campuses (as in the moderation queue), so they may view any profile, but blocking stays campus-scoped.
 */
private fun canSee(c: Connection, viewer: UserPrincipal, id: UUID, allowModerator: Boolean = true): Boolean {
    val campus = c.queryOne("SELECT campus_domain FROM users WHERE id = ? AND email_verified", id) { it.getString("campus_domain") }
        ?: return false
    return campus == viewer.campusDomain || (allowModerator && viewer.isModerator)
}

private sealed interface AvatarChange {
    data object Keep : AvatarChange
    data object Clear : AvatarChange
    data class Set(val photoId: UUID) : AvatarChange
}

private class ProfileUpdate(val displayName: String?, val bio: String?, val avatar: AvatarChange)

/** Normalises and validates every provided field, reporting all problems at once. */
private fun UpdateProfileRequest.validated(): ProfileUpdate {
    val errors = LinkedHashMap<String, String>()
    val name = displayName?.let(::normalizeDisplayName)?.also { n -> displayNameError(n)?.let { errors["displayName"] = it } }
    val bio = bio?.let(::normalizeBio)?.also { b -> bioError(b)?.let { errors["bio"] = it } }
    val photoId = avatarPhotoId
    val avatar = when {
        photoId == null -> AvatarChange.Keep
        photoId.isBlank() -> AvatarChange.Clear
        else -> photoId.trim().toUuidOrNull()?.let { AvatarChange.Set(it) }
            ?: AvatarChange.Keep.also { errors["avatarPhotoId"] = AVATAR_NOT_FOUND }
    }
    throwIfInvalid(errors)
    return ProfileUpdate(name, bio, avatar)
}
