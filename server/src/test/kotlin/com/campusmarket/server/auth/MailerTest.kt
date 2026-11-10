package com.campusmarket.server.auth

import com.campusmarket.server.AppConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MailerTest {

    /** Minimal SMTP sink: enough of RFC 5321 for Jakarta Mail to deliver one message per connection. */
    private class FakeSmtpServer : AutoCloseable {
        data class Received(val from: String, val to: List<String>, val data: String)

        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        val received = LinkedBlockingQueue<Received>()

        init {
            thread(isDaemon = true, name = "fake-smtp") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { handle(socket) }
                }
            }
        }

        private fun handle(socket: Socket) = socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val writer = s.getOutputStream().bufferedWriter(Charsets.US_ASCII)
            fun reply(line: String) { writer.write(line + "\r\n"); writer.flush() }
            reply("220 localhost fake ESMTP")
            var from = ""
            val to = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                val cmd = line.uppercase()
                when {
                    cmd.startsWith("EHLO") -> { reply("250-localhost"); reply("250 8BITMIME") }
                    cmd.startsWith("HELO") -> reply("250 localhost")
                    cmd.startsWith("MAIL FROM:") -> { from = line.substringAfter(':').trim(); reply("250 OK") }
                    cmd.startsWith("RCPT TO:") -> { to += line.substringAfter(':').trim(); reply("250 OK") }
                    cmd == "DATA" -> {
                        reply("354 End data with <CR><LF>.<CR><LF>")
                        val data = StringBuilder()
                        while (true) {
                            val l = reader.readLine() ?: break
                            if (l == ".") break
                            data.append(if (l.startsWith("..")) l.substring(1) else l).append('\n')
                        }
                        received.put(Received(from, to.toList(), data.toString()))
                        reply("250 OK queued")
                    }
                    cmd == "QUIT" -> { reply("221 Bye"); break }
                    else -> reply("250 OK")
                }
            }
        }

        override fun close() = server.close()
    }

    @Test
    fun smtpMailerDeliversThroughAnSmtpServer() = runBlocking {
        FakeSmtpServer().use { smtp ->
            val mailer = SmtpMailer("127.0.0.1", smtp.port, null, null, "Quad <no-reply@campusmarket.test>", timeout = 5.seconds)
            mailer.send(verificationEmail("alice@state.edu", "042137", 15.minutes))
            val msg = assertNotNull(smtp.received.poll(5, TimeUnit.SECONDS))
            assertEquals("<no-reply@campusmarket.test>", msg.from)
            assertEquals(listOf("<alice@state.edu>"), msg.to)
            assertTrue("Subject: Your Quad verification code" in msg.data, msg.data)
            assertTrue("042137" in msg.data, msg.data)
            assertTrue("15 minutes" in msg.data, msg.data)
        }
    }

    @Test
    fun smtpMailerFailsWhenTheServerIsDown() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val mailer = SmtpMailer("127.0.0.1", port, null, null, "no-reply@campusmarket.test", timeout = 2.seconds)
        assertFailsWith<Exception> { mailer.send(MailMessage("a@state.edu", "s", "t")) }
        Unit
    }

    @Test
    fun mailerSelectionFollowsConfig() {
        assertIs<LogMailer>(Mailer.fromConfig(AppConfig()))
        assertIs<SmtpMailer>(Mailer.fromConfig(AppConfig(smtpHost = "smtp.example.com", smtpUser = "u", smtpPassword = "p")))
    }

    @Test
    fun outboxRetriesFailedDeliveries() = runBlocking {
        val calls = AtomicInteger()
        val delivered = LinkedBlockingQueue<MailMessage>()
        val flaky = object : Mailer {
            override suspend fun send(message: MailMessage) {
                if (calls.incrementAndGet() < 3) error("SMTP hiccup")
                delivered.put(message)
            }
        }
        val parent = Job()
        MailOutbox(flaky, parent, retryDelays = listOf(10.milliseconds, 10.milliseconds)).enqueue(MailMessage("a@state.edu", "s", "t"))
        assertEquals("a@state.edu", delivered.poll(5, TimeUnit.SECONDS)?.to)
        assertEquals(3, calls.get())
        parent.cancel()
    }

    @Test
    fun outboxGivesUpAfterTheLastRetry() = runBlocking {
        val calls = AtomicInteger()
        val broken = object : Mailer {
            override suspend fun send(message: MailMessage) {
                calls.incrementAndGet()
                error("SMTP down")
            }
        }
        val parent = Job()
        MailOutbox(broken, parent, retryDelays = listOf(10.milliseconds)).enqueue(MailMessage("a@state.edu", "s", "t"))
        kotlinx.coroutines.delay(300)
        assertEquals(2, calls.get())
        assertTrue(parent.isActive, "a failed delivery must not cancel the application scope")
        parent.cancel()
    }

    @Test
    fun settingsFromEnvironment() {
        assertEquals(AuthSettings(), AuthSettings.fromEnv(emptyMap()))
        val s = AuthSettings.fromEnv(
            mapOf(
                "BCRYPT_COST" to "10",
                "AUTH_RESEND_COOLDOWN_SECONDS" to "60",
                "AUTH_MAX_CODES_PER_DAY" to "4",
                "AUTH_RATE_LIMIT_ENABLED" to "false",
                "AUTH_RATE_LIMIT_WINDOW_SECONDS" to "30",
                "AUTH_RATE_LIMIT_LOGIN" to "7",
            ),
        )
        assertEquals(10, s.bcryptCost)
        assertEquals(60.seconds, s.resendCooldown)
        assertEquals(4, s.maxCodesPerDay)
        assertEquals(false, s.rateLimits.enabled)
        assertEquals(30.seconds, s.rateLimits.window)
        assertEquals(7, s.rateLimits.login)
        assertEquals(AuthRateLimits().register, s.rateLimits.register)
        assertFailsWith<IllegalStateException> { AuthSettings.fromEnv(mapOf("BCRYPT_COST" to "twelve")) }
        assertFailsWith<IllegalArgumentException> { AuthSettings.fromEnv(mapOf("BCRYPT_COST" to "3")) }
    }
}
