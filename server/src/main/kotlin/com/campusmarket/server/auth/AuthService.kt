package com.campusmarket.server.auth

import com.campusmarket.core.AuthResponse
import com.campusmarket.core.DeleteAccountRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.LogoutRequest
import com.campusmarket.core.OkResponse
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.RegisterRequest
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.ResendCodeRequest
import com.campusmarket.core.VerifyEmailRequest
import com.campusmarket.server.ApiException
import com.campusmarket.server.AppDeps
import com.campusmarket.server.conflict
import com.campusmarket.server.forbidden
import com.campusmarket.server.instant
import com.campusmarket.server.instantOrNull
import com.campusmarket.server.invalidField
import com.campusmarket.server.loadUserPrivate
import com.campusmarket.server.queryOne
import com.campusmarket.server.unauthorized
import com.campusmarket.server.update
import com.campusmarket.server.users.deleteAccount
import com.campusmarket.server.uuid
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Registration, email verification and sessions (see docs/CONTRACT.md "Auth").
 *
 * Sessions are a short-lived HS256 access JWT plus an opaque refresh token that is stored only as a
 * digest and rotated on every use; presenting an already-revoked refresh token is treated as theft and
 * revokes every session of that user.
 */
class AuthService(
    private val deps: AppDeps,
    private val settings: AuthSettings,
    private val outbox: MailOutbox,
) {
    private val config = deps.config
    private val db = deps.db
    private val passwords = PasswordHasher(settings.bcryptCost)
    private val secrets = SecretCodec(config.jwtSecret)
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    // ---------------------------------------------------------------- register

    suspend fun register(req: RegisterRequest): RegisterResponse {
        val email = normalizeEmail(req.email)
        val displayName = normalizeDisplayName(req.displayName)
        throwIfInvalid(
            buildMap {
                emailError(email)?.let { put("email", it) }
                passwordError(req.password)?.let { put("password", it) }
                displayNameError(displayName)?.let { put("displayName", it) }
            },
        )
        val domain = emailDomain(email)
        if (!isAllowedDomain(domain, config.allowedEmailSuffixes)) {
            val msg = "Use your campus email address (ending in ${config.allowedEmailSuffixes.joinToString(" or ")})"
            throw ApiException(HttpStatusCode.BadRequest, ErrorCodes.EMAIL_NOT_ALLOWED, msg, mapOf("email" to msg))
        }

        val passwordHash = passwords.hash(req.password) // outside the transaction: ~0.25 s of CPU at cost 12
        val code = secrets.newVerificationCode()
        val now = Instant.now()
        db.tx { c ->
            // Two rounds at most: if a concurrent registration inserts the same email between our SELECT and
            // INSERT, ON CONFLICT DO NOTHING waits for it to commit and the second round takes the update path.
            repeat(2) {
                val existing = c.queryOne(
                    "SELECT $CODE_STATE_COLS FROM users WHERE lower(email) = ? FOR UPDATE",
                    email,
                ) { it.toCodeState() }
                when {
                    existing == null -> {
                        val id = UUID.randomUUID()
                        val inserted = c.update(
                            """INSERT INTO users (id, email, password_hash, display_name, campus_domain,
                                                  verification_code_hash, verification_expires_at, verification_attempts,
                                                  verification_sent_at, verification_window_start, verification_sends)
                               VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 1)
                               ON CONFLICT ((lower(email))) DO NOTHING""",
                            id, email, passwordHash, displayName, domain,
                            secrets.verificationDigest(id, code), now + settings.codeTtl, now, now,
                        )
                        if (inserted == 1) return@tx
                    }
                    existing.verified || existing.deleted ->
                        conflict("An account with this email already exists. Try signing in.", ErrorCodes.EMAIL_TAKEN)
                    else -> {
                        // Unverified duplicate: whoever proves ownership of the inbox decides the password.
                        c.update(
                            "UPDATE users SET password_hash = ?, display_name = ? WHERE id = ?",
                            passwordHash, displayName, existing.id,
                        )
                        when (issueCode(c, existing, code, now, enforceCooldown = false)) {
                            CodeIssue.ISSUED -> return@tx
                            CodeIssue.DAILY_CAP -> throw ApiException(
                                HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED,
                                "Too many verification codes were requested for this email. Try again later.",
                            )
                            CodeIssue.COOLDOWN -> error("cooldown not enforced on register")
                        }
                    }
                }
            }
            error("Registration for $email did not settle after a concurrent insert")
        }
        dispatchCode(email, code)
        return RegisterResponse(email = email, verificationRequired = true, devCode = code.takeIf { config.devMode })
    }

    // ---------------------------------------------------------------- verify

    private sealed interface VerifyOutcome {
        data object Invalid : VerifyOutcome
        data class Suspended(val reason: String?) : VerifyOutcome
        data class Ok(val session: AuthResponse) : VerifyOutcome
    }

    suspend fun verify(req: VerifyEmailRequest): AuthResponse {
        val email = normalizeEmail(req.email)
        val code = req.code.filterNot { it.isWhitespace() || it == '-' } // tolerate "123 456" / "123-456"
        val wellFormed = emailError(email) == null && code.length == Limits.VERIFICATION_CODE_LENGTH && code.all { it in '0'..'9' }
        // The pending password must be proven as well as the code, otherwise someone who re-registered a victim's
        // address with their own password could get the victim to verify that account. The bcrypt check always runs
        // (dummy hash when there is no usable pending account) so a wrong password costs the same as a wrong code.
        val checkedHash = if (!wellFormed || req.password.length > Limits.PASSWORD_MAX) null else db.tx { c ->
            c.queryOne("SELECT password_hash FROM users WHERE lower(email) = ? AND deleted_at IS NULL AND NOT email_verified", email) {
                it.getString(1)
            }
        }
        val passwordOk = passwords.verify(req.password, checkedHash).ok
        if (!wellFormed) invalidCode()
        val now = Instant.now()
        val outcome = db.tx { c ->
            val row = c.queryOne(
                """SELECT $CODE_STATE_COLS, password_hash, verification_code_hash, verification_expires_at, verification_attempts,
                          suspended, suspended_until, suspension_reason
                   FROM users WHERE lower(email) = ? FOR UPDATE""",
                email,
            ) { rs ->
                VerifyRow(
                    state = rs.toCodeState(),
                    passwordHash = rs.getString("password_hash"),
                    codeHash = rs.getString("verification_code_hash"),
                    expiresAt = rs.instantOrNull("verification_expires_at"),
                    attempts = rs.getInt("verification_attempts"),
                    suspension = rs.activeSuspension(now),
                )
            }
            if (row == null || row.state.verified || row.state.deleted || row.codeHash == null || row.expiresAt == null) {
                return@tx VerifyOutcome.Invalid
            }
            val id = row.state.id
            if (!row.expiresAt.isAfter(now) || row.attempts >= settings.maxCodeAttempts) {
                burnCode(c, id, row.attempts)
                return@tx VerifyOutcome.Invalid
            }
            // The password must still be the one that was checked (a concurrent re-registration changes it).
            val passwordMatches = passwordOk && row.passwordHash == checkedHash
            val codeMatches = SecretCodec.constantTimeEquals(secrets.verificationDigest(id, code), row.codeHash)
            if (!codeMatches || !passwordMatches) {
                val attempts = row.attempts + 1
                if (attempts >= settings.maxCodeAttempts) burnCode(c, id, attempts)
                else c.update("UPDATE users SET verification_attempts = ? WHERE id = ?", attempts, id)
                return@tx VerifyOutcome.Invalid // committed: the failed attempt must count
            }
            c.update(
                """UPDATE users SET email_verified = true, verification_code_hash = NULL, verification_expires_at = NULL,
                                    verification_attempts = 0,
                                    role = CASE WHEN ? THEN 'ADMIN' ELSE role END
                   WHERE id = ?""",
                isAdminEmail(email), id,
            )
            if (row.suspension != null) VerifyOutcome.Suspended(row.suspension.reason)
            else VerifyOutcome.Ok(issueSession(c, id, now))
        }
        return when (outcome) {
            VerifyOutcome.Invalid -> invalidCode()
            is VerifyOutcome.Suspended -> suspended(outcome.reason)
            is VerifyOutcome.Ok -> outcome.session
        }
    }

    private data class VerifyRow(
        val state: CodeState,
        val passwordHash: String?,
        val codeHash: String?,
        val expiresAt: Instant?,
        val attempts: Int,
        val suspension: Suspension?,
    )

    /** Invalidates the current code (expired or out of attempts); a new one must be requested. */
    private fun burnCode(c: Connection, id: UUID, attempts: Int) {
        c.update(
            "UPDATE users SET verification_code_hash = NULL, verification_expires_at = NULL, verification_attempts = ? WHERE id = ?",
            attempts, id,
        )
    }

    private fun invalidCode(): Nothing {
        val msg = "That code is invalid or has expired. Check your latest email or request a new code."
        throw ApiException(HttpStatusCode.BadRequest, ErrorCodes.INVALID_CODE, msg, mapOf("code" to msg))
    }

    // ---------------------------------------------------------------- resend

    /**
     * Always answers 200 with the same shape, whether or not the account exists or is already verified,
     * so it can't be used to discover registered emails. A new code is only issued for an existing
     * unverified account outside the cooldown and under the daily cap; in dev mode `devCode` is set then.
     */
    suspend fun resend(req: ResendCodeRequest): RegisterResponse {
        val email = normalizeEmail(req.email)
        emailError(email)?.let { invalidField("email", it) }
        val code = secrets.newVerificationCode()
        val now = Instant.now()
        val issued = db.tx { c ->
            val state = c.queryOne("SELECT $CODE_STATE_COLS FROM users WHERE lower(email) = ? FOR UPDATE", email) { it.toCodeState() }
            state != null && !state.verified && !state.deleted &&
                issueCode(c, state, code, now, enforceCooldown = true) == CodeIssue.ISSUED
        }
        if (issued) dispatchCode(email, code)
        return RegisterResponse(email = email, verificationRequired = true, devCode = code.takeIf { issued && config.devMode })
    }

    // ---------------------------------------------------------------- login

    suspend fun login(req: LoginRequest): AuthResponse {
        val email = normalizeEmail(req.email)
        val now = Instant.now()
        val (row, check) = provenAccount(email, req.password, now)
        // Only now, with the password proven, is it safe to say anything about the account's state.
        if (!row.verified) forbidden("Verify your email address to sign in. We can send you a new code.", ErrorCodes.EMAIL_NOT_VERIFIED)
        if (row.suspension != null) suspended(row.suspension.reason)

        val upgradedHash = if (check.needsRehash) passwords.hash(req.password) else null
        return db.tx { c ->
            if (upgradedHash != null) {
                c.update("UPDATE users SET password_hash = ? WHERE id = ? AND password_hash = ?", upgradedHash, row.id, row.passwordHash)
            }
            if (isAdminEmail(email)) c.update("UPDATE users SET role = 'ADMIN' WHERE id = ? AND role <> 'ADMIN'", row.id)
            issueSession(c, row.id, now)
        }
    }

    private data class LoginRow(val id: UUID, val passwordHash: String, val verified: Boolean, val suspension: Suspension?)

    /**
     * The account [email] (normalised) and [password] prove, or 401 `INVALID_CREDENTIALS` alike for an unknown email
     * and a wrong password: unknown emails are checked against a dummy hash, so response time doesn't reveal which
     * accounts exist. Deleted accounts don't exist here.
     */
    private suspend fun provenAccount(email: String, password: String, now: Instant): Pair<LoginRow, PasswordHasher.Check> {
        throwIfInvalid(
            buildMap {
                if (email.isEmpty()) put("email", "Enter your email address")
                if (password.isEmpty()) put("password", "Enter your password")
            },
        )
        // Values that can't belong to any account skip the lookup but still pay for the bcrypt check.
        val row = if (emailError(email) != null || password.length > Limits.PASSWORD_MAX) null else db.tx { c ->
            c.queryOne(
                """SELECT id, password_hash, email_verified, suspended, suspended_until, suspension_reason
                   FROM users WHERE lower(email) = ? AND deleted_at IS NULL""",
                email,
            ) { rs ->
                LoginRow(rs.uuid("id"), rs.getString("password_hash"), rs.getBoolean("email_verified"), rs.activeSuspension(now))
            }
        }
        val check = passwords.verify(password, row?.passwordHash)
        if (row == null || !check.ok) unauthorized("Incorrect email or password", ErrorCodes.INVALID_CREDENTIALS)
        return row to check
    }

    // ---------------------------------------------------------------- credential-authenticated deletion

    /**
     * `POST /auth/delete-account`: deletes the account proven by email + password, with exactly the rules and effects
     * of `DELETE /me` ([deleteAccount]). Needs no session, so it also works for suspended accounts (store requirement:
     * every user must be able to delete their account). Answers exactly like login for unknown emails and wrong
     * passwords (same 401, same bcrypt cost), and says nothing about the account (unverified, orders in progress)
     * before the password is proven.
     */
    suspend fun deleteAccountWithCredentials(req: DeleteAccountRequest): OkResponse {
        val (id, hash, verified) = provenAccount(normalizeEmail(req.email), req.password, Instant.now()).first
        if (!verified) forbidden("Verify your email address first. We can send you a new code.", ErrorCodes.EMAIL_NOT_VERIFIED)
        db.tx { c ->
            // The password must still be the one we checked (a concurrent change would make this a stale proof).
            val current = c.queryOne("SELECT password_hash FROM users WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id) { it.getString(1) }
            if (current != hash) unauthorized("Incorrect email or password", ErrorCodes.INVALID_CREDENTIALS)
            deleteAccount(c, id)
        }
        log.info("Account {} deleted with credentials", id)
        return OkResponse()
    }

    // ---------------------------------------------------------------- refresh / logout

    private sealed interface RefreshOutcome {
        data object Invalid : RefreshOutcome
        data object Reused : RefreshOutcome
        data class Suspended(val reason: String?) : RefreshOutcome
        data class Ok(val session: AuthResponse) : RefreshOutcome
    }

    suspend fun refresh(req: RefreshRequest): AuthResponse {
        val token = req.refreshToken.trim()
        if (!looksLikeRefreshToken(token)) invalidRefresh()
        val digest = secrets.refreshTokenDigest(token)
        val now = Instant.now()
        val outcome = db.tx { c ->
            // Row lock serialises concurrent uses of one token: exactly one rotates it, the other sees it revoked.
            val row = c.queryOne(
                "SELECT id, user_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ? FOR UPDATE",
                digest,
            ) { rs -> TokenRow(rs.uuid("id"), rs.uuid("user_id"), rs.instant("expires_at"), rs.instantOrNull("revoked_at")) }
                ?: return@tx RefreshOutcome.Invalid
            if (!row.expiresAt.isAfter(now)) return@tx RefreshOutcome.Invalid // stale either way; nothing to defend
            if (row.revokedAt != null) {
                // A rotated-away (or logged-out) token came back: assume it was stolen and end every session.
                val n = c.update("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", now, row.userId)
                log.warn("Refresh token reuse for user {}; revoked {} active session(s)", row.userId, n)
                return@tx RefreshOutcome.Reused // committed, then reported as 401
            }
            val owner = c.queryOne(
                """SELECT suspended, suspended_until, suspension_reason FROM users
                   WHERE id = ? AND deleted_at IS NULL AND email_verified""",
                row.userId,
            ) { rs -> TokenOwner(rs.activeSuspension(now)) } ?: return@tx RefreshOutcome.Invalid
            if (owner.suspension != null) return@tx RefreshOutcome.Suspended(owner.suspension.reason)
            c.update("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?", now, row.id)
            RefreshOutcome.Ok(issueSession(c, row.userId, now))
        }
        return when (outcome) {
            RefreshOutcome.Invalid, RefreshOutcome.Reused -> invalidRefresh()
            is RefreshOutcome.Suspended -> suspended(outcome.reason)
            is RefreshOutcome.Ok -> outcome.session
        }
    }

    private data class TokenRow(val id: UUID, val userId: UUID, val expiresAt: Instant, val revokedAt: Instant?)
    private data class TokenOwner(val suspension: Suspension?)

    /** Idempotent: unknown, expired or already-revoked tokens are a successful no-op. */
    suspend fun logout(req: LogoutRequest): OkResponse {
        val token = req.refreshToken.trim()
        if (looksLikeRefreshToken(token)) {
            val digest = secrets.refreshTokenDigest(token)
            db.tx { c -> c.update("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL", digest) }
        }
        return OkResponse()
    }

    private fun looksLikeRefreshToken(token: String) = token.isNotEmpty() && token.length <= 256

    private fun invalidRefresh(): Nothing = unauthorized("Your session has expired. Please sign in again.")

    // ---------------------------------------------------------------- shared

    /**
     * Creates a refresh token row and access token for [userId] and renders the response. Expired rows of
     * the user are pruned here; revoked-but-unexpired ones are kept, as they are what reuse detection matches.
     */
    private fun issueSession(c: Connection, userId: UUID, now: Instant): AuthResponse {
        c.update("DELETE FROM refresh_tokens WHERE user_id = ? AND expires_at < ?", userId, now)
        val refreshToken = secrets.newRefreshToken()
        c.update(
            "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), userId, secrets.refreshTokenDigest(refreshToken), now.plusSeconds(config.refreshTokenTtlSeconds), now,
        )
        val access = deps.jwt.issueAccessToken(userId, now)
        val user = c.loadUserPrivate(config, userId) ?: error("User $userId disappeared while issuing a session")
        return AuthResponse(
            accessToken = access.token,
            refreshToken = refreshToken,
            accessTokenExpiresAt = access.expiresAt.toEpochMilli(),
            user = user,
        )
    }

    private enum class CodeIssue { ISSUED, COOLDOWN, DAILY_CAP }

    /** Columns of [CodeState]; select them with the row locked (`FOR UPDATE`). */
    private data class CodeState(
        val id: UUID,
        val verified: Boolean,
        val deleted: Boolean,
        val sentAt: Instant?,
        val windowStart: Instant?,
        val sends: Int,
    )

    private fun ResultSet.toCodeState() = CodeState(
        id = uuid("id"),
        verified = getBoolean("email_verified"),
        deleted = getTimestamp("deleted_at") != null,
        sentAt = instantOrNull("verification_sent_at"),
        windowStart = instantOrNull("verification_window_start"),
        sends = getInt("verification_sends"),
    )

    /** Stores a fresh code for the (locked) account unless the cooldown or the daily cap forbids it. */
    private fun issueCode(c: Connection, state: CodeState, code: String, now: Instant, enforceCooldown: Boolean): CodeIssue {
        if (enforceCooldown && state.sentAt != null && (state.sentAt + settings.resendCooldown).isAfter(now)) return CodeIssue.COOLDOWN
        val newWindow = state.windowStart == null || !(state.windowStart + ISSUE_WINDOW).isAfter(now)
        val sendsInWindow = if (newWindow) 0 else state.sends
        if (sendsInWindow >= settings.maxCodesPerDay) return CodeIssue.DAILY_CAP
        c.update(
            """UPDATE users SET verification_code_hash = ?, verification_expires_at = ?, verification_attempts = 0,
                                verification_sent_at = ?, verification_window_start = ?, verification_sends = ?
               WHERE id = ?""",
            secrets.verificationDigest(state.id, code), now + settings.codeTtl, now,
            if (newWindow) now else state.windowStart, sendsInWindow + 1, state.id,
        )
        return CodeIssue.ISSUED
    }

    private fun dispatchCode(email: String, code: String) {
        if (config.devMode) log.info("[dev] Verification code for {}: {}", email, code)
        outbox.enqueue(verificationEmail(email, code, settings.codeTtl))
    }

    private fun isAdminEmail(email: String) = config.adminEmails.any { normalizeEmail(it) == email }

    private data class Suspension(val reason: String?)

    /** Suspension in force at [now] (a timed suspension that has run out no longer counts), or null. */
    private fun ResultSet.activeSuspension(now: Instant): Suspension? {
        if (!getBoolean("suspended")) return null
        val until = instantOrNull("suspended_until")
        return if (until == null || until.isAfter(now)) Suspension(getString("suspension_reason")) else null
    }

    /** Same wording as the central auth challenge in Security.kt. */
    private fun suspended(reason: String?): Nothing =
        forbidden("Your account is suspended" + if (!reason.isNullOrBlank()) ": $reason" else "", ErrorCodes.ACCOUNT_SUSPENDED)

    private operator fun Instant.plus(d: Duration): Instant = plus(d.toJavaDuration())

    private companion object {
        const val CODE_STATE_COLS =
            "id, email_verified, deleted_at, verification_sent_at, verification_window_start, verification_sends"
        val ISSUE_WINDOW = 24.hours
    }
}
