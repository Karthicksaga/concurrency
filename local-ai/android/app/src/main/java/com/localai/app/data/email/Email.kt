package com.localai.app.data.email

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Date
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

enum class Security(val label: String) { SSL("SSL/TLS"), STARTTLS("STARTTLS"), NONE("None (tests only)") }

data class SmtpPreset(val id: String, val name: String, val host: String, val port: Int, val security: Security, val help: String)

val SMTP_PRESETS = listOf(
    SmtpPreset("gmail", "Gmail", "smtp.gmail.com", 465, Security.SSL,
        "Turn on 2-Step Verification, then create an app password at myaccount.google.com › Security › App passwords. Use that 16-letter password here, not your normal password."),
    SmtpPreset("outlook", "Outlook / Hotmail", "smtp-mail.outlook.com", 587, Security.STARTTLS,
        "Use an app password from account.microsoft.com › Security if 2-step verification is on. Some Microsoft accounts have SMTP sign-in turned off."),
    SmtpPreset("yahoo", "Yahoo", "smtp.mail.yahoo.com", 465, Security.SSL, "Create an app password in Yahoo Account Security."),
    SmtpPreset("zoho", "Zoho", "smtp.zoho.in", 465, Security.SSL, "Use smtp.zoho.com outside India. Create an app-specific password in Zoho Accounts."),
    SmtpPreset("custom", "Other provider", "", 465, Security.SSL, "Ask your email provider for its SMTP server, port and security."),
)

data class EmailConfig(
    val enabled: Boolean = false,
    val presetId: String = "gmail",
    val host: String = "smtp.gmail.com",
    val port: Int = 465,
    val security: Security = Security.SSL,
    val username: String = "",
    val hasPassword: Boolean = false,
    val to: String = "",
) {
    val ready get() = host.isNotBlank() && username.isNotBlank() && hasPassword && to.isNotBlank()
}

private val Context.emailStore by preferencesDataStore("email")

/** Encrypts secrets with a non-exportable AES key held by Android Keystore. */
object SecretBox {
    private const val ALIAS = "localai-email-key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun open(sealed: String): String? = runCatching {
        val (iv, data) = sealed.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(data))
    }.getOrNull()
}

/**
 * Optional automatic sending of the daily digest through the user's own mail server (ADR-0014).
 * The phone connects only to the SMTP host the user configured, only to send what the user enabled.
 */
@Singleton
class EmailService @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled"); val preset = stringPreferencesKey("preset"); val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port"); val security = stringPreferencesKey("security"); val user = stringPreferencesKey("user")
        val secret = stringPreferencesKey("secret"); val to = stringPreferencesKey("to")
    }

    val config: Flow<EmailConfig> = context.emailStore.data.map { p ->
        EmailConfig(p[K.enabled] ?: false, p[K.preset] ?: "gmail", p[K.host] ?: "smtp.gmail.com", p[K.port] ?: 465,
            p[K.security]?.let { runCatching { Security.valueOf(it) }.getOrNull() } ?: Security.SSL, p[K.user] ?: "", p[K.secret] != null, p[K.to] ?: "")
    }

    suspend fun save(c: EmailConfig, newPassword: String?) = context.emailStore.edit { p ->
        p[K.enabled] = c.enabled; p[K.preset] = c.presetId; p[K.host] = c.host.trim(); p[K.port] = c.port; p[K.security] = c.security.name
        p[K.user] = c.username.trim(); p[K.to] = c.to.trim()
        if (!newPassword.isNullOrBlank()) p[K.secret] = SecretBox.seal(newPassword.replace(" ", ""))
    }

    suspend fun forget() = context.emailStore.edit { it.clear() }

    /** Sends [subject]/[body] to the configured recipient. Returns null on success or an error message. */
    suspend fun send(subject: String, body: String): String? {
        val c = config.first()
        if (!c.ready) return "Email sending is not set up."
        val secret = context.emailStore.data.first()[K.secret]?.let { SecretBox.open(it) } ?: return "Saved password could not be read. Enter it again."
        return SmtpSender.send(c, secret, subject, body)
    }
}

/** Plain JavaMail sending; no Android types so it can be unit-tested against a local SMTP server. */
object SmtpSender {
    suspend fun send(c: EmailConfig, password: String?, subject: String, body: String): String? = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.host", c.host); put("mail.smtp.port", c.port.toString())
            put("mail.smtp.connectiontimeout", "20000"); put("mail.smtp.timeout", "20000"); put("mail.smtp.writetimeout", "20000")
            put("mail.smtp.auth", (password != null).toString())
            when (c.security) {
                Security.SSL -> { put("mail.smtp.ssl.enable", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
                Security.STARTTLS -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
                Security.NONE -> Unit
            }
        }
        val session = if (password != null) Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(c.username, password)
        }) else Session.getInstance(props)
        runCatching {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(c.username, "LOCAL AI"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(c.to, true))
                setSubject(subject, "UTF-8"); setText(body, "UTF-8"); sentDate = Date()
            }
            Transport.send(msg)
            null
        }.getOrElse { e ->
            val m = e.message ?: e.javaClass.simpleName
            when {
                m.contains("535") || m.contains("Authentication", true) -> "Sign-in failed. Check the email address and use an app password."
                m.contains("UnknownHost", true) || e is java.net.UnknownHostException -> "Could not reach ${c.host}. Check the internet connection and server name."
                else -> "Could not send: ${m.take(160)}"
            }
        }
    }
}
