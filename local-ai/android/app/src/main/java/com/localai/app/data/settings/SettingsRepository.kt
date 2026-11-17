package com.localai.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localai.app.runtime.device.DeviceProfile
import com.localai.app.runtime.device.DeviceTier
import com.localai.app.runtime.device.TierDefaults
import com.localai.app.runtime.device.defaults
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

enum class LiteMode(val label: String) { AUTO("Automatic"), ON("Always on"), OFF("Off") }
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant running privately on the user's phone. Answer clearly and concisely."

data class AppSettings(
    val onboarded: Boolean = false,
    val activeModelId: String? = null,
    val liteMode: LiteMode = LiteMode.AUTO,
    val threads: Int = 0,                 // 0 = automatic
    val wifiOnly: Boolean = true,
    val theme: ThemeMode = ThemeMode.DARK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val showReasoning: Boolean = false,
    val kvQ8: Boolean = true,
    val digestEnabled: Boolean = false,
    val digestHour: Int = 21,
    val digestMinute: Int = 0,
    val digestEmail: String = "",
    val digestIncludeSms: Boolean = true,
    val folderSync: Boolean = true,
    val voiceLanguage: String = "",       // BCP-47 tag, "" = phone language
    val speechRate: Float = 1.0f,
    val readAloud: Boolean = false,       // speak every reply in normal chat
    val ocrLanguages: String = "",
    val bargeIn: String = "NORMAL",
    val ttsVoice: String = "",            // natural voice id (NaturalVoices), "" = the phone's text-to-speech voice
    val ttsSpeaker: Int = 0,              // speaker within a multi-speaker voice       // interrupt the assistant by talking: OFF | NORMAL | SENSITIVE        // extra text-recognition languages (csv of tessdata codes); English is always on
)

data class ModelProfile(val contextLength: Int, val temperature: Float, val topP: Float)

/** Effective runtime settings: what lite mode actually changes. */
data class RuntimePolicy(val lite: Boolean, val tier: DeviceTier, val defaults: TierDefaults, val threads: Int)

