package com.campusmarket.server

/**
 * Runtime configuration, read from environment variables (see docs/CONTRACT.md §Config).
 * Defaults are suitable for local development against docker-compose.
 */
data class AppConfig(
    val port: Int = 8080,
    val dbUrl: String = "jdbc:postgresql://127.0.0.1:5434/campus_market",
    val dbUser: String = "campus",
    val dbPassword: String = "campus",
    /** Origin clients use to reach this server; used to build absolute photo / redirect URLs. */
    val publicBaseUrl: String = "http://localhost:8080",
    val jwtSecret: String = DEV_JWT_SECRET,
    val jwtIssuer: String = "campus-market",
    val accessTokenTtlSeconds: Long = 15 * 60,
    val refreshTokenTtlSeconds: Long = 30L * 24 * 3600,
    /** dev mode: verification codes are returned in API responses and logged instead of emailed. */
    val devMode: Boolean = true,
    /** Comma-separated list of allowed email domain suffixes. */
    val allowedEmailSuffixes: List<String> = listOf(".edu"),
    /** Emails that are granted ADMIN on registration/verification. */
    val adminEmails: Set<String> = emptySet(),
    val uploadsDir: String = "uploads",
    /** Null => FakePaymentGateway (no network calls; local simulated checkout). */
    val stripeSecretKey: String? = null,
    val stripeWebhookSecret: String? = null,
    /** Platform fee in basis points of the item price (500 = 5%). */
    val platformFeeBps: Int = 500,
    /** Where Stripe / fake checkout sends the buyer afterwards; the apps register this scheme. */
    val appDeepLinkBase: String = "campusmarket://",
    val smtpHost: String? = null,
    val smtpPort: Int = 587,
    val smtpUser: String? = null,
    val smtpPassword: String? = null,
    val mailFrom: String = "Quad <no-reply@campusmarket.local>",
    /** Legal entity operating the service, shown on /legal pages. Null => a visible placeholder. */
    val legalEntityName: String? = null,
    /** Contact address shown on /legal pages. Null => a visible placeholder. */
    val supportEmail: String? = null,
    /** Per-user abuse limits on authenticated actions (see [UserRateLimits]); `USER_RATE_LIMITS_ENABLED=false` turns them off. */
    val userRateLimits: UserRateLimits = UserRateLimits(),
) {
    /**
     * Problems that make this configuration unsafe to serve real users with; empty when it's fine. Outside dev mode
     * the server must not start with any of them (dev mode returns verification codes in API responses and mounts
     * the fake processor's "Pay" pages, which mark orders paid without any money moving).
     */
    fun productionProblems(): List<String> = buildList {
        if (platformFeeBps !in 0..10_000) add("PLATFORM_FEE_BPS must be between 0 and 10000 (got $platformFeeBps)")
        if (devMode) {
            if (stripeSecretKey?.let { it.startsWith("sk_live_") || it.startsWith("rk_live_") } == true) {
                add("A live STRIPE_SECRET_KEY must not be used with DEV_MODE=true")
            }
            return@buildList
        }
        if (jwtSecret == DEV_JWT_SECRET || jwtSecret.length < 32) {
            add("JWT_SECRET must be set to a random value of at least 32 characters when DEV_MODE=false")
        }
        if (stripeSecretKey == null) add("STRIPE_SECRET_KEY must be set when DEV_MODE=false (the fake processor is dev-only)")
        if (stripeWebhookSecret == null) add("STRIPE_WEBHOOK_SECRET must be set when DEV_MODE=false (payments are confirmed by webhooks)")
        if (smtpHost == null) add("SMTP_HOST must be set when DEV_MODE=false (verification codes are sent by email)")
    }

    companion object {
        private const val DEV_JWT_SECRET = "dev-only-secret-change-me-dev-only-secret-change-me"

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val d = AppConfig()
            fun s(k: String) = env[k]?.takeIf { it.isNotBlank() }
            val devMode = s("DEV_MODE")?.toBoolean() ?: d.devMode
            val cfg = AppConfig(
                port = s("PORT")?.toInt() ?: d.port,
                dbUrl = s("DATABASE_URL") ?: d.dbUrl,
                dbUser = s("DATABASE_USER") ?: d.dbUser,
                dbPassword = s("DATABASE_PASSWORD") ?: d.dbPassword,
                publicBaseUrl = (s("PUBLIC_BASE_URL") ?: d.publicBaseUrl).trimEnd('/'),
                jwtSecret = s("JWT_SECRET") ?: d.jwtSecret,
                jwtIssuer = s("JWT_ISSUER") ?: d.jwtIssuer,
                accessTokenTtlSeconds = s("ACCESS_TOKEN_TTL_SECONDS")?.toLong() ?: d.accessTokenTtlSeconds,
                refreshTokenTtlSeconds = s("REFRESH_TOKEN_TTL_SECONDS")?.toLong() ?: d.refreshTokenTtlSeconds,
                devMode = devMode,
                allowedEmailSuffixes = s("ALLOWED_EMAIL_SUFFIXES")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
                    ?: d.allowedEmailSuffixes,
                adminEmails = s("ADMIN_EMAILS")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
                    ?: d.adminEmails,
                uploadsDir = s("UPLOADS_DIR") ?: d.uploadsDir,
                stripeSecretKey = s("STRIPE_SECRET_KEY"),
                stripeWebhookSecret = s("STRIPE_WEBHOOK_SECRET"),
                platformFeeBps = s("PLATFORM_FEE_BPS")?.toInt() ?: d.platformFeeBps,
                appDeepLinkBase = s("APP_DEEP_LINK_BASE") ?: d.appDeepLinkBase,
                smtpHost = s("SMTP_HOST"),
                smtpPort = s("SMTP_PORT")?.toInt() ?: d.smtpPort,
                smtpUser = s("SMTP_USER"),
                smtpPassword = s("SMTP_PASSWORD"),
                mailFrom = s("MAIL_FROM") ?: d.mailFrom,
                legalEntityName = s("LEGAL_ENTITY_NAME"),
                supportEmail = s("SUPPORT_EMAIL"),
                userRateLimits = d.userRateLimits.copy(
                    enabled = s("USER_RATE_LIMITS_ENABLED")?.toBooleanStrictOrNull() ?: d.userRateLimits.enabled,
                ),
            )
            val problems = cfg.productionProblems()
            require(problems.isEmpty()) { "Unsafe configuration:\n  - " + problems.joinToString("\n  - ") }
            return cfg
        }
    }
}
