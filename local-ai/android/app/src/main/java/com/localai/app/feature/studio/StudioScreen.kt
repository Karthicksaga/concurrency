package com.localai.app.feature.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.core.designsystem.fmtBytes
import com.localai.app.data.documents.AiAction
import com.localai.app.data.documents.Block
import com.localai.app.data.documents.DocFormat
import com.localai.app.data.documents.DocKindChoice
import com.localai.app.data.documents.DocLength
import com.localai.app.data.documents.DocumentStudio
import com.localai.app.data.documents.LoadedDoc
import com.localai.app.data.documents.Markdown
import com.localai.app.feature.chat.MarkdownText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class StudioViewModel @Inject constructor(private val studio: DocumentStudio) : ViewModel() {
    val files = studio.files
    var tab by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)
    var exported by mutableStateOf<File?>(null)
    private var job: Job? = null
    var busy by mutableStateOf(false); private set
    /** What the app is doing right now, shown with the loading animation. */
    var busyLabel by mutableStateOf(""); private set

    // write
    var kind by mutableStateOf(DocKindChoice.REPORT)
    var request by mutableStateOf("")
    var length by mutableStateOf(DocLength.MEDIUM)
    var language by mutableStateOf("")
    var draft by mutableStateOf("")

    // convert
    var source by mutableStateOf<LoadedDoc?>(null); private set
    var target by mutableStateOf(DocFormat.PDF)
    var action by mutableStateOf(AiAction.NONE)
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    var preview by mutableStateOf("")

    init { studio.refresh() }

    fun write() {
        if (request.isBlank()) { message = "Describe what to write."; return }
        busy = true; draft = ""; message = null; busyLabel = "Writing with the model on your phone…"
        job = viewModelScope.launch {
            try { draft = studio.write(kind, request, length, language) { draft = it } }
            catch (e: CancellationException) { message = "Stopped." }
            catch (e: Exception) { message = e.message ?: "Writing failed." }
            finally { busy = false }
        }
    }

    fun saveDraft(format: DocFormat) = viewModelScope.launch {
        runCatching { studio.exportMarkdown(draft, format, kind.label) }.onSuccess { exported = it }.onFailure { message = "Could not save: ${it.message}" }
    }

    fun load(uri: Uri) = viewModelScope.launch {
        busy = true; message = null; preview = ""
        busyLabel = "Opening the file… photos and scanned PDFs are read with text recognition on the phone, which can take up to a minute."
        runCatching { studio.load(uri) }.onSuccess { source = it; target = if (it.name.lowercase().endsWith(".pdf")) DocFormat.DOCX else DocFormat.PDF }
            .onFailure { message = it.message ?: "Could not read that file." }
        busy = false
    }

    fun convert() {
        val doc = source ?: return
        busy = true; message = null; preview = ""; progress = null
        busyLabel = if (action == AiAction.NONE) "Converting to ${target.label}…" else "${action.label} with the model on your phone…"
        job = viewModelScope.launch {
            try {
                val blocks: List<Block> = studio.transform(doc.blocks, action, language) { part, parts, text -> progress = part to parts; preview = text }
                val suffix = when (action) { AiAction.NONE -> ""; AiAction.SUMMARIZE -> " – summary"; AiAction.TRANSLATE -> " – ${language.ifBlank { "translated" }}"; else -> " – edited" }
                // Name the result after the original file (headings read from photos can be garbled).
                val title = doc.name.substringBeforeLast('.').ifBlank { doc.title } + suffix
                exported = studio.export(blocks, title.trim(), target)
            } catch (e: CancellationException) { message = "Stopped." }
            catch (e: Exception) { message = e.message ?: "Conversion failed." }
            finally { busy = false; progress = null }
        }
    }

    fun stop() { job?.cancel() }
    fun clearSource() { source = null; preview = "" }

    fun open(f: File) { if (!studio.open(f)) message = "No app on this phone opens ${f.extension.uppercase()} files. Use Share or Save to phone." }
    fun delete(f: File) = studio.delete(f)
    fun copyTo(f: File, uri: Uri) = viewModelScope.launch { runCatching { studio.copyTo(f, uri) }.onSuccess { message = "Saved ${f.name}." }.onFailure { message = it.message } }
}

