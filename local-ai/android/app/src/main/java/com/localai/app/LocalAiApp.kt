package com.localai.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.LlamaEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LocalAiApp : Application() {
    @Inject lateinit var engine: LlamaEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var device: DeviceCapabilityService
    @Inject lateinit var speech: com.localai.app.data.voice.SpeechOutput

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // The ":ocr" helper process only runs text recognition: no channels, schedules or crash dialogs there.
        if (processName().endsWith(":ocr")) return
        CrashReport.install(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Model downloads and background work", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "Daily digest", NotificationManager.IMPORTANCE_DEFAULT))
        // Keep background jobs (folder sync, daily digest) in line with the settings.
        scope.launch { settings.settings.collect { com.localai.app.data.automation.Automation.schedule(this@LocalAiApp, it) } }
        // Be a good citizen on small phones: release the model soon after the app leaves the screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                unloadJob = scope.launch {
                    val delayMs = policyFor(settings.current(), device.profile).defaults.unloadAfterBackgroundMs
                    delay(delayMs)
                    while (engine.generating.value) delay(5_000)
                    if (engine.state.value is EngineState.Ready) engine.unload()
                }
            }
            override fun onStart(owner: LifecycleOwner) { unloadJob?.cancel() }
        })
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(packageName)

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (processName().endsWith(":ocr")) return
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE && !engine.generating.value) scope.launch { engine.unload() }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !speech.speaking.value) speech.releaseVoice()
    }

    companion object { const val CHANNEL_DOWNLOADS = "downloads"; const val CHANNEL_DIGEST = "digest" }
}
