package com.localai.app.feature.helpers

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.ModelLoadingMark
import com.localai.app.core.designsystem.Tone
import com.localai.app.data.assist.Helpers
import com.localai.app.data.assist.SharedInbox
import com.localai.app.data.assist.SharedItem
import com.localai.app.data.assist.languageName
import com.localai.app.data.media.Images
import com.localai.app.data.safety.ScamCheck
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.voice.Speakable
import com.localai.app.data.voice.SpeechOutput
import com.localai.app.feature.settings.VOICE_LANGUAGES
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Shared state for both helpers: photo intake (camera, gallery, share), language and read-aloud. */
abstract class HelperBase(
    protected val context: Context, protected val helpers: Helpers, protected val settings: SettingsRepository,
    protected val speech: SpeechOutput, protected val inbox: SharedInbox,
) : ViewModel() {
    var language by mutableStateOf("")
    var busy by mutableStateOf<String?>(null); protected set
    var error by mutableStateOf<String?>(null)
    var explanation by mutableStateOf("")
    var photo by mutableStateOf<String?>(null); protected set
    val speakingId = speech.speakingId
    protected var job: Job? = null
    private var cameraFile: File? = null

    init { viewModelScope.launch { language = settings.current().voiceLanguage } }

    fun cameraUri(): Uri = Images.cameraTarget(context).let { (f, u) -> cameraFile = f; u }

    /** Stores the photo, then runs [onText] with the recognised text. */
    fun takePhoto(uri: Uri) = viewModelScope.launch {
        error = null; busy = "Reading the text…"
        runCatching { Images.import(context, uri) }.onSuccess { f -> photo?.let { File(it).delete() }; photo = f.path; readStored(f) }
            .onFailure { error = it.message ?: "Could not open that image."; busy = null }
        cameraFile?.delete(); cameraFile = null
    }

    protected suspend fun readStored(f: File) {
        busy = "Reading the text…"
        val r = runCatching { helpers.readPhoto(f) }.getOrNull()
        busy = null
        if (r == null || r.text.count { it.isLetterOrDigit() } < 6) { error = "No readable text found. Try a sharper photo, taken straight on, in good light."; return }
        onText(r.text)
    }

    protected abstract fun onText(text: String)

    fun speak(id: String, text: String, tag: String = language) = viewModelScope.launch {
        if (speakingId.value == id) { speech.stop(); return@launch }
        speech.stop()
        val rate = settings.current().speechRate
        if (!speech.enqueue(Speakable.clean(text), tag, rate, id)) error = "No text-to-speech voice is installed. Add one in Android settings › Text-to-speech."
    }

    fun stop() { job?.cancel(); speech.stop(); busy = null }

    override fun onCleared() { speech.stop(); photo?.let { File(it).delete() } }
}

@HiltViewModel
class ScamViewModel @Inject constructor(
    @ApplicationContext context: Context, helpers: Helpers, settings: SettingsRepository, speech: SpeechOutput, inbox: SharedInbox,
) : HelperBase(context, helpers, settings, speech, inbox) {
    var input by mutableStateOf("")
    var report by mutableStateOf<ScamCheck.Report?>(null); private set
    var noModel by mutableStateOf(false); private set

    init {
        when (val s = inbox.take()) {
            is SharedItem.Text -> { input = s.text; check() }
            is SharedItem.Photo -> viewModelScope.launch { photo = s.path; readStored(File(s.path)) }
            null -> Unit
        }
    }

    override fun onText(text: String) { input = text; check() }

    fun paste() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (t.isBlank()) error = "The clipboard is empty. Copy the message first (long-press it in your messages app)." else { input = t; check() }
    }

    fun check() {
        val text = input.trim()
        if (text.length < 8) { error = "Paste or type the message, or add a screenshot."; return }
        error = null; explanation = ""; speech.stop()
        val r = ScamCheck.analyze(text); report = r
        job?.cancel()
        job = viewModelScope.launch {
            if (!helpers.hasModel()) { noModel = true; return@launch }
            noModel = false
            busy = "Explaining in ${languageName(language)}…"
            try { explanation = helpers.explainScam(text, r, language) { explanation = it } }
            catch (e: CancellationException) { }
            catch (e: Exception) { error = "Explanation unavailable: ${e.message}" }
            finally { busy = null }
        }
    }

    fun clear() { stop(); input = ""; report = null; explanation = ""; error = null; photo?.let { File(it).delete() }; photo = null }
}

