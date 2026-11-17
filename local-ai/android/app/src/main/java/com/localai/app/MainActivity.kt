package com.localai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import com.localai.app.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openChat.value = intent?.getStringExtra("open_chat")
        setContent { AppRoot(openChat) }
    }

    /** Chat to open from a notification (e.g. the daily digest). */
    private val openChat = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open_chat")?.let { openChat.value = it }
    }
}
