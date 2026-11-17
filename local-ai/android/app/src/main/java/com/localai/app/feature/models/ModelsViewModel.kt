package com.localai.app.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.catalog.MemoryModel
import com.localai.app.data.catalog.ModelSpec
import com.localai.app.data.catalog.Verdict
import com.localai.app.data.downloads.DownloadManager
import com.localai.app.data.downloads.DownloadUi
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.AppSettings
import com.localai.app.data.settings.RuntimePolicy
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.device.DeviceProfile
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.LlamaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelRow(
    val spec: ModelSpec,
    val installed: Boolean,
    val download: DownloadUi?,
    val verdict: Verdict,
    val estimateGiB: Double,
    val active: Boolean,
    val loaded: Boolean,
    val recommended: Boolean,
)

data class ModelsUi(
    val rows: List<ModelRow> = emptyList(),
    val device: DeviceProfile? = null,
    val policy: RuntimePolicy? = null,
    val settings: AppSettings = AppSettings(),
    val engine: EngineState = EngineState.Idle,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val repo: ModelRepository,
    private val downloads: DownloadManager,
    private val settingsRepo: SettingsRepository,
    private val engine: LlamaEngine,
    private val deviceService: DeviceCapabilityService,
) : ViewModel() {
    val device = deviceService.profile
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _importing = MutableStateFlow<Float?>(null)
    val importing: StateFlow<Float?> = _importing.asStateFlow()

    val ui: StateFlow<ModelsUi> = combine(repo.installed, downloads.downloads, settingsRepo.settings, engine.state) { installed, dls, s, eng ->
        val policy = policyFor(s, device)
        val installedIds = installed.associateBy { it.id }
        val specs = installed.filter { it.source == "IMPORT" }.map { it.toSpec() } + Catalog.models
        val rows = specs.map { spec ->
            val ctx = minOf(policy.defaults.contextLength, spec.ctxTrain)
            val est = MemoryModel.estimate(spec, ctx, s.kvQ8, policy.defaults.batchSize).totalGiB
            ModelRow(
                spec = spec, installed = installedIds.containsKey(spec.id), download = dls[spec.id],
                verdict = MemoryModel.verdict(spec, ctx, s.kvQ8, device, policy.defaults.batchSize), estimateGiB = est,
                active = s.activeModelId == spec.id, loaded = (eng as? EngineState.Ready)?.modelId == spec.id,
                recommended = spec.id == policy.defaults.recommendedModelId,
            )
        }
        ModelsUi(rows, device, policy, s, eng)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelsUi(device = device))

    fun consumeMessage() { _message.value = null }

    fun download(spec: ModelSpec) = viewModelScope.launch {
        val need = (spec.sizeBytes - repo.partFileFor(spec.fileName).let { if (it.exists()) it.length() else 0L }) + 512L * 1024 * 1024
        if (deviceService.freeStorage() < need) { _message.value = "Not enough free storage. Free about ${need / (1 shl 20)} MB first."; return@launch }
        downloads.start(spec)
        if (settingsRepo.current().wifiOnly) _message.value = "Download queued. It runs on Wi-Fi (change this in Settings)."
    }
    fun pause(id: String) = downloads.pause(id)
    fun cancel(id: String) = downloads.cancel(id)

    fun activate(id: String, force: Boolean = false) = viewModelScope.launch {
        repo.activate(id, force).onFailure { _message.value = it.message }.onSuccess { _message.value = "Model loaded and ready." }
    }
    fun unload() = viewModelScope.launch { engine.unload() }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id); _message.value = "Model deleted." }

    fun import(uri: Uri) = viewModelScope.launch {
        _importing.value = 0f
        val r = repo.importGguf(uri) { p -> _importing.value = if (p.total > 0) p.bytes.toFloat() / p.total else -1f }
        _importing.value = null
        _message.value = r.fold({ "Model imported. Open it to load." }, { "Import failed: ${it.message}" })
    }

    fun profile(id: String, defaultCtx: Int) = settingsRepo.profile(id, defaultCtx)
    fun saveProfile(id: String, p: com.localai.app.data.settings.ModelProfile) = viewModelScope.launch {
        settingsRepo.saveProfile(id, p)
        _message.value = if ((engine.state.value as? EngineState.Ready)?.modelId == id) "Saved. Reload the model to apply the new context length." else "Saved."
    }
    fun resetProfile(id: String) = viewModelScope.launch { settingsRepo.resetProfile(id) }
}
