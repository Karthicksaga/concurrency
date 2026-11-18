package com.localai.app.feature.phone

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.localai.app.core.designsystem.La
import com.localai.app.data.phone.CallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(val calls: CallManager) : ViewModel() {
    var pickFor by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    fun picked(uri: android.net.Uri?) = viewModelScope.launch {
        val alias = pickFor ?: return@launch
        pickFor = null
        if (uri == null) { calls.dismissAsk(); return@launch }
        if (!calls.savePicked(alias, uri, thenCall = true)) error = "That contact has no phone number."
    }
}

/** Shown over every screen: the call countdown (Cancel / Call now) and "Who is Amma?" with the contact picker. */
@Composable
fun BoxScope.CallOverlay(vm: CallViewModel = hiltViewModel()) {
    val pending by vm.calls.pending.collectAsStateWithLifecycle()
    val askWho by vm.calls.askWho.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.picked(if (res.resultCode == Activity.RESULT_OK) res.data?.data else null)
    }
    pending?.let { p ->
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).background(La.colors.surface3).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(La.colors.ok), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Call, null, tint = La.colors.bg) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Calling ${p.name} in ${p.secondsLeft}…", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(p.number + if (p.direct) "" else " · opens the dialer", fontSize = 12.5.sp, color = La.colors.text3)
            }
            TextButton(onClick = { vm.calls.cancel() }) { Text("Cancel") }
            Button(onClick = { vm.calls.callNow() }) { Text("Call") }
        }
    }
    askWho?.let { name ->
        AlertDialog(onDismissRequest = { vm.calls.dismissAsk() }, title = { Text("Who is $name?") },
            text = { Text("Choose $name from your contacts once. Next time \"call $name\" works right away. LOCAL AI only sees the one contact you pick.") },
            confirmButton = { Button(onClick = { vm.pickFor = name; runCatching { picker.launch(vm.calls.pickIntent()) }.onFailure { vm.error = "No contacts app found." } }) { Text("Choose contact") } },
            dismissButton = { TextButton(onClick = { vm.calls.dismissAsk() }) { Text("Cancel") } })
    }
    vm.error?.let { e -> AlertDialog(onDismissRequest = { vm.error = null }, text = { Text(e) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } }) }
}
