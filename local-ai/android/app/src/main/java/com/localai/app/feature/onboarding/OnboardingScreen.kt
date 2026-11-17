package com.localai.app.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.Eyebrow
import com.localai.app.core.designsystem.KeyValue
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.VerdictPill
import com.localai.app.core.designsystem.fmtBytes
import com.localai.app.core.designsystem.fmtGiB
import com.localai.app.data.catalog.Verdict
import com.localai.app.data.downloads.DownloadManager
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.feature.models.ModelsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val settings: SettingsRepository, private val downloads: DownloadManager) : ViewModel() {
    fun finish(downloadId: String?, onDone: () -> Unit) = viewModelScope.launch {
        if (downloadId != null) com.localai.app.data.catalog.Catalog.byId(downloadId)?.let { downloads.start(it); settings.setActiveModel(it.id) }
        settings.setOnboarded(true)
        onDone()
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel(), models: ModelsViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val ui by models.ui.collectAsStateWithLifecycle()
    val dev = models.device
    val policy = ui.policy
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                0 -> {
                    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(28.dp)).background(La.colors.surface1), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(104.dp).clip(RoundedCornerShape(26.dp)).background(La.colors.surface3), contentAlignment = Alignment.Center) {
                            Text("LOCAL·AI", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = La.colors.accentStrong)
                        }
                    }
                    Eyebrow("Personal AI, on device")
                    Text("Your models and chats. On your phone.", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 34.sp))
                    Text("Run open-source language models locally. No account, no cloud, no analytics.", color = La.colors.text2, fontSize = 15.sp)
                    listOf(Icons.Outlined.WifiOff to "Works offline once a model is downloaded",
                        Icons.Outlined.Lock to "Chats are stored only on this phone",
                        Icons.Outlined.Memory to "The internet is used only to download models").forEach { (ic, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(ic, null, tint = La.colors.ok); Spacer(Modifier.width(12.dp)); Text(t, fontSize = 14.sp) }
                    }
                }
                1 -> {
                    Eyebrow("Device check")
                    Text("What this phone can run", style = MaterialTheme.typography.headlineSmall)
                    LaCard {
                        KeyValue("Phone", "${dev.manufacturer} ${dev.model}")
                        KeyValue("Memory", "${"%.1f".format(dev.totalRamGiB)} GB total · ${"%.1f".format(dev.availRamGiB)} GB free")
                        KeyValue("Processor", dev.socModel.ifBlank { "Unknown" })
                        KeyValue("Cores", "${dev.cpuCores} (${dev.performanceCores} performance)")
                        KeyValue("CPU features", dev.cpuFeatures.joinToString(" · ").ifBlank { "baseline" })
                        KeyValue("Android", "${dev.androidVersion} (API ${dev.sdkInt})")
                        KeyValue("Free storage", fmtBytes(dev.freeStorageBytes))
                        KeyValue("Model budget", fmtGiB(dev.modelBudgetGiB))
                        KeyValue("Class", dev.tier.label)
                    }
                    if (!dev.is64Bit) Banner("This phone is not 64-bit ARM. LOCAL AI needs an arm64 device.", Tone.BAD)
                    if (dev.tier.isLite) Banner("Lite mode will be on: small models (≈1–2B), a 2K context, smaller processing batches and early memory release keep the app stable on ${dev.tier.label.substringBefore(" ·")} phones. Answers are shorter and simpler than on larger phones.", Tone.RUN)
                    else Banner("This phone runs ${if (dev.totalRamGiB > 10) "4B models comfortably and 7–8B models with care" else "3B models well and 4B models as a tight fit"}.", Tone.OK)
                }
                else -> {
                    Eyebrow("First model")
                    Text("Pick a starter model", style = MaterialTheme.typography.headlineSmall)
                    Text("Recommended for your ${dev.tier.label} phone. You can add more later.", color = La.colors.text2)
                    val options = ui.rows.filter { !it.spec.id.startsWith("import") && it.verdict == Verdict.RUNS_WELL }
                        .sortedWith(compareByDescending<com.localai.app.feature.models.ModelRow> { it.recommended }.thenBy { it.spec.sizeBytes }).take(4)
                    if (pick == null) pick = options.firstOrNull()?.spec?.id
                    options.forEach { r ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (pick == r.spec.id) La.colors.accentSoft else La.colors.surface1)
                            .selectable(selected = pick == r.spec.id) { pick = r.spec.id }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pick == r.spec.id, onClick = { pick = r.spec.id })
                            Column(Modifier.weight(1f)) {
                                Text(r.spec.name + if (r.recommended) " · recommended" else "", fontWeight = FontWeight.SemiBold)
                                Text("${fmtBytes(r.spec.sizeBytes)} download · ${r.spec.license}", fontSize = 12.sp, color = La.colors.text2)
                            }
                            VerdictPill(r.verdict)
                        }
                    }
                    Text("Downloads run on Wi-Fi by default and are checked against a SHA-256 hash.", fontSize = 12.sp, color = La.colors.text3)
                    if (policy == null) Text("Checking…")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${page + 1} / 3", color = La.colors.text3, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.finish(null, onDone) }) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (page < 2) page++ else {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    vm.finish(pick, onDone)
                }
            }) { Text(if (page < 2) "Continue" else "Download and start") }
        }
    }
}
