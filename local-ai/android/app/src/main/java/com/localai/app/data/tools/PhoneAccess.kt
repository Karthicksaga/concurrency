package com.localai.app.data.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Sms(val from: String, val body: String, val date: Long)

/**
 * Read-only access to SMS and contacts, used by tools and the daily digest. Everything stays on the phone.
 * Note: Google Play only allows READ_SMS for default SMS apps; this works in sideloaded builds.
 */
object PhoneAccess {
    fun has(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun canReadSms(context: Context) = has(context, Manifest.permission.READ_SMS)
    fun canReadContacts(context: Context) = has(context, Manifest.permission.READ_CONTACTS)

    fun readSms(context: Context, sinceMs: Long, keyword: String?, limit: Int = 40): List<Sms> {
        if (!canReadSms(context)) return emptyList()
        val out = ArrayList<Sms>()
        val sel = StringBuilder("${Telephony.Sms.DATE} >= ?"); val args = arrayListOf(sinceMs.toString())
        if (!keyword.isNullOrBlank()) { sel.append(" AND (${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)"); args += "%$keyword%"; args += "%$keyword%" }
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            sel.toString(), args.toTypedArray(), "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out += Sms(c.getString(0) ?: "?", (c.getString(1) ?: "").take(500), c.getLong(2))
        }
        return out
    }

    fun format(list: List<Sms>): String {
        val f = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        return list.joinToString("\n") { "${f.format(Date(it.date))} · ${it.from}: ${it.body.replace('\n', ' ')}" }
    }

    /** Returns (name, number) for a contact whose name contains [query], if contacts permission was granted. */
    fun findContact(context: Context, query: String): Pair<String, String>? {
        if (!canReadContacts(context)) return null
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: query) to (c.getString(1) ?: return null)
        }
        return null
    }

    /** Opens the dialer with the number filled in. The user still presses Call. */
    fun openDialer(context: Context, number: String) {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