@HiltViewModel
class ReadViewModel @Inject constructor(
    @ApplicationContext context: Context, helpers: Helpers, settings: SettingsRepository, speech: SpeechOutput, inbox: SharedInbox,
) : HelperBase(context, helpers, settings, speech, inbox) {
    var text by mutableStateOf("")
    var large by mutableStateOf(true)

    init { (inbox.take() as? SharedItem.Photo)?.let { s -> viewModelScope.launch { photo = s.path; readStored(File(s.path)) } } }

    override fun onText(text: String) { this.text = text; explanation = ""; viewModelScope.launch { if (helpers.hasModel()) explain() } }

    fun readAsWritten() = speak("raw", text, helpers.speechTagFor(text))

    fun explain() {
        if (text.isBlank()) return
        job?.cancel(); speech.stop(); explanation = ""; error = null
        job = viewModelScope.launch {
            if (!helpers.hasModel()) { error = "Explaining needs a chat model (Models tab). Reading aloud works without one."; return@launch }
            busy = "Explaining in ${languageName(language)}…"
            try {
                explanation = helpers.explainDocument(text, language) { explanation = it }
                speak("explain", explanation)
            } catch (e: CancellationException) { }
            catch (e: Exception) { error = e.message }
            finally { busy = null }
        }
    }
}

/* ---------------- UI ---------------- */

@Composable
private fun HelperTopBar(nav: NavController, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, fontSize = 12.sp, color = La.colors.text3)
        }
    }
}

@Composable
private fun PhotoButtons(vm: HelperBase, cameraLabel: String = "Take photo") {
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { vm.takePhoto(it) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.takePhoto(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runCatching { camera.launch(vm.cameraUri().also { cameraUri = it }) }.onFailure { vm.error = "No camera app is available." } }, Modifier.weight(1f)) {
            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(cameraLabel, maxLines = 1)
        }
        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f)) {
            Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Screenshot / photo", maxLines = 1)
        }
    }
}

@Composable
private fun LanguageChips(vm: HelperBase) {
    FlowRowSimple {
        VOICE_LANGUAGES.take(11).forEach { (tag, label) -> FilterChip(vm.language == tag, { vm.language = tag }, { Text(label) }) }
    }
}

@Composable
private fun Thumb(path: String) {
    val bmp: ImageBitmap? = produceState<ImageBitmap?>(null, path) { value = Images.thumbnail(File(path), 500)?.asImageBitmap() }.value
    if (bmp != null) Image(bmp, "Photo", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
}

@Composable
private fun BusyRow(label: String, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ModelLoadingMark(32.dp); Text("  $label", fontSize = 13.sp, color = La.colors.text2, modifier = Modifier.weight(1f))
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

@Composable
fun ScamScreen(nav: NavController, vm: ScamViewModel = hiltViewModel()) {
    val speaking by vm.speakingId.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        HelperTopBar(nav, "Scam check", "Checked on this phone · nothing is sent anywhere")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LaCard {
                Text("Is this message a scam?", fontWeight = FontWeight.SemiBold)
                Text("Paste an SMS, WhatsApp message or email, or add a screenshot. You can also share a message to LOCAL AI from any app.", fontSize = 12.5.sp, color = La.colors.text2)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(vm.input, { vm.input = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, placeholder = { Text("Message text") })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.paste() }, Modifier.weight(1f)) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste") }
                    Button(onClick = { vm.check() }, Modifier.weight(1f)) { Text("Check") }
                }
                Spacer(Modifier.height(8.dp))
                PhotoButtons(vm, "Photo")
            }
            vm.photo?.let { Thumb(it) }
            vm.error?.let { Banner(it, Tone.WARN) }
            vm.report?.let { r -> Verdict(r) }
            vm.report?.let { r ->
                if (r.flags.isNotEmpty()) LaCard {
                    Text("Red flags", fontWeight = FontWeight.SemiBold)
                    r.flags.forEach { f ->
                        Spacer(Modifier.height(10.dp))
                        Text(f.title, fontWeight = FontWeight.Medium, color = if (f.weight >= 5) La.colors.bad else La.colors.warn)
                        Text(f.why, fontSize = 13.sp, color = La.colors.text2)
                        Text("“${f.evidence}”", fontSize = 12.sp, color = La.colors.text3, fontFamily = La.mono)
                    }
                }
                LaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("In your language", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (vm.explanation.isNotBlank() && vm.busy == null) IconButton(onClick = { vm.speak("scam", vm.explanation) }) {
                            Icon(if (speaking == "scam") Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp, "Read aloud", tint = La.colors.accent)
                        }
                    }
                    LanguageChips(vm)
                    Spacer(Modifier.height(6.dp))
                    when {
                        vm.noModel -> Text("Download a chat model in Models to get an explanation in your language. The check above works without one.", fontSize = 12.5.sp, color = La.colors.text3)
                        vm.busy != null && vm.explanation.isBlank() -> BusyRow(vm.busy!!) { vm.stop() }
                        vm.explanation.isNotBlank() -> Text(vm.explanation + if (vm.busy != null) " ▍" else "", fontSize = 15.sp, lineHeight = 22.sp)
                    }
                    if (vm.explanation.isNotBlank() && vm.busy == null) TextButton(onClick = { vm.check() }) { Text("Explain again in ${languageName(vm.language)}") }
                }
                LaCard {
                    Text("What to do", fontWeight = FontWeight.SemiBold)
                    ScamCheck.advice.forEach { a -> Text("• $a", fontSize = 13.sp, color = La.colors.text2, modifier = Modifier.padding(top = 4.dp)) }
                }
                TextButton(onClick = { vm.clear() }) { Text("Check another message") }
            }
            if (vm.report == null && vm.busy != null) BusyRow(vm.busy!!) { vm.stop() }
            Text("The verdict comes from rules that run on the phone; the model only explains it. It can miss new tricks, so when in doubt, don't act on the message.",
                fontSize = 11.5.sp, color = La.colors.text3)
        }
    }
}

