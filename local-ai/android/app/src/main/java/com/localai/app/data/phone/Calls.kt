package com.localai.app.data.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.localai.app.data.db.PeopleDao
import com.localai.app.data.db.PersonEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Names for family members in English, Tanglish, Tamil and Hindi, so "Amma", "அம்மா", "माँ" and "mom" are the same
 * person. Pure, unit-tested.
 */
object Kin {
    private val groups = listOf(
        setOf("amma", "ammaa", "mom", "mother", "mummy", "mum", "maa", "ma", "அம்மா", "अम्मा", "माँ", "मां", "मम्मी"),
        setOf("appa", "dad", "father", "daddy", "papa", "pappa", "அப்பா", "पापा", "पिताजी"),
        setOf("anna", "annan", "elder brother", "அண்ணா", "அண்ணன்", "भैया", "भाई"),
        setOf("akka", "elder sister", "அக்கா", "दीदी"),
        setOf("thambi", "younger brother", "தம்பி"),
        setOf("thangachi", "thangai", "younger sister", "தங்கச்சி", "தங்கை"),
        setOf("paati", "patti", "grandma", "grandmother", "ammamma", "aaya", "பாட்டி", "दादी", "नानी"),
        setOf("thatha", "grandpa", "grandfather", "தாத்தா", "दादा", "नाना"),
        setOf("wife", "manaivi", "மனைவி", "पत्नी"),
        setOf("husband", "kanavar", "கணவர்", "पति"),
    )

    /** A stable key for matching: the kin group's first name, or the cleaned word itself. */
    fun key(name: String): String {
        val n = name.trim().lowercase().removePrefix("my ").removePrefix("en ").removePrefix("என் ")
            .trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")
        return groups.firstOrNull { n in it }?.first() ?: n
    }
}

data class PendingCall(val name: String, val number: String, val secondsLeft: Int, val direct: Boolean)

/**
 * "Call Amma": resolves names from People (picked once from the system contact picker, no contacts permission),
 * then calls after a short cancellable countdown. Without the "Phone calls" permission it opens the dialer with
 * the number ready (one tap). The countdown also protects against a misheard voice command.
 */
@Singleton
class CallManager @Inject constructor(@ApplicationContext private val context: Context, private val people: PeopleDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("calls", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow<PendingCall?>(null)
    val pending: StateFlow<PendingCall?> = _pending.asStateFlow()
    /** A name we don't know yet: the screen asks "Who is …?" and opens the contact picker. */
    private val _askWho = MutableStateFlow<String?>(null)
    val askWho: StateFlow<String?> = _askWho.asStateFlow()
    private var countdown: Job? = null

    var callDirectly: Boolean
        get() = prefs.getBoolean("direct", false)
        set(v) { prefs.edit().putBoolean("direct", v).apply() }
    val canCallDirectly get() = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    suspend fun find(name: String): PersonEntity? { val k = Kin.key(name); return people.all().firstOrNull { Kin.key(it.alias) == k } }

    /** Starts a call to [target] (a name from People or a number). Returns what happened, for the chat. */
    suspend fun call(target: String): String {
        val digits = target.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5 && digits.length >= target.count { it.isLetterOrDigit() } / 2) {
            start(target.trim(), digits); return "Calling $target."
        }
        val p = find(target)
        if (p == null) { _askWho.value = target.trim().replaceFirstChar { it.uppercase() }; return "Asked the user to choose ${target.trim()} from contacts; the call starts once they pick." }
        start(p.alias, p.number)
        return "Calling ${p.alias} (${p.displayName}, ${p.number})."
    }

    private fun start(name: String, number: String) {
        countdown?.cancel()
        val direct = callDirectly && canCallDirectly
        countdown = scope.launch {
            for (s in 3 downTo 1) { _pending.value = PendingCall(name, number, s, direct); delay(1000) }
            _pending.value = null
            place(number, direct)
        }
    }

    fun cancel() { countdown?.cancel(); _pending.value = null }
    fun callNow() { val p = _pending.value ?: return; countdown?.cancel(); _pending.value = null; place(p.number, p.direct) }
    fun dismissAsk() { _askWho.value = null }

    private fun place(number: String, direct: Boolean) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Saves the contact the user picked (a phone row from the system picker, readable once without permission). */
    suspend fun savePicked(alias: String, picked: Uri, thenCall: Boolean): Boolean = withContext(Dispatchers.IO) {
        val row = runCatching {
            context.contentResolver.query(picked, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: alias) to (c.getString(1) ?: "") else null }
        }.getOrNull() ?: return@withContext false
        if (row.second.isBlank()) return@withContext false
        people.upsert(PersonEntity(alias.trim().replaceFirstChar { it.uppercase() }, row.first, row.second, System.currentTimeMillis()))
        _askWho.value = null
        if (thenCall) withContext(Dispatchers.Main) { start(alias.trim().replaceFirstChar { it.uppercase() }, row.second) }
        true
    }

    /** Intent for the system contact picker, limited to phone numbers (no contacts permission needed). */
    fun pickIntent(): Intent = Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
}
