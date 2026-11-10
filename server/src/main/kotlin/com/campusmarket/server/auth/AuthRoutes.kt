package com.campusmarket.server.auth

import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.DeleteAccountRequest
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.LogoutRequest
import com.campusmarket.core.RefreshRequest
import com.campusmarket.core.RegisterRequest
import com.campusmarket.core.ResendCodeRequest
import com.campusmarket.core.VerifyEmailRequest
import com.campusmarket.server.AppDeps
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.campusmarket.server.auth.AuthRoutes")

/** Entry point for the auth feature; called once from Application.module. See docs/CONTRACT.md "Auth". */
fun Route.authRoutes(deps: AppDeps) {
    val app = application
    val settings = app.attributes.getOrNull(AuthSettingsKey) ?: AuthSettings.fromEnv()
    val mailer = settings.mailer ?: Mailer.fromConfig(deps.config)
    val service = AuthService(deps, settings, MailOutbox(mailer, app.coroutineContext[Job]))
    val limited = installAuthRateLimits(settings.rateLimits)

    limitedBy(limited, AuthLimit.REGISTER) {
        post(ApiRoutes.REGISTER) { call.respond(service.register(call.receiveBody<RegisterRequest>())) }
    }
    limitedBy(limited, AuthLimit.VERIFY) {
        post(ApiRoutes.VERIFY_EMAIL) { call.respond(service.verify(call.receiveBody<VerifyEmailRequest>())) }
    }
    limitedBy(limited, AuthLimit.RESEND) {
        post(ApiRoutes.RESEND_CODE) { call.respond(service.resend(call.receiveBody<ResendCodeRequest>())) }
    }
    limitedBy(limited, AuthLimit.LOGIN) {
        post(ApiRoutes.LOGIN) { call.respond(service.login(call.receiveBody<LoginRequest>())) }
        // Also checks a password: shares the login budget, so the two can't be combined to guess more.
        post(ApiRoutes.DELETE_ACCOUNT) { call.respond(service.deleteAccountWithCredentials(call.receiveBody<DeleteAccountRequest>())) }
    }
    limitedBy(limited, AuthLimit.SESSION) {
        post(ApiRoutes.REFRESH) { call.respond(service.refresh(call.receiveBody<RefreshRequest>())) }
        post(ApiRoutes.LOGOUT) { call.respond(service.logout(call.receiveBody<LogoutRequest>())) }
    }
}

/** Per-client-IP rate limiter buckets of the auth endpoints (Ktor RateLimit provider names). */
enum class AuthLimit(val providerName: RateLimitName, val budget: (AuthRateLimits) -> Int) {
    REGISTER(RateLimitName("auth-register"), { it.register }),
    VERIFY(RateLimitName("auth-verify"), { it.verify }),
    RESEND(RateLimitName("auth-resend"), { it.resend }),
    LOGIN(RateLimitName("auth-login"), { it.login }),
    SESSION(RateLimitName("auth-session"), { it.refresh }),
}

/**
 * Registers the [AuthLimit] providers, keyed by client IP. Public so that, should RateLimit ever be
 * installed centrally (it accepts providers only at install time), that install can include them:
 * `install(RateLimit) { registerAuthRateLimits(AuthSettings.fromEnv().rateLimits); ... }`.
 */
fun RateLimitConfig.registerAuthRateLimits(limits: AuthRateLimits) {
    for (limit in AuthLimit.entries) {
        register(limit.providerName) {
            rateLimiter(limit = limit.budget(limits), refillPeriod = limits.window)
            // The direct peer, or the client address forwarded by our own proxies (see AuthRateLimits.trustedProxyHops).
            requestKey { call ->
                clientAddress(call.request.origin.remoteAddress, call.request.headers.getAll("X-Forwarded-For").orEmpty(), limits.trustedProxyHops)
            }
        }
    }
}

/**
 * Installs the Ktor RateLimit plugin with the auth providers (Application.kt doesn't install it).
 * If something else installed it first, that install must have called [registerAuthRateLimits]
 * (Ktor fails route setup otherwise). Returns whether the auth routes should be limited.
 */
private fun Route.installAuthRateLimits(limits: AuthRateLimits): Boolean {
    if (!limits.enabled) {
        log.warn("Auth rate limiting is disabled")
        return false
    }
    val app = application
    if (app.pluginOrNull(RateLimit) != null) {
        log.info("RateLimit already installed; auth routes expect providers {}", AuthLimit.entries.map { it.providerName })
    } else {
        app.install(RateLimit) { registerAuthRateLimits(limits) }
    }
    return true
}

private fun Route.limitedBy(enabled: Boolean, limit: AuthLimit, build: Route.() -> Unit) {
    if (enabled) rateLimit(limit.providerName, build) else build()
}
