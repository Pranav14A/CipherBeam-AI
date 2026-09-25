package com.cipherbeam.receiver.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cipherbeam.receiver.optical.OpticalDecoder
import com.cipherbeam.receiver.packet.CipherBeamPacket
import com.cipherbeam.receiver.packet.CipherBeamPacketParser
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onBit: (Boolean) -> Unit,
    onDebug: (RedLedAnalyzer.DebugSample) -> Unit,
    onDecoderSnapshot: (OpticalDecoder.Snapshot) -> Unit,
    onPacket: (CipherBeamPacket) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }

    val executor = remember {
        Executors.newSingleThreadExecutor()
    }

    AndroidView(
        modifier = modifier,

        factory = { ctx ->

            val previewView = PreviewView(ctx)

            val decoder = OpticalDecoder()
            var packetHandledForCurrentCompletion = false

            val analyzer = RedLedAnalyzer(
                onBit = { bit ->
                    /*
                     * Legacy RED-only callback retained for compatibility.
                     *
                     * The Phase 7 protocol no longer uses this callback
                     * for decoding.
                     */
                    mainHandler.post {
                        onBit(bit)
                    }
                },

                onDebug = { sample ->
                    mainHandler.post {
                        onDebug(sample)
                    }
                },

                onOpticalSample = { timestampNs, redOn, greenOn ->

                    val snapshot =
                        decoder.feedOpticalSample(
                            timestampNs = timestampNs,
                            redOn = redOn,
                            greenOn = greenOn
                        )

                    /*
                     * Packet-layer boundary.
                     *
                     * OpticalDecoder produces raw bytes.
                     * CipherBeamPacketParser interprets those bytes
                     * as a CipherBeam logical packet.
                     */
                    val completedBytes = snapshot.completedBytes

                    if (completedBytes != null) {
                        if (!packetHandledForCurrentCompletion) {
                            packetHandledForCurrentCompletion = true

                            when (val result = CipherBeamPacketParser.parse(completedBytes)) {
                                is CipherBeamPacketParser.Result.Success -> {
                                    Log.d(
                                        "CipherBeamPacket",
                                        "PACKET SUCCESS: startIndex=${result.startIndex}, " +
                                                "bytesConsumed=${result.bytesConsumed}, " +
                                                "version=${result.packet.version}, " +
                                                "flags=0x${result.packet.flags.toString(16).padStart(2, '0')}, " +
                                                "length=${result.packet.length}, " +
                                                "crc=0x${(result.packet.crc ?: 0).toString(16).padStart(4, '0')}"
                                    )
                                    mainHandler.post { onPacket(result.packet) }
                                }

                                is CipherBeamPacketParser.Result.Failure -> {
                                    Log.d(
                                        "CipherBeamPacket",
                                        "PACKET FAILURE: ${result.reason}"
                                    )
                                }
                            }
                        }
                    } else {
                        packetHandledForCurrentCompletion = false
                    }

                    mainHandler.post {
                        onDecoderSnapshot(snapshot)
                    }
                }
            )

            analyzer.resetSignalState()
            decoder.reset()

            val cameraProviderFuture =
                ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({

                val cameraProvider =
                    cameraProviderFuture.get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(
                                previewView.surfaceProvider
                            )
                        }

                val analysis =
                    ImageAnalysis.Builder()
                        .setTargetResolution(
                            Size(320, 240)
                        )
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()

                analysis.setAnalyzer(
                    executor,
                    analyzer
                )

                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

            }, ContextCompat.getMainExecutor(ctx))

            previewView.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {

                    override fun onViewAttachedToWindow(
                        v: View
                    ) = Unit

                    override fun onViewDetachedFromWindow(
                        v: View
                    ) {
                        executor.shutdown()
                    }
                }
            )

            previewView
        }
    )
}