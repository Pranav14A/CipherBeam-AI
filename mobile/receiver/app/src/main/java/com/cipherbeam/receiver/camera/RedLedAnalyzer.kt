package com.cipherbeam.receiver.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cipherbeam.receiver.optical.GreenSignalTracker
import com.cipherbeam.receiver.optical.RednessScorer
import com.cipherbeam.receiver.optical.SignalTracker
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Low-cost YUV_420_888 analyzer for the CipherBeam optical receiver.
 *
 * Phase 7 optical channels:
 *
 * GREEN = control / framing
 * RED   = data
 *
 * GREEN marks:
 * - transmission START
 * - transmission END
 *
 * RED carries:
 * - 0 = RED OFF
 * - 1 = RED ON
 *
 * The analyzer intentionally does NOT perform bit-window aggregation.
 * Synchronization is now controlled by the GREEN channel, and the
 * decoder is responsible for deciding when RED samples represent data.
 */
class RedLedAnalyzer(
    private val onBit: (Boolean) -> Unit,
    private val onDebug: (DebugSample) -> Unit,
    private val onOpticalSample: (
        timestampNs: Long,
        redOn: Boolean,
        greenOn: Boolean
    ) -> Unit = { _, _, _ -> }
) : ImageAnalysis.Analyzer {

    private val redTracker = SignalTracker(
        attackAlpha = 1.0f,
        releaseAlpha = 1.0f
    )
    private val greenTracker = GreenSignalTracker(
        attackAlpha = 1.0f,
        releaseAlpha = 1.0f
    )

    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }

        try {
            val sample = sampleCenterChroma(image)

            val redTracked =
                redTracker.update(sample.redness)

            val greenTracked =
                greenTracker.update(sample.greenness)

            /*
             * Phase 7 no longer uses BitWindowAggregator.
             *
             * GREEN controls framing and synchronization.
             * The decoder receives the raw timestamped RED/GREEN state
             * for every analyzed camera frame.
             */
            onOpticalSample(
                image.imageInfo.timestamp,
                redTracked.isOn,
                greenTracked.isOn
            )

            /*
             * Kept for constructor compatibility with the existing
             * CameraPreview/MainActivity code.
             *
             * This callback is intentionally NOT used for protocol
             * decoding in the new GREEN-framed receiver.
             */
            @Suppress("UNUSED_VARIABLE")
            val legacyBitCallback = onBit

            onDebug(
                DebugSample(
                    redness = redTracked.redness,
                    baseline = redTracked.baseline,
                    envelope = redTracked.envelope,
                    threshold = redTracked.threshold,
                    bitOn = redTracked.isOn,
                    signalLocked = greenTracked.isOn,

                    greenness = greenTracked.greenness,
                    greenBaseline = greenTracked.baseline,
                    greenEnvelope = greenTracked.envelope,
                    greenThreshold = greenTracked.threshold,
                    greenOn = greenTracked.isOn,

                    width = image.width,
                    height = image.height
                )
            )
        } catch (_: Throwable) {
            /*
             * Camera frames can occasionally be malformed or unavailable.
             * Never allow one bad frame to terminate ImageAnalysis.
             */
        } finally {
            image.close()
            busy.set(false)
        }
    }

    /**
     * Resets both RED and GREEN signal tracking.
     *
     * Used when starting a new reception.
     */
    fun resetSignalState() {
        redTracker.reset()
        greenTracker.reset()
    }

    /**
     * Samples the center 40% × 40% region of the image.
     *
     * YUV_420_888 chroma planes are normally half-resolution compared
     * with the full image. PlaneProxy does not expose width/height, so
     * their dimensions are derived from the ImageProxy dimensions.
     */
    private fun sampleCenterChroma(image: ImageProxy): ChromaSample {
        val planes = image.planes
        require(planes.size >= 3)

        val uPlane = planes[1]
        val vPlane = planes[2]

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        /*
         * Center ROI:
         *
         * Horizontal: 30% → 70%
         * Vertical:   30% → 70%
         */
        val left = (image.width * 0.30f).toInt()
        val right = (image.width * 0.70f).toInt()
        val top = (image.height * 0.30f).toInt()
        val bottom = (image.height * 0.70f).toInt()

        /*
         * YUV_420_888 chroma dimensions are approximately half of
         * the luma dimensions. Round upward for odd dimensions.
         */
        val chromaWidth = (image.width + 1) / 2
        val chromaHeight = (image.height + 1) / 2

        if (chromaWidth <= 0 || chromaHeight <= 0) {
            return ChromaSample(
                redness = 0f,
                greenness = 0f
            )
        }

        val cx = (
                (left + right) / 2f *
                        chromaWidth /
                        image.width
                ).toInt()
            .coerceIn(0, chromaWidth - 1)

        val cy = (
                (top + bottom) / 2f *
                        chromaHeight /
                        image.height
                ).toInt()
            .coerceIn(0, chromaHeight - 1)

        val radiusX = max(
            1,
            (
                    (right - left) *
                            chromaWidth /
                            image.width /
                            2f
                    ).toInt()
        )

        val radiusY = max(
            1,
            (
                    (bottom - top) *
                            chromaHeight /
                            image.height /
                            2f
                    ).toInt()
        )

        val y0 = max(0, cy - radiusY)
        val y1 = min(chromaHeight - 1, cy + radiusY)
        val x0 = max(0, cx - radiusX)
        val x1 = min(chromaWidth - 1, cx + radiusX)

        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        var uSum = 0L
        var vSum = 0L
        var count = 0

        /*
         * Sample every second chroma pixel to keep CPU usage low.
         */
        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {

                val uIndex =
                    y * uRowStride +
                            x * uPixelStride

                val vIndex =
                    y * vRowStride +
                            x * vPixelStride

                if (
                    uIndex in 0 until uBuffer.limit() &&
                    vIndex in 0 until vBuffer.limit()
                ) {
                    uSum +=
                        uBuffer.get(uIndex).toInt() and 0xFF

                    vSum +=
                        vBuffer.get(vIndex).toInt() and 0xFF

                    count++
                }
            }
        }

        if (count == 0) {
            return ChromaSample(
                redness = 0f,
                greenness = 0f
            )
        }

        val averageU = (uSum / count).toInt()
        val averageV = (vSum / count).toInt()

        return ChromaSample(
            redness = RednessScorer.score(
                averageU,
                averageV
            ),
            greenness = RednessScorer.greenScore(
                averageU,
                averageV
            )
        )
    }

    data class ChromaSample(
        val redness: Float,
        val greenness: Float
    )

    data class DebugSample(
        val redness: Float,
        val baseline: Float,
        val envelope: Float,
        val threshold: Float,
        val bitOn: Boolean,
        val signalLocked: Boolean,

        val greenness: Float,
        val greenBaseline: Float,
        val greenEnvelope: Float,
        val greenThreshold: Float,
        val greenOn: Boolean,

        val width: Int,
        val height: Int
    )
}