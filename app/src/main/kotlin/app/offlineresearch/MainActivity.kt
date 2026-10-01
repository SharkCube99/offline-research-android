package app.offlineresearch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import app.offlineresearch.ui.ChatScreen
import app.offlineresearch.ui.ChatViewModel

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not on recreation (rotation), or the same question would be asked twice.
        if (savedInstanceState == null) askFromIntent(intent)
        setContent {
            MaterialTheme {
                ChatScreen(chatViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        askFromIntent(intent)
    }

    // Lets scripts ask a question over adb:
    //   adb shell am start -n app.offlineresearch/.MainActivity --es ask "..."
    private fun askFromIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ASK)?.takeIf { it.isNotBlank() }?.let(chatViewModel::ask)
    }

    companion object {
        const val EXTRA_ASK = "ask"
    }
}
