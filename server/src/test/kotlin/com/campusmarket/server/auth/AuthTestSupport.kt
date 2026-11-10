package com.campusmarket.server.auth

import com.campusmarket.core.ApiJson
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.AuthResponse
import com.campusmarket.core.ErrorResponse
import com.campusmarket.core.LoginRequest
import com.campusmarket.core.RegisterRequest
import com.campusmarket.core.RegisterResponse
import com.campusmarket.core.VerifyEmailRequest
import com.campusmarket.server.ApiTestScope
import com.campusmarket.server.AppDeps
import com.campusmarket.server.TestEnv
import com.campusmarket.server.json
import com.campusmarket.server.module
import com.campusmarket.server.queryOne
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Captures outgoing mail so tests can read codes even when dev mode is off. */
class RecordingMailer : Mailer {
    private val channel = Channel<MailMessage>(Channel.UNLIMITED)

    override suspend fun send(message: MailMessage) {
        channel.send(message)
    }

    suspend fun next(timeout: Duration = 5.seconds): MailMessage = withTimeout(timeout) { channel.receive() }

    /** Messages delivered so far (the outbox is asynchronous: await [next] first when one is expected). */
    fun drain(): List<MailMessage> = generateSequence { channel.tryReceive().getOrNull() }.toList()
}

/** Very generous limits so ordinary tests never trip them; rate limiting itself is tested with small ones. */
val RelaxedRateLimits = AuthRateLimits(register = 10_000, verify = 10_000, resend = 10_000, login = 10_000, refresh = 10_000)

fun testAuthSettings(
    mailer: Mailer = RecordingMailer(),
    rateLimits: AuthRateLimits = RelaxedRateLimits,
    resendCooldown: Duration = Duration.ZERO,
    maxCodesPerDay: Int = 1_000,
    bcryptCost: Int = 4,
) = AuthSettings(
    bcryptCost = bcryptCost,
    resendCooldown = resendCooldown,
    maxCodesPerDay = maxCodesPerDay,
    rateLimits = rateLimits,
    mailer = mailer,
)

/**
 * Like `apiTest`, but runs the real `module(deps)` with [settings] injected through [AuthSettingsKey]
 * (fast bcrypt, recording mailer, relaxed rate limits by default).
 */
fun authTest(
    settings: AuthSettings = testAuthSettings(),
    deps: AppDeps = TestEnv.deps,
    block: suspend ApiTestScope.(TestEnv) -> Unit,
) = testApplication {
    TestEnv.db
    TestEnv.reset()
    application {
        attributes.put(AuthSettingsKey, settings)
        module(deps)
    }
    val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
    ApiTestScope(this, client).block(TestEnv)
}

val AuthSettings.recorder: RecordingMailer get() = mailer as RecordingMailer

const val PASSWORD = "correct horse battery"

suspend fun ApiTestScope.register(email: String, password: String = PASSWORD, displayName: String = "Alice Doe"): HttpResponse =
    client.post(ApiRoutes.REGISTER) { json(RegisterRequest(email, password, displayName)) }

suspend fun ApiTestScope.verify(email: String, code: String, password: String = PASSWORD): HttpResponse =
    client.post(ApiRoutes.VERIFY_EMAIL) { json(VerifyEmailRequest(email, code, password)) }

suspend fun ApiTestScope.login(email: String, password: String = PASSWORD): HttpResponse =
    client.post(ApiRoutes.LOGIN) { json(LoginRequest(email, password)) }

/** Registers (dev mode, so the code comes back as devCode) and verifies; returns the session. */
suspend fun ApiTestScope.signUp(email: String, password: String = PASSWORD, displayName: String = "Alice Doe"): AuthResponse {
    val reg = register(email, password, displayName)
    assertEquals(HttpStatusCode.OK, reg.status, reg.body<String>())
    val code = assertNotNull(reg.body<RegisterResponse>().devCode)
    val res = verify(email, code, password)
    assertEquals(HttpStatusCode.OK, res.status, res.body<String>())
    return res.body()
}

suspend fun HttpResponse.error(): ErrorResponse = body()

suspend fun assertError(res: HttpResponse, status: HttpStatusCode, code: String): ErrorResponse {
    val text = res.body<String>()
    assertEquals(status, res.status, text)
    val err = ApiJson.decodeFromString(ErrorResponse.serializer(), text)
    assertEquals(code, err.code, text)
    return err
}

fun userColumn(email: String, column: String): Any? = TestEnv.db.txBlocking { c ->
    c.queryOne("SELECT $column FROM users WHERE lower(email) = ?", email.lowercase()) { it.getObject(1) }
}
