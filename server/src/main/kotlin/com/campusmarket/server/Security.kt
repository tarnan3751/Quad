package com.campusmarket.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Date
import java.util.UUID

/** Name of the bearer-JWT auth provider: wrap routes in `authenticate(AUTH_JWT) { ... }`. */
const val AUTH_JWT = "auth-jwt"

/** The authenticated caller. Role and suspension are re-read from the database on every request. */
data class UserPrincipal(val id: UUID, val role: UserRole, val campusDomain: String) {
    val isModerator: Boolean get() = role == UserRole.MODERATOR || role == UserRole.ADMIN
}

class JwtService(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).build()

    data class Issued(val token: String, val expiresAt: Instant)

    fun issueAccessToken(userId: UUID, now: Instant = Instant.now()): Issued {
        val exp = now.plusSeconds(config.accessTokenTtlSeconds)
        val token = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(userId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(exp))
            .sign(algorithm)
        return Issued(token, exp)
    }

    /** Returns the user id for a valid, unexpired access token, else null. (Does not check suspension; use [authenticateToken].) */
    fun verify(token: String): UUID? = runCatching { verifier.verify(token).subject.toUuidOrNull() }.getOrNull()
}

/** Why a user row may not act right now, or null if it's fine. */
sealed interface AccessDenial {
    data object Unknown : AccessDenial
    data class Suspended(val reason: String?) : AccessDenial
}

/**
 * Loads the principal for [userId], enforcing that the account exists, is verified, not deleted and
 * not currently suspended. An expired timed suspension is treated as lifted.
 */
fun loadPrincipal(db: Db, userId: UUID): Pair<UserPrincipal?, AccessDenial?> = db.txBlocking { c ->
    c.queryOne(
        """SELECT role, campus_domain, suspended, suspended_until, suspension_reason
           FROM users WHERE id = ? AND deleted_at IS NULL AND email_verified""",
        userId,
    ) { rs ->
        val until = rs.instantOrNull("suspended_until")
        val suspended = rs.getBoolean("suspended") && (until == null || until.isAfter(Instant.now()))
        if (suspended) null to AccessDenial.Suspended(rs.getString("suspension_reason"))
        else UserPrincipal(userId, UserRole.valueOf(rs.getString("role")), rs.getString("campus_domain")) to null
    } ?: (null to AccessDenial.Unknown)
}

/** Full check of a raw access token (used by the websocket endpoint, which can't use the auth plugin's header). */
fun authenticateToken(deps: AppDeps, token: String): UserPrincipal? {
    val id = deps.jwt.verify(token) ?: return null
    return loadPrincipal(deps.db, id).first
}

fun Application.installAuth(deps: AppDeps) {
    install(Authentication) {
        jwt(AUTH_JWT) {
            verifier(deps.jwt.verifier)
            validate { cred ->
                val id = cred.payload.subject?.toUuidOrNull() ?: return@validate null
                // JDBC blocks: keep it off the engine's call threads (a slow or exhausted pool would stall every request).
                val (principal, denial) = withContext(Dispatchers.IO) { loadPrincipal(deps.db, id) }
                if (denial is AccessDenial.Suspended) attributes.put(SuspendedKey, denial.reason ?: "")
                principal
            }
            challenge { _, _ ->
                val suspendedReason = call.attributes.getOrNull(SuspendedKey)
                if (suspendedReason != null) {
                    val msg = "Your account is suspended" + if (suspendedReason.isNotBlank()) ": $suspendedReason" else ""
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorCodes.ACCOUNT_SUSPENDED, msg))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCodes.UNAUTHORIZED, "Authentication required"))
                }
            }
        }
    }
}

private val SuspendedKey = io.ktor.util.AttributeKey<String>("suspendedReason")

/** The caller; only valid inside `authenticate(AUTH_JWT)`. */
val ApplicationCall.user: UserPrincipal get() = principal<UserPrincipal>() ?: unauthorized()
val ApplicationCall.userId: UUID get() = user.id

/** Throws 403 unless the caller is a moderator or admin. */
fun ApplicationCall.requireModerator(): UserPrincipal = user.also { if (!it.isModerator) forbidden("Moderator access required") }
