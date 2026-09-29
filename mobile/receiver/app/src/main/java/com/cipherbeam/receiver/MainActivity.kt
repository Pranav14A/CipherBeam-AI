package com.cipherbeam.receiver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cipherbeam.receiver.camera.CameraPreview
import com.cipherbeam.receiver.optical.OpticalDecoder
import com.cipherbeam.receiver.ui.OpticalWaveformSample
import com.cipherbeam.receiver.ui.ReceiverAppStage
import com.cipherbeam.receiver.ui.ReceiverDashboard
import com.cipherbeam.receiver.ui.ReceiverLandingScreen
import com.cipherbeam.receiver.ui.ReceiverUiState
import com.cipherbeam.receiver.ui.theme.CipherBeamReceiverTheme
import org.opencv.android.OpenCVLoader
import java.util.Locale


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenCVLoader.initLocal()) {
            Log.i(
                "CipherBeamOpenCV",
                "OpenCV initialized successfully"
            )
        } else {
            Log.e(
                "CipherBeamOpenCV",
                "OpenCV initialization failed"
            )
        }

        setContent {
            CipherBeamReceiverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReceiverRoot()
                }
            }
        }
    }
}


@Composable
private fun ReceiverRoot() {

    val context = LocalContext.current

    var appStage by remember {
        mutableStateOf(
            ReceiverAppStage.LANDING
        )
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            hasPermission = it
        }

    when (appStage) {

        ReceiverAppStage.LANDING -> {

            ReceiverLandingScreen(
                onInitializeReceiver = {

                    /*
                     * The camera is intentionally not started
                     * until the user presses this button.
                     */

                    if (hasPermission) {

                        appStage =
                            ReceiverAppStage.RECEIVING

                    } else {

                        launcher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                }
            )
        }

        ReceiverAppStage.RECEIVING -> {

            if (hasPermission) {

                ReceiverScreen()

            } else {

                PermissionScreen {

                    launcher.launch(
                        Manifest.permission.CAMERA
                    )
                }
            }
        }
    }
}


@Composable
private fun PermissionScreen(
    onRequest: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text = "CIPHERBEAM",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = "Optical receiver requires camera access.",
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            Button(
                onClick = onRequest
            ) {
                Text("Grant Camera Permission")
            }
        }
    }
}


