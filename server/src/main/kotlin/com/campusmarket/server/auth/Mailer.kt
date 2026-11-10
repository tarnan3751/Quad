package com.campusmarket.server.auth

import com.campusmarket.server.AppConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class MailMessage(val to: String, val subject: String, val text: String)

/** Outgoing email. Implementations may block/suspend on the network; callers go through [MailOutbox]. */
interface Mailer {
    suspend fun send(message: MailMessage)

    companion object {
        private val log = LoggerFactory.getLogger(Mailer::class.java)

        /** SMTP when `SMTP_HOST` is configured, otherwise [LogMailer]. */
        fun fromConfig(config: AppConfig): Mailer {
            val host = config.smtpHost
            if (host != null) return SmtpMailer(host, config.smtpPort, config.smtpUser, config.smtpPassword, config.mailFrom)
            if (!config.devMode) log.warn("SMTP_HOST is not set: outgoing email (verification codes) is only written to the log")
            return LogMailer()
        }
    }
}

/** Development mailer: writes the message to the log instead of sending it. */
class LogMailer : Mailer {
    private val log = LoggerFactory.getLogger(LogMailer::class.java)

    override suspend fun send(message: MailMessage) {
        log.info("Email to {} | subject: {}\n{}", message.to, message.subject, message.text)
    }
}

/**
 * SMTP via Jakarta Mail (Eclipse Angus). Port 465 uses implicit TLS; otherwise STARTTLS is used when the
 * server offers it and is mandatory whenever credentials are configured, so a password is never sent in clear.
 */
class SmtpMailer(
    host: String,
    port: Int,
    private val user: String?,
    private val password: String?,
    from: String,
    timeout: Duration = 15.seconds,
) : Mailer {
    private val fromAddress = InternetAddress(from, true)
    private val session: Session

    init {
        val authenticate = !user.isNullOrEmpty()
        val implicitTls = port == 465
        val ms = timeout.inWholeMilliseconds.toString()
        val props = Properties().apply {
            this["mail.transport.protocol"] = "smtp"
            this["mail.smtp.host"] = host
            this["mail.smtp.port"] = port.toString()
            this["mail.smtp.auth"] = authenticate.toString()
            this["mail.smtp.connectiontimeout"] = ms
            this["mail.smtp.timeout"] = ms
            this["mail.smtp.writetimeout"] = ms
            this["mail.smtp.ssl.enable"] = implicitTls.toString()
            this["mail.smtp.ssl.checkserveridentity"] = "true"
            this["mail.smtp.starttls.enable"] = (!implicitTls).toString()
            this["mail.smtp.starttls.required"] = (authenticate && !implicitTls).toString()
        }
        val authenticator = if (authenticate) object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, password.orEmpty())
        } else null
        session = Session.getInstance(props, authenticator)
    }

    override suspend fun send(message: MailMessage) = withContext(Dispatchers.IO) {
        val mime = MimeMessage(session).apply {
            setFrom(fromAddress)
            setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(message.to, true)))
            setSubject(message.subject, "UTF-8")
            setText(message.text, "UTF-8")
            sentDate = Date()
        }
        Transport.send(mime)
    }

    override fun toString(): String = "SmtpMailer(${session.getProperty("mail.smtp.host")}:${session.getProperty("mail.smtp.port")})"
}

/**
 * Fire-and-forget delivery with retries, off the request path. Responses therefore don't wait on (or
 * reveal through their timing) the mail server, which matters for the no-enumeration endpoints.
 * Delivery jobs are children of [parent] and are cancelled with it (the application's lifetime).
 */
class MailOutbox(
    private val mailer: Mailer,
    parent: Job?,
    private val retryDelays: List<Duration> = listOf(2.seconds, 10.seconds),
) {
    private val log = LoggerFactory.getLogger(MailOutbox::class.java)
    private val scope = CoroutineScope(SupervisorJob(parent) + Dispatchers.IO + CoroutineName("mail-outbox"))

    fun enqueue(message: MailMessage) {
        scope.launch { deliver(message) }
    }

    private suspend fun deliver(message: MailMessage) {
        for (attempt in 0..retryDelays.size) {
            try {
                mailer.send(message)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val last = attempt == retryDelays.size
                if (last) log.error("Giving up sending email to {} after {} attempts", message.to, attempt + 1, e)
                else log.warn("Sending email to {} failed (attempt {}), retrying: {}", message.to, attempt + 1, e.toString())
                if (!last) delay(retryDelays[attempt])
            }
        }
    }
}

/** The verification email. Deliberately contains no user-supplied text (e.g. the display name), so it can't be used for phishing. */
fun verificationEmail(to: String, code: String, ttl: Duration): MailMessage = MailMessage(
    to = to,
    subject = "Your Quad verification code",
    text = """
        |Welcome to Quad! Your verification code is:
        |
        |    $code
        |
        |It expires in ${ttl.inWholeMinutes} minutes. Enter it in the app to finish signing up.
        |
        |If you didn't try to create a Quad account, you can ignore this email.
        |""".trimMargin(),
)
