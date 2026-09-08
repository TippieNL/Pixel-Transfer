package nl.tippie.pixeltransfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.tippie.pixeltransfer.receiver.ReceiverScreen
import nl.tippie.pixeltransfer.receiver.ReceiverViewModel
import nl.tippie.pixeltransfer.sender.SenderPlaybackScreen
import nl.tippie.pixeltransfer.sender.SenderSetupScreen
import nl.tippie.pixeltransfer.sender.SenderViewModel
import nl.tippie.pixeltransfer.ui.HomeScreen
import nl.tippie.pixeltransfer.ui.PixelTransferTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelTransferTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    PixelTransferApp(sharedUri = sharedUriFrom(intent))
                }
            }
        }
    }

    private fun sharedUriFrom(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

private enum class Screen { HOME, SEND_SETUP, SEND_PLAY, RECEIVE }

@Composable
private fun PixelTransferApp(sharedUri: Uri?) {
    val senderViewModel: SenderViewModel = viewModel()
    val receiverViewModel: ReceiverViewModel = viewModel()

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var handledShare by remember { mutableStateOf(false) }

    // A file shared in from another app drops the user straight into the sender.
    LaunchedEffect(sharedUri) {
        if (sharedUri != null && !handledShare) {
            handledShare = true
            senderViewModel.pick(sharedUri)
            screen = Screen.SEND_SETUP
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onSend = { screen = Screen.SEND_SETUP },
            onReceive = { screen = Screen.RECEIVE },
        )

        Screen.SEND_SETUP -> SenderSetupScreen(
            viewModel = senderViewModel,
            onPlay = { screen = Screen.SEND_PLAY },
            onBack = { screen = Screen.HOME },
        )

        Screen.SEND_PLAY -> SenderPlaybackScreen(
            viewModel = senderViewModel,
            onExit = { screen = Screen.SEND_SETUP },
        )

        Screen.RECEIVE -> ReceiverScreen(
            viewModel = receiverViewModel,
            onBack = { screen = Screen.HOME },
        )
    }
}