@Composable
fun StudioScreen(nav: NavController, vm: StudioViewModel = hiltViewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val files by vm.files.collectAsStateWithLifecycle()
    var saveTarget by remember { mutableStateOf<File?>(null) }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.load(it) } }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocFormat.PDF.mime)) { uri -> val f = saveTarget; if (uri != null && f != null) vm.copyTo(f, uri) }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocFormat.DOCX.mime)) { uri -> val f = saveTarget; if (uri != null && f != null) vm.copyTo(f, uri) }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> val f = saveTarget; if (uri != null && f != null) vm.copyTo(f, uri) }
    val shareFile: (File) -> Unit = { f ->
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(DocFormat.forFile(f)?.mime ?: "*/*").putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"))
    }
    val saveToPhone: (File) -> Unit = { f ->
        saveTarget = f
        when (DocFormat.forFile(f)) { DocFormat.PDF -> savePdf.launch(f.name); DocFormat.DOCX -> saveDocx.launch(f.name); else -> saveMd.launch(f.name) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Document Studio", style = MaterialTheme.typography.titleMedium)
                Text("PDF and Word, written and converted on this phone", fontSize = 12.sp, color = La.colors.text3)
            }
        }
        TabRow(vm.tab, containerColor = La.colors.bg) {
            listOf("Write", "Convert", "Files (${files.size})").forEachIndexed { i, t -> Tab(vm.tab == i, { vm.tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        vm.message?.let { m -> Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Banner(m, Tone.RUN) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (vm.tab) {
                0 -> WriteTab(vm)
                1 -> ConvertTab(vm) { pickDoc.launch(arrayOf("application/pdf", DocFormat.DOCX.mime, "image/*", "text/*", "application/octet-stream")) }
                else -> FilesTab(files, vm, shareFile, saveToPhone)
            }
        }
    }
    vm.exported?.let { f ->
        AlertDialog(onDismissRequest = { vm.exported = null }, title = { Text("${f.extension.uppercase()} ready") },
            text = { Text("${f.name} · ${fmtBytes(f.length())}\nKept in Files until you delete it.", fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.open(f); vm.exported = null }) { Text("Open") } },
            dismissButton = { Row {
                TextButton(onClick = { shareFile(f); vm.exported = null }) { Text("Share") }
                TextButton(onClick = { saveToPhone(f); vm.exported = null }) { Text("Save to phone") }
            } })
    }
}

@Composable
private fun WriteTab(vm: StudioViewModel) {
    LaCard {
        Text("What should I write?", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRowSimple { DocKindChoice.entries.forEach { k -> FilterChip(vm.kind == k, { vm.kind = k }, { Text(k.label) }) } }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(vm.request, { vm.request = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = !vm.busy,
            label = { Text("Describe it") }, placeholder = { Text("e.g. Leave letter to my manager for 3 days from 20 Nov for a family function") })
        Spacer(Modifier.height(8.dp))
        Text("Length", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        FlowRowSimple { DocLength.entries.forEach { l -> FilterChip(vm.length == l, { vm.length = l }, { Text(l.label) }) } }
        OutlinedTextField(vm.language, { vm.language = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Language (optional)") }, placeholder = { Text("English") })
        Spacer(Modifier.height(10.dp))
        if (vm.busy) OutlinedButton(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        else Button(onClick = { vm.write() }, modifier = Modifier.fillMaxWidth()) { Text(if (vm.draft.isBlank()) "Write with AI" else "Write again") }
        Text("Uses the model you loaded. Small models write simpler text; check names, dates and numbers before you send it.", fontSize = 11.5.sp, color = La.colors.text3, modifier = Modifier.padding(top = 6.dp))
    }
    if (vm.busy && vm.tab == 0 && vm.draft.isBlank()) LoaderCard(vm.busyLabel)
    if (vm.draft.isNotBlank()) {
        LaCard {
            Text("Preview", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            MarkdownText(vm.draft + if (vm.busy) " ▍" else "")
        }
        if (!vm.busy) {
            OutlinedTextField(vm.draft, { vm.draft = it }, Modifier.fillMaxWidth().heightIn(max = 320.dp), label = { Text("Edit (Markdown)") }, minLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.saveDraft(DocFormat.PDF) }, Modifier.weight(1f)) { Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save PDF") }
                Button(onClick = { vm.saveDraft(DocFormat.DOCX) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save Word") }
            }
        }
    }
}

@Composable
private fun ConvertTab(vm: StudioViewModel, pick: () -> Unit) {
    val src = vm.source
    LaCard {
        Text("1 · Choose a file", fontWeight = FontWeight.SemiBold)
        Text("Word (.docx), PDF (scanned PDFs too), a photo of a page, Markdown or text. Photos and scans are read with on-device text recognition.", fontSize = 12.sp, color = La.colors.text3)
        Spacer(Modifier.height(8.dp))
        if (src == null) { if (!vm.busy) Button(onClick = pick) { Text("Choose file") } }
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(src.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${src.blocks.size} blocks · ${src.chars} characters", fontSize = 12.sp, color = La.colors.text3)
            }
            TextButton(onClick = { vm.clearSource() }, enabled = !vm.busy) { Text("Change") }
        }
    }
    if (src == null && vm.busy) LoaderCard(vm.busyLabel)
    if (src != null) {
        LaCard {
            Text("2 · Convert to", fontWeight = FontWeight.SemiBold)
            FlowRowSimple { listOf(DocFormat.PDF, DocFormat.DOCX, DocFormat.MD).forEach { f -> FilterChip(vm.target == f, { vm.target = f }, { Text(f.label) }) } }
            Spacer(Modifier.height(8.dp))
            Text("3 · AI on the way (optional)", fontWeight = FontWeight.SemiBold)
            FlowRowSimple { AiAction.entries.forEach { a -> FilterChip(vm.action == a, { vm.action = a }, { Text(a.label) }) } }
            if (vm.action == AiAction.TRANSLATE) OutlinedTextField(vm.language, { vm.language = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Translate into") }, placeholder = { Text("e.g. Tamil, Hindi, French") })
            if (vm.action != AiAction.NONE) Text("Long documents are processed part by part (about a page at a time in lite mode). This can take several minutes.", fontSize = 11.5.sp, color = La.colors.text3)
            Spacer(Modifier.height(10.dp))
            if (vm.busy) OutlinedButton(onClick = { vm.stop() }, Modifier.fillMaxWidth()) { Text("Stop") }
            else Button(onClick = { vm.convert() }, Modifier.fillMaxWidth()) { Text(if (vm.action == AiAction.NONE) "Convert to ${vm.target.label}" else "${vm.action.label} → ${vm.target.label}") }
        }
        if (vm.busy) LoaderCard(vm.busyLabel + (vm.progress?.let { (p, n) -> " Part $p of $n." } ?: ""))
        if (vm.preview.isNotBlank()) LaCard { Text("Live preview", fontWeight = FontWeight.SemiBold); MarkdownText(vm.preview.takeLast(3000)) }
        else if (!vm.busy) LaCard { Text("Preview of the original", fontWeight = FontWeight.SemiBold); MarkdownText(Markdown.render(src.blocks.take(12)).take(2500)) }
    }
}

@Composable
private fun FilesTab(files: List<File>, vm: StudioViewModel, share: (File) -> Unit, save: (File) -> Unit) {
    if (files.isEmpty()) Banner("Nothing here yet. Documents you write, convert or save from chat appear here.", Tone.PENDING)
    files.forEach { f ->
        var confirm by remember { mutableStateOf(false) }
        LaCard(onClick = { vm.open(f) }, padding = 12) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (f.extension == "pdf") Icons.Outlined.PictureAsPdf else Icons.Outlined.Description, null, tint = if (f.extension == "pdf") La.colors.bad else La.colors.accent)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(f.name, fontWeight = FontWeight.Medium, maxLines = 2, fontSize = 13.5.sp)
                    Text("${fmtBytes(f.length())} · ${fmtAgo(f.lastModified())}", fontSize = 11.5.sp, color = La.colors.text3)
                }
                IconButton(onClick = { share(f) }) { Icon(Icons.Outlined.Share, "Share", tint = La.colors.text2) }
                IconButton(onClick = { save(f) }) { Icon(Icons.Outlined.SaveAlt, "Save to phone", tint = La.colors.text2) }
                IconButton(onClick = { if (confirm) vm.delete(f) else confirm = true }) { Icon(Icons.Outlined.Delete, if (confirm) "Tap again to delete" else "Delete", tint = if (confirm) La.colors.bad else La.colors.text3) }
            }
        }
    }
}


/** Loading state with the animated chip, so a slow step never looks like an empty screen. */
@Composable
fun LoaderCard(label: String) {
    LaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.localai.app.core.designsystem.ModelLoadingMark(44.dp)
            Text(label.ifBlank { "Working…" }, fontSize = 13.5.sp, color = La.colors.text2, modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
