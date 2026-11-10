package com.campusmarket.server.auth

import io.ktor.util.AttributeKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tunables of the auth feature that are not part of [com.campusmarket.server.AppConfig].
 *
 * Production reads them from the environment ([fromEnv]). Tests (or an embedding application) can
 * override them by putting an instance into the application attributes under [AuthSettingsKey]
 * before `module(deps)` runs.
 */
data class AuthSettings(
    /** bcrypt work factor for new hashes. Contract: 12 in production; 4 is fine in tests. */
    val bcryptCost: Int = 12,
    /** Lifetime of an emailed verification code. */
    val codeTtl: Duration = 15.minutes,
    /** Wrong guesses allowed per code before it is burned and a new one must be requested. */
    val maxCodeAttempts: Int = 5,
    /** /auth/resend-code is a silent no-op for an account whose last code is younger than this. */
    val resendCooldown: Duration = 30.seconds,
    /**
     * Codes issued per account per 24h (register + resend). Bounds both mail-bombing of an inbox and
     * brute-forcing by cycling codes (at most [maxCodesPerDay] x [maxCodeAttempts] guesses a day).
     */
    val maxCodesPerDay: Int = 10,
    val rateLimits: AuthRateLimits = AuthRateLimits(),
    /** Overrides mailer selection; null = SMTP when `SMTP_HOST` is set, else [LogMailer]. */
    val mailer: Mailer? = null,
) {
    init {
        require(bcryptCost in 4..31) { "bcryptCost must be in 4..31" }
        require(maxCodeAttempts >= 1) { "maxCodeAttempts must be >= 1" }
        require(maxCodesPerDay >= 1) { "maxCodesPerDay must be >= 1" }
        require(codeTtl.isPositive()) { "codeTtl must be positive" }
        require(!resendCooldown.isNegative()) { "resendCooldown must not be negative" }
    }

    companion object {
        /**
         * Env vars (all optional): `BCRYPT_COST`, `AUTH_RESEND_COOLDOWN_SECONDS`, `AUTH_MAX_CODES_PER_DAY`, `AUTH_RATE_LIMIT_ENABLED`,
         * `AUTH_RATE_LIMIT_WINDOW_SECONDS`, and per-endpoint request budgets per client IP per window:
         * `AUTH_RATE_LIMIT_REGISTER`, `AUTH_RATE_LIMIT_VERIFY`, `AUTH_RATE_LIMIT_RESEND`, `AUTH_RATE_LIMIT_LOGIN`,
         * `AUTH_RATE_LIMIT_REFRESH`.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): AuthSettings {
            val d = AuthSettings()
            val r = d.rateLimits
            fun s(k: String) = env[k]?.trim()?.takeIf { it.isNotEmpty() }
            fun i(k: String) = s(k)?.let { v -> v.toIntOrNull() ?: error("$k must be an integer, got '$v'") }
            return d.copy(
                bcryptCost = i("BCRYPT_COST") ?: d.bcryptCost,
                resendCooldown = i("AUTH_RESEND_COOLDOWN_SECONDS")?.seconds ?: d.resendCooldown,
                maxCodesPerDay = i("AUTH_MAX_CODES_PER_DAY") ?: d.maxCodesPerDay,
                rateLimits = AuthRateLimits(
                    enabled = s("AUTH_RATE_LIMIT_ENABLED")?.toBooleanStrictOrNull() ?: r.enabled,
                    window = i("AUTH_RATE_LIMIT_WINDOW_SECONDS")?.seconds ?: r.window,
                    register = i("AUTH_RATE_LIMIT_REGISTER") ?: r.register,
                    verify = i("AUTH_RATE_LIMIT_VERIFY") ?: r.verify,
                    resend = i("AUTH_RATE_LIMIT_RESEND") ?: r.resend,
                    login = i("AUTH_RATE_LIMIT_LOGIN") ?: r.login,
                    refresh = i("AUTH_RATE_LIMIT_REFRESH") ?: r.refresh,
                    trustedProxyHops = i("TRUSTED_PROXY_HOPS") ?: r.trustedProxyHops,
                ),
            )
        }
    }
}

/**
 * Per-client-IP request budgets for the public auth endpoints, each refilled every [window].
 * Defaults are deliberately generous: many students share one campus NAT address, and per-account
 * protections (code attempt limit, bcrypt cost) do the fine-grained work.
 */
data class AuthRateLimits(
    val enabled: Boolean = true,
    val window: Duration = 1.minutes,
    val register: Int = 20,
    val verify: Int = 30,
    val resend: Int = 10,
    val login: Int = 30,
    val refresh: Int = 120,
    /**
     * Number of reverse proxies in front of the server that append to `X-Forwarded-For` (`TRUSTED_PROXY_HOPS`).
     * 0 (default): the TCP peer is the client. Behind a TLS-terminating proxy this must be set (usually 1), otherwise
     * every client shares the proxy's address and the budgets above become global.
     */
    val trustedProxyHops: Int = 0,
) {
    init {
        require(window.isPositive()) { "rate limit window must be positive" }
        require(listOf(register, verify, resend, login, refresh).all { it >= 1 }) { "rate limits must be >= 1" }
        require(trustedProxyHops >= 0) { "TRUSTED_PROXY_HOPS must be >= 0" }
    }
}

/**
 * The client's address for rate limiting. With [trustedHops] proxies in front, each appending the address it received
 * the request from to `X-Forwarded-For`, the client is the entry [trustedHops] places from the end: entries further
 * left were supplied by the client itself and can't be trusted.
 */
fun clientAddress(directPeer: String, forwardedFor: List<String>, trustedHops: Int): String {
    if (trustedHops <= 0) return directPeer
    val chain = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return chain.getOrNull(chain.size - trustedHops) ?: directPeer
}

/** Application attribute that overrides [AuthSettings.fromEnv]; must be set before routing is built. */
val AuthSettingsKey = AttributeKey<AuthSettings>("campusmarket.AuthSettings")
