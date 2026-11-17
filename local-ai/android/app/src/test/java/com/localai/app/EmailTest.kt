package com.localai.app

import com.localai.app.data.email.EmailConfig
import com.localai.app.data.email.Security
import com.localai.app.data.email.SmtpSender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Exercises the real JavaMail sender against a minimal in-process SMTP server (no TLS, no auth: tests only). */
class EmailTest {
    private fun fakeSmtp(): Pair<Int, () -> String> {
        val server = ServerSocket(0)
        val received = StringBuilder()
        val t = thread {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader(); val w = s.getOutputStream().bufferedWriter()
                fun say(x: String) { w.write(x + "\r\n"); w.flush() }
                say("220 localhost ESMTP")
                var inData = false
                while (true) {
                    val line = r.readLine() ?: break
                    if (inData) { if (line == ".") { inData = false; say("250 OK queued") } else received.append(line).append('\n'); continue }
                    when {
                        line.startsWith("EHLO") || line.startsWith("HELO") -> { say("250-localhost"); say("250 OK") }
                        line.startsWith("MAIL") || line.startsWith("RCPT") -> { received.append(line).append('\n'); say("250 OK") }
                        line == "DATA" -> { inData = true; say("354 End data with <CR><LF>.<CR><LF>") }
                        line == "QUIT" -> { say("221 Bye"); break }
                        else -> say("250 OK")
                    }
                }
            }
            server.close()
        }
        return server.localPort to { t.join(5000); received.toString() }
    }

    @Test fun sendsDigestThroughSmtp() = runBlocking {
        val (port, result) = fakeSmtp()
        val cfg = EmailConfig(true, "custom", "127.0.0.1", port, Security.NONE, "me@example.com", true, "me@example.com")
        val err = SmtpSender.send(cfg, null, "Daily digest · Tue 17 Nov", "• Spent ₹1,250 at Swiggy\n• OTP alert from HDFC")
        assertNull(err)
        val data = result()
        assertTrue(data.contains("RCPT TO:<me@example.com>"))
        assertTrue(data.contains("Subject: =?UTF-8?") || data.contains("Subject: Daily digest"))
        assertTrue(data.contains("Swiggy") || data.contains("charset=UTF-8"))
    }

    @Test fun reportsUnreachableServer() = runBlocking {
        val free = ServerSocket(0).use { it.localPort }   // nothing listening there now
        val err = SmtpSender.send(EmailConfig(true, "custom", "127.0.0.1", free, Security.NONE, "me@example.com", true, "me@example.com"), null, "x", "y")
        assertNotNull(err)
        assertEquals(true, err!!.isNotBlank())
    }
}
