package nl.tippie.pixeltransfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "PixelTransfer",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Move a file between two phones with nothing but a screen and a camera. " +
                "No Bluetooth, no Wi-Fi, no mobile data, no NFC, no internet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Text("  Send a file", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Text("  Receive a file", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(16.dp))

        SectionCard("How it works") {
            Text(
                "The sender turns the file into an endless stream of coloured pixel frames and " +
                    "plays them at 5-20 frames per second. The receiver films the screen and " +
                    "decodes whatever it can resolve.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "There is no way to ask for a retransmission, so the stream is rateless: every " +
                    "frame carries freshly generated fountain-coded symbols, and the receiver " +
                    "needs any large enough subset of them. Missed, torn or misread frames are " +
                    "simply discarded - the stream keeps going.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("For the best results") {
            Text("Hold the phones 25-35 cm apart, roughly parallel.", style = MaterialTheme.typography.bodyMedium)
            Text("Turn off the night light or blue-light filter on the sending phone.", style = MaterialTheme.typography.bodyMedium)
            Text("Avoid glare: tilt slightly rather than sitting under a ceiling light.", style = MaterialTheme.typography.bodyMedium)
            Text("Steady hands beat a fast frame rate. If frames tear, lower the fps.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