@Composable
private fun ReceiverScreen() {

    val context = LocalContext.current

    var uiState by remember {
        mutableStateOf(
            ReceiverUiState()
        )
    }

    var frameCount by remember {
        mutableIntStateOf(0)
    }

    var fps by remember {
        mutableDoubleStateOf(0.0)
    }

    var fpsStart by remember {
        mutableStateOf(System.nanoTime())
    }

    /*
     * ------------------------------------------------------------
     * CipherBeam voice feedback
     * ------------------------------------------------------------
     *
     * Uses Android's built-in TextToSpeech engine.
     *
     * Voice events:
     *
     * START_DETECTED
     *     -> "Transmission started."
     *
     * PACKET_OK
     *     -> "Decoding passed."
     *
     * PACKET_FAILURE
     *     -> "Decoding failed."
     *
     * No optical/decoder logic is changed.
     */

    var ttsReady by remember {
        mutableStateOf(false)
    }

    val textToSpeech = remember(context) {

        TextToSpeech(
            context
        ) { status ->

            if (status == TextToSpeech.SUCCESS) {

                ttsReady = true

                Log.i(
                    "CipherBeamTTS",
                    "TextToSpeech initialized successfully"
                )

            } else {

                ttsReady = false

                Log.e(
                    "CipherBeamTTS",
                    "TextToSpeech initialization failed"
                )
            }
        }
    }

    /*
     * Configure and clean up TTS with the lifetime of the
     * receiver screen.
     */
    DisposableEffect(textToSpeech) {

        try {

            val languageResult =
                textToSpeech.setLanguage(
                    Locale.US
                )

            textToSpeech.setSpeechRate(
                1.0f
            )

            Log.i(
                "CipherBeamTTS",
                "Language configured: $languageResult"
            )

        } catch (exception: Exception) {

            Log.e(
                "CipherBeamTTS",
                "Failed to configure TextToSpeech",
                exception
            )
        }

        onDispose {

            try {

                textToSpeech.stop()
                textToSpeech.shutdown()

                Log.i(
                    "CipherBeamTTS",
                    "TextToSpeech shut down"
                )

            } catch (exception: Exception) {

                Log.e(
                    "CipherBeamTTS",
                    "TTS shutdown failed",
                    exception
                )
            }
        }
    }

    /*
     * START_DETECTED can produce several decoder snapshots.
     *
     * This prevents:
     *
     * "Transmission started."
     * "Transmission started."
     * "Transmission started."
     *
     * from being spoken repeatedly.
     */
    var transmissionStartAnnounced by remember {
        mutableStateOf(false)
    }

    /*
     * Prevent duplicate final announcements for the same
     * transmission.
     */
    var decodingResultAnnounced by remember {
        mutableStateOf(false)
    }

    fun speak(
        message: String
    ) {

        if (!ttsReady) {

            Log.w(
                "CipherBeamTTS",
                "TTS not ready. Skipping: $message"
            )

            return
        }

        try {

            textToSpeech.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "cipherbeam_${System.currentTimeMillis()}"
            )

            Log.i(
                "CipherBeamTTS",
                "Speaking: $message"
            )

        } catch (exception: Exception) {

            Log.e(
                "CipherBeamTTS",
                "Failed to speak: $message",
                exception
            )
        }
    }

    ReceiverDashboard(
        uiState = uiState,

        cameraContent = {

            CameraPreview(
                modifier = Modifier.fillMaxSize(),

                onBit = {
                    /*
                     * Legacy callback.
                     *
                     * The current optical pipeline uses
                     * feedOpticalSample() instead.
                     */
                },

                onDebug = { sample ->

                    frameCount++

                    val now =
                        System.nanoTime()

                    val elapsed =
                        (now - fpsStart) /
                                1_000_000_000.0

                    if (elapsed >= 1.0) {

                        fps =
                            frameCount / elapsed

                        frameCount = 0
                        fpsStart = now
                    }

                    val waveformSample =
                        OpticalWaveformSample(
                            redOn = sample.bitOn,
                            greenOn = sample.greenOn,
                            timestampNs =
                                System.nanoTime()
                        )

                    val updatedWaveform =
                        (
                                uiState.waveformSamples +
                                        waveformSample
                                ).takeLast(200)

                    uiState =
                        uiState.copy(
                            debugSample = sample,
                            fps = fps,
                            waveformSamples =
                                updatedWaveform
                        )
                },

                onDecoderSnapshot = { snapshot ->

                    val newTransmissionStarted =
                        snapshot.state ==
                                OpticalDecoder.State.START_DETECTED

                    /*
                     * When the decoder returns to idle,
                     * prepare the voice guards for the next
                     * transmission.
                     */
                    if (
                        snapshot.state ==
                        OpticalDecoder.State.WAITING_FOR_START
                    ) {

                        transmissionStartAnnounced =
                            false

                        decodingResultAnnounced =
                            false
                    }

                    /*
                     * Announce transmission start exactly once.
                     */
                    if (
                        newTransmissionStarted &&
                        !transmissionStartAnnounced
                    ) {

                        transmissionStartAnnounced =
                            true

                        decodingResultAnnounced =
                            false

                        speak(
                            "Transmission started."
                        )
                    }

                    uiState =
                        uiState.copy(
                            decoderState =
                                snapshot.state,

                            receivedByteCount =
                                snapshot.receivedByteCount,

                            lastReceivedMessage =
                                snapshot.completedMessage
                                    ?: if (
                                        newTransmissionStarted
                                    ) {
                                        null
                                    } else {
                                        uiState.lastReceivedMessage
                                    },

                            receivedPacket =
                                if (
                                    newTransmissionStarted
                                ) {
                                    null
                                } else {
                                    uiState.receivedPacket
                                },

                            packetStatus =
                                if (
                                    newTransmissionStarted
                                ) {
                                    null
                                } else {
                                    uiState.packetStatus
                                },

                            packetFailureReason =
                                if (
                                    newTransmissionStarted
                                ) {
                                    null
                                } else {
                                    uiState.packetFailureReason
                                }
                        )
                },

                onPacket = { packet ->

                    val payloadText =
                        packet.payload.toString(
                            Charsets.US_ASCII
                        )

                    /*
                     * onPacket is the successful packet event.
                     *
                     * The existing packet parser has already
                     * accepted the packet before this callback.
                     */
                    if (!decodingResultAnnounced) {

                        decodingResultAnnounced =
                            true

                        speak(
                            "Decoding passed."
                        )
                    }

                    uiState =
                        uiState.copy(
                            receivedPacket =
                                packet,

                            packetStatus =
                                "PACKET_OK",

                            packetFailureReason =
                                null,

                            lastReceivedMessage =
                                payloadText
                        )
                },

                onPacketFailure = { reason ->

                    val status =
                        when {

                            reason.startsWith(
                                "CRC mismatch"
                            ) ->
                                "PACKET_CRC_FAIL"

                            reason.startsWith(
                                "Unsupported algorithm ID"
                            ) ||
                                    reason.startsWith(
                                        "Unsupported security profile"
                                    ) ->
                                "PACKET_METADATA_FAIL"

                            else ->
                                "PACKET_FAIL"
                        }

                    /*
                     * The exact failure reason continues to be
                     * displayed in the existing UI.
                     *
                     * Voice deliberately says only:
                     * "Decoding failed."
                     */
                    if (!decodingResultAnnounced) {

                        decodingResultAnnounced =
                            true

                        speak(
                            "Decoding failed."
                        )
                    }

                    uiState =
                        uiState.copy(
                            packetStatus =
                                status,

                            packetFailureReason =
                                reason
                        )
                }
            )
        }
    )
}