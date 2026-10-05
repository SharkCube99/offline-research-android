package app.offlineresearch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import app.offlineresearch.profiles.ProfileChoice
import app.offlineresearch.rag.Position
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

    // Lets scripts drive the app over adb:
    //   adb shell am start -n app.offlineresearch/.MainActivity --es ask "..."
    //   adb shell am start -n app.offlineresearch/.MainActivity --es profile high
    //   adb shell am start -n app.offlineresearch/.MainActivity --ei stress 20
    //   adb shell am start -n app.offlineresearch/.MainActivity --es gps "39.7392,-104.9903" --es ask "..."
    private fun askFromIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_PROFILE)?.let { chatViewModel.setProfileChoice(ProfileChoice.parse(it)) }
        // A script's stand-in for the satellite receiver; absent, the last one is forgotten.
        chatViewModel.setScriptedPosition(intent.getStringExtra(EXTRA_GPS)?.let(Position::parse))
        val stress = intent.getIntExtra(EXTRA_STRESS, 0)
        if (stress > 0) {
            chatViewModel.runStress(stress)
        } else {
            intent.getStringExtra(EXTRA_ASK)?.takeIf { it.isNotBlank() }?.let(chatViewModel::ask)
        }
    }

    companion object {
        const val EXTRA_ASK = "ask"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_STRESS = "stress"
        const val EXTRA_GPS = "gps"
    }
}
