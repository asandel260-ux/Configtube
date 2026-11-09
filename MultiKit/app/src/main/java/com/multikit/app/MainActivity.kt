package com.multikit.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.multikit.app.navigation.AppNavHost
import com.multikit.app.ui.theme.MultiKitTheme

class MainActivity : ComponentActivity() {

    private var incoming by mutableStateOf<Incoming?>(null)

    data class Incoming(val uri: String?, val text: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming = parseIncoming(intent)
        setContent {
            MultiKitTheme {
                AppNavHost(incoming = incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = parseIncoming(intent)
    }

    private fun parseIncoming(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri: Uri? = intent.data
                if (uri != null) Incoming(uri.toString(), null) else null
            }
            Intent.ACTION_SEND -> {
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> Incoming(stream.toString(), null)
                    !text.isNullOrBlank() -> Incoming(null, text)
                    else -> null
                }
            }
            else -> null
        }
    }
}