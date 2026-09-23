package com.cipherbeam.receiver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cipherbeam.receiver.camera.CameraPreview
import com.cipherbeam.receiver.camera.RedLedAnalyzer
import com.cipherbeam.receiver.optical.OpticalDecoder
import com.cipherbeam.receiver.packet.CipherBeamPacket

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ReceiverRoot()
                }
            }
        }
    }
}

@Composable
private fun ReceiverRoot() {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        ReceiverScreen()
    } else {
        PermissionScreen {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("CipherBeam AI Receiver needs camera access.")

            Spacer(Modifier.height(16.dp))

            Button(onClick = onRequest) {
                Text("Grant Camera Permission")
            }
        }
    }
}

@Composable
private fun ReceiverScreen() {

    /*
     * The active OpticalDecoder is owned by CameraPreview.
     * MainActivity receives immutable decoder snapshots.
     */
    var decoderState by remember {
        mutableStateOf(
            OpticalDecoder.Snapshot(
                state = OpticalDecoder.State.WAITING_FOR_START,
                message = "",
                lastByte = null,
                completedMessage = null
            )
        )
    }

    /*
     * Keep the last successfully decoded message visible even
     * after the decoder returns to WAITING_FOR_START.
     *
     * This is UI-only state and does not affect decoding.
     */
    var lastReceivedMessage by remember {
        mutableStateOf<String?>(null)
    }

    /*
     * Packet-level state.
     *
     * This is populated only after the optical decoder has produced
     * a complete byte sequence and CipherBeamPacketParser has
     * successfully validated the packet structure.
     */
    var receivedPacket by remember {
        mutableStateOf<CipherBeamPacket?>(null)
    }

    var debug by remember {
        mutableStateOf<RedLedAnalyzer.DebugSample?>(null)
    }

    var fps by remember {
        mutableDoubleStateOf(0.0)
    }

    var frameCount by remember {
        mutableIntStateOf(0)
    }

    var fpsStart by remember {
        mutableLongStateOf(System.nanoTime())
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        CameraPreview(
            modifier = Modifier.fillMaxSize(),

            onBit = {
                /*
                 * Legacy RED-only callback.
                 *
                 * Phase 7 decoding no longer uses this callback.
                 */
            },

            onDebug = { sample ->
                debug = sample

                frameCount++

                val now = System.nanoTime()

                val elapsed =
                    (now - fpsStart) / 1_000_000_000.0

                if (elapsed >= 1.0) {
                    fps = frameCount / elapsed
                    frameCount = 0
                    fpsStart = now
                }
            },

            onDecoderSnapshot = { snapshot ->
                decoderState = snapshot

                /*
                 * Capture a completed printable message before
                 * the decoder returns to WAITING_FOR_START.
                 */
                snapshot.completedMessage?.let { completed ->
                    lastReceivedMessage = completed
                }
            },

            onPacket = { packet ->
                /*
                 * Packet parser has already validated the
                 * logical packet structure.
                 *
                 * Phase 10 currently has no CRC validation yet,
                 * so the CRC field is displayed as parsed.
                 */
                receivedPacket = packet

                /*
                 * For the current plaintext Phase 10 packet,
                 * the payload is expected to contain printable ASCII.
                 */
                val payloadText =
                    packet.payload
                        .toString(Charsets.US_ASCII)

                lastReceivedMessage = payloadText
            }
        )

        /*
         * Debug / receiver status overlay.
         */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .background(
                    Color.Black.copy(alpha = 0.68f)
                )
                .padding(12.dp)
        ) {

            Text(
                text = "CipherBeam AI Receiver",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Status: ${decoderState.state}",
                color = Color.White
            )

            Text(
                text =
                    "Red: ${debug?.redness?.format()}  " +
                            "Baseline: ${debug?.baseline?.format()}",
                color = Color.White
            )

            Text(
                text =
                    "Red envelope: ${debug?.envelope?.format()}  " +
                            "Threshold: ${debug?.threshold?.format()}",
                color = Color.White
            )

            Text(
                text =
                    "Green: ${debug?.greenness?.format()}  " +
                            "Baseline: ${debug?.greenBaseline?.format()}",
                color = Color.White
            )

            Text(
                text =
                    "Green envelope: ${debug?.greenEnvelope?.format()}  " +
                            "Threshold: ${debug?.greenThreshold?.format()}",
                color = Color.White
            )

            Text(
                text =
                    "Red bit: ${if (debug?.bitOn == true) 1 else 0}   " +
                            "Green: ${if (debug?.greenOn == true) 1 else 0}",
                color = Color.White
            )

            Text(
                text =
                    "Analysis FPS: ${"%.1f".format(fps)}",
                color = Color.White
            )

            Text(
                text =
                    "Optical lock: " +
                            if (debug?.signalLocked == true) {
                                "YES"
                            } else {
                                "NO"
                            },
                color = Color.White
            )

            Text(
                text =
                    "Analysis: ${debug?.width ?: 0} × " +
                            "${debug?.height ?: 0}",
                color = Color.White
            )
        }

        /*
         * Received message / packet panel.
         */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .background(
                    Color.Black.copy(alpha = 0.72f)
                )
                .padding(14.dp)
        ) {

            Text(
                text = "Received Message",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )

            Text(
                text =
                    lastReceivedMessage
                        ?: decoderState.message.ifEmpty {
                            "Waiting for GREEN START…"
                        },
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )

            receivedPacket?.let { packet ->

                Spacer(Modifier.height(10.dp))

                Text(
                    text =
                        "Packet v${packet.version}  " +
                                "Flags: 0x${packet.flags.toString(16).padStart(2, '0')}",
                    color = Color.White
                )

                Text(
                    text =
                        "Profile: ${packet.securityProfile}  " +
                                "Algorithm: ${packet.algorithmId}",
                    color = Color.White
                )

                Text(
                    text =
                        "Payload: ${packet.length} bytes  " +
                                "CRC: 0x${
                                    (packet.crc ?: 0)
                                        .toString(16)
                                        .padStart(4, '0')
                                }",
                    color = Color.White
                )
            }

            if (
                decoderState.state ==
                OpticalDecoder.State.MESSAGE_COMPLETE
            ) {

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Message received successfully.",
                    color = Color.White
                )
            }
        }
    }
}

private fun Float.format(): String {
    return "%.3f".format(this)
}