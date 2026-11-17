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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Model downloads", NotificationManager.IMPORTANCE_LOW))
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

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE && !engine.generating.value) scope.launch { engine.unload() }
    }

    companion object { const val CHANNEL_DOWNLOADS = "downloads" }
}
