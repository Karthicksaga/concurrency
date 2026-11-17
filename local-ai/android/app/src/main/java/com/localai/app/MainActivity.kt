package com.localai.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.localai.app.data.assist.SharedInbox
import com.localai.app.data.assist.SharedItem
import com.localai.app.data.media.Images
import com.localai.app.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var inbox: SharedInbox

    /** Chat to open from a notification (e.g. the daily digest). */
    private val openChat = MutableStateFlow<String?>(null)
    /** Screen to open, e.g. "share" after another app shared a message or screenshot. */
    private val openRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openChat.value = intent?.getStringExtra("open_chat")
        if (savedInstanceState == null) handleShare(intent)
        setContent { AppRoot(openChat, openRoute) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open_chat")?.let { openChat.value = it }
        handleShare(intent)
    }

    /** Text or one image shared from another app. The image is copied into app storage right away. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        when {
            type.startsWith("text/") -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                inbox.put(SharedItem.Text(it.take(20_000))); openRoute.value = "share"
            }
            type.startsWith("image/") -> {
                val uri: Uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)) ?: return
                lifecycleScope.launch {
                    runCatching { Images.import(this@MainActivity, uri) }.onSuccess { inbox.put(SharedItem.Photo(it.path)); openRoute.value = "share" }
                }
            }
        }
    }
}