fun policyFor(settings: AppSettings, device: DeviceProfile): RuntimePolicy {
    val lite = when (settings.liteMode) { LiteMode.ON -> true; LiteMode.OFF -> false; LiteMode.AUTO -> device.tier.isLite }
    // Lite mode forced on a big phone uses the 6 GB lite profile; turned off on a small phone uses 8 GB defaults
    // with the budget still enforced by the verdict.
    val tierForDefaults = when {
        lite && !device.tier.isLite -> DeviceTier.LITE_6
        !lite && device.tier.isLite -> DeviceTier.STANDARD_8
        else -> device.tier
    }
    val d = tierForDefaults.defaults()
    val auto = minOf(device.performanceCores, d.maxThreads).coerceAtLeast(2)
    return RuntimePolicy(lite, device.tier, d, if (settings.threads > 0) settings.threads else auto)
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val active = stringPreferencesKey("active_model")
        val lite = stringPreferencesKey("lite_mode")
        val threads = intPreferencesKey("threads")
        val wifi = booleanPreferencesKey("wifi_only")
        val theme = stringPreferencesKey("theme")
        val system = stringPreferencesKey("system_prompt")
        val reasoning = booleanPreferencesKey("show_reasoning")
        val kvq8 = booleanPreferencesKey("kv_q8")
        val digestOn = booleanPreferencesKey("digest_on")
        val digestHour = intPreferencesKey("digest_hour")
        val digestMinute = intPreferencesKey("digest_minute")
        val digestEmail = stringPreferencesKey("digest_email")
        val digestSms = booleanPreferencesKey("digest_sms")
        val folderSync = booleanPreferencesKey("folder_sync")
        val voiceLang = stringPreferencesKey("voice_lang")
        val speechRate = floatPreferencesKey("speech_rate")
        val readAloud = booleanPreferencesKey("read_aloud")
        val ocrLangs = stringPreferencesKey("ocr_langs")
        val bargeIn = stringPreferencesKey("barge_in")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsSpeaker = intPreferencesKey("tts_speaker")
        fun ctx(id: String) = intPreferencesKey("ctx_$id")
        fun temp(id: String) = floatPreferencesKey("temp_$id")
        fun topP(id: String) = floatPreferencesKey("topp_$id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboarded = p[K.onboarded] ?: false,
            activeModelId = p[K.active],
            liteMode = p[K.lite]?.let { runCatching { LiteMode.valueOf(it) }.getOrNull() } ?: LiteMode.AUTO,
            threads = p[K.threads] ?: 0,
            wifiOnly = p[K.wifi] ?: true,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            systemPrompt = p[K.system] ?: DEFAULT_SYSTEM_PROMPT,
            showReasoning = p[K.reasoning] ?: false,
            kvQ8 = p[K.kvq8] ?: true,
            digestEnabled = p[K.digestOn] ?: false,
            digestHour = p[K.digestHour] ?: 21,
            digestMinute = p[K.digestMinute] ?: 0,
            digestEmail = p[K.digestEmail] ?: "",
            digestIncludeSms = p[K.digestSms] ?: true,
            folderSync = p[K.folderSync] ?: true,
            voiceLanguage = p[K.voiceLang] ?: "",
            speechRate = p[K.speechRate] ?: 1.0f,
            readAloud = p[K.readAloud] ?: false,
            ocrLanguages = p[K.ocrLangs] ?: "",
            bargeIn = p[K.bargeIn] ?: "NORMAL",
            ttsVoice = p[K.ttsVoice] ?: "",
            ttsSpeaker = p[K.ttsSpeaker] ?: 0,
        )
    }

    suspend fun current() = settings.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) { context.dataStore.edit { block(it) } }
    suspend fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }
    suspend fun setActiveModel(id: String?) = edit { if (id == null) it.remove(K.active) else it[K.active] = id }
    suspend fun setLiteMode(m: LiteMode) = edit { it[K.lite] = m.name }
    suspend fun setThreads(n: Int) = edit { it[K.threads] = n }
    suspend fun setWifiOnly(v: Boolean) = edit { it[K.wifi] = v }
    suspend fun setTheme(t: ThemeMode) = edit { it[K.theme] = t.name }
    suspend fun setSystemPrompt(s: String) = edit { it[K.system] = s }
    suspend fun setShowReasoning(v: Boolean) = edit { it[K.reasoning] = v }
    suspend fun setKvQ8(v: Boolean) = edit { it[K.kvq8] = v }
    suspend fun setDigest(on: Boolean, hour: Int, minute: Int, email: String, sms: Boolean) = edit {
        it[K.digestOn] = on; it[K.digestHour] = hour; it[K.digestMinute] = minute; it[K.digestEmail] = email.trim(); it[K.digestSms] = sms
    }
    suspend fun setFolderSync(v: Boolean) = edit { it[K.folderSync] = v }
    suspend fun setBargeIn(v: String) = edit { it[K.bargeIn] = v }
    suspend fun setTtsVoice(id: String, speaker: Int = 0) = edit { it[K.ttsVoice] = id; it[K.ttsSpeaker] = speaker }
    suspend fun setOcrLanguages(codes: Collection<String>) = edit { it[K.ocrLangs] = codes.filter { c -> c != "eng" }.distinct().joinToString(",") }
    suspend fun setVoice(language: String, rate: Float, readAloud: Boolean) = edit {
        it[K.voiceLang] = language; it[K.speechRate] = rate.coerceIn(0.5f, 2f); it[K.readAloud] = readAloud
    }

    fun profile(id: String, defaultCtx: Int): Flow<ModelProfile> = context.dataStore.data.map { p -> readProfile(p, id, defaultCtx) }
    suspend fun profileNow(id: String, defaultCtx: Int) = readProfile(context.dataStore.data.first(), id, defaultCtx)
    private fun readProfile(p: Preferences, id: String, defaultCtx: Int) =
        ModelProfile(p[K.ctx(id)] ?: defaultCtx, p[K.temp(id)] ?: 0.7f, p[K.topP(id)] ?: 0.9f)
    suspend fun saveProfile(id: String, profile: ModelProfile) = edit {
        it[K.ctx(id)] = profile.contextLength; it[K.temp(id)] = profile.temperature; it[K.topP(id)] = profile.topP
    }
    suspend fun resetProfile(id: String) = edit { it.remove(K.ctx(id)); it.remove(K.temp(id)); it.remove(K.topP(id)) }
    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