@Composable
private fun Verdict(r: ScamCheck.Report) {
    val (color, icon: ImageVector) = when (r.level) {
        ScamCheck.Level.HIGH -> La.colors.bad to Icons.Outlined.GppBad
        ScamCheck.Level.MEDIUM -> La.colors.warn to Icons.Outlined.GppMaybe
        ScamCheck.Level.LOW -> La.colors.ok to Icons.Outlined.GppGood
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.14f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(40.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(r.level.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(when (r.level) {
                ScamCheck.Level.HIGH -> "Don't click, call, pay or share anything."
                ScamCheck.Level.MEDIUM -> "Be careful. Check with the real company first."
                ScamCheck.Level.LOW -> "Still be careful with links and money requests."
            }, fontSize = 13.sp, color = La.colors.text2)
        }
    }
}

@Composable
fun ReadScreen(nav: NavController, vm: ReadViewModel = hiltViewModel()) {
    val speaking by vm.speakingId.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        HelperTopBar(nav, "Read it to me", "Letters, bills, forms, medicine labels")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.text.isBlank() && vm.busy == null) LaCard {
                Text("Point the camera at the page", fontWeight = FontWeight.SemiBold)
                Text("The text is read on the phone, then read out loud and explained simply in your language.", fontSize = 12.5.sp, color = La.colors.text2)
                Spacer(Modifier.height(10.dp))
                PhotoButtons(vm)
            }
            vm.photo?.let { Thumb(it) }
            vm.error?.let { Banner(it, Tone.WARN) }
            vm.busy?.let { b -> BusyRow(b) { vm.stop() } }
            if (vm.text.isNotBlank()) {
                LaCard {
                    Text("Explanation language", fontWeight = FontWeight.SemiBold)
                    LanguageChips(vm)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.explain() }, enabled = vm.busy == null, modifier = Modifier.weight(1f)) { Text(if (vm.explanation.isBlank()) "Explain simply" else "Explain again") }
                        OutlinedButton(onClick = { vm.readAsWritten() }, Modifier.weight(1f)) {
                            Icon(if (speaking == "raw") Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (speaking == "raw") "Stop" else "Read as written", maxLines = 1)
                        }
                    }
                }
                if (vm.explanation.isNotBlank()) LaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("What it says", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.speak("explain", vm.explanation) }) {
                            Icon(if (speaking == "explain") Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp, "Read aloud", tint = La.colors.accent)
                        }
                    }
                    Text(vm.explanation, fontSize = if (vm.large) 20.sp else 15.sp, lineHeight = if (vm.large) 30.sp else 22.sp)
                }
                LaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Text found", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.large = !vm.large }) { Icon(Icons.Outlined.TextFields, if (vm.large) "Smaller text" else "Larger text", tint = La.colors.text2) }
                    }
                    OutlinedTextField(vm.text, { vm.text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = if (vm.large) 20.sp else 15.sp))
                    Text("Read on the phone with text recognition. Fix any mistakes before explaining.", fontSize = 11.5.sp, color = La.colors.text3)
                }
                PhotoButtons(vm, "New photo")
                Text("For medicines, always follow the doctor or pharmacist.", fontSize = 11.5.sp, color = La.colors.text3)
            }
        }
    }
}

/** Entry point for content shared from other apps: choose what to do with it. */
@HiltViewModel
class ShareViewModel @Inject constructor(val inbox: SharedInbox) : ViewModel()

@Composable
fun ShareScreen(nav: NavController, vm: ShareViewModel = hiltViewModel()) {
    val item by vm.inbox.item.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        HelperTopBar(nav, "Shared with LOCAL AI", "Stays on this phone")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val i = item) {
                is SharedItem.Text -> LaCard { Text(i.text.take(600), fontSize = 14.sp) }
                is SharedItem.Photo -> Thumb(i.path)
                null -> Banner("Nothing was shared.", Tone.PENDING)
            }
            if (item != null) {
                Button(onClick = { nav.navigate("scam") { popUpTo("share") { inclusive = true } } }, Modifier.fillMaxWidth()) { Text("Check for scam") }
                if (item is SharedItem.Photo) OutlinedButton(onClick = { nav.navigate("read") { popUpTo("share") { inclusive = true } } }, Modifier.fillMaxWidth()) { Text("Read it to me") }
            }
        }
    }
}
