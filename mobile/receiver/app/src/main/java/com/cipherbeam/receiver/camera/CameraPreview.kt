package com.cipherbeam.receiver.camera

import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onBit: (Boolean) -> Unit,
    onDebug: (RedLedAnalyzer.DebugSample) -> Unit,
    onDecoderSnapshot: (OpticalDecoder.Snapshot) -> Unit
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