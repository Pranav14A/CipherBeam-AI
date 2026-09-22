package com.cipherbeam.receiver.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cipherbeam.receiver.optical.GreenSignalTracker
import com.cipherbeam.receiver.optical.RednessScorer
import com.cipherbeam.receiver.optical.SignalTracker
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Low-cost YUV_420_888 analyzer for the CipherBeam optical receiver.
 *
 * Phase 8:
 *
 * GREEN = control / framing / transmitter localization
 * RED   = data
 *
 * Detection architecture:
 *
 * 1. GREEN spatial + temporal pilot detection
 *    - Scan a coarse grid of small ROIs.
 *    - Look for a localized GREEN rise rather than a static GREEN object.
 *    - Lock the GREEN position when a pilot appears.
 *
 * 2. RED local tracking
 *    - Start around the GREEN transmitter location.
 *    - Search a small neighborhood for the strongest RED region.
 *    - Move the RED ROI only when a sufficiently strong RED signal
 *      provides evidence that the transmitter has moved.
 *
 * The communication protocol and optical decoder remain unchanged.
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

    private companion object {
        const val DIAGNOSTIC_TAG = "CipherBeamDiag"
        const val DIAGNOSTIC_INTERVAL_NS = 250_000_000L

        /*
         * The previously validated small ROI.
         *
         * 8% of image width × 8% of image height.
         */
        const val ROI_FRACTION = 0.08f

        /*
         * Coarse GREEN localization grid.
         */
        const val GREEN_GRID_COLUMNS = 5
        const val GREEN_GRID_ROWS = 5

        /*
         * GREEN must satisfy three conditions:
         *
         * 1. It must be sufficiently strong.
         * 2. It must rise compared with the previous frame.
         * 3. It must stand out from the spatial background.
         */
        const val GREEN_START_THRESHOLD = 0.08f
        const val GREEN_START_DELTA = 0.025f
        const val GREEN_SPATIAL_MARGIN = 0.025f

        /*
         * RED local search positions.
         *
         * The search covers ±12% around the current RED center.
         */
        const val RED_SEARCH_RADIUS_FRACTION = 0.24f
        const val RED_SEARCH_STEP_FRACTION = 0.12f

        /*
         * A RED candidate can be used as the current sample at a
         * moderate signal level, but the tracking center only moves
         * when the candidate is clearly strong.
         */
        const val RED_SAMPLE_MIN = 0.10f
        const val RED_TRACK_THRESHOLD = 0.10f
        const val RED_TRACK_MARGIN = 0.015f
    }

    private var lastDiagnosticTimestampNs: Long? = null

    private var fpsWindowStartNs: Long? = null
    private var fpsFrameCount = 0

    private var lastLoggedRedOn = false
    private var lastLoggedGreenOn = false

    /*
     * GREEN localization state.
     */
    private var greenCenterX: Int? = null
    private var greenCenterY: Int? = null

    private var previousGreenScores =
        FloatArray(
            GREEN_GRID_COLUMNS * GREEN_GRID_ROWS
        )

    private var greenHistoryInitialized = false

    /*
     * RED localization state.
     *
     * Until RED has been located, start searching around the GREEN
     * transmitter position.
     */
    private var redCenterX: Int? = null
    private var redCenterY: Int? = null

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }

        try {
            /*
             * First perform GREEN localization.
             *
             * Both GREEN START and GREEN END create a rising edge,
             * so this same mechanism can relocate the transmitter
             * after camera movement.
             */
            val greenSearch =
                findGreenCandidate(image)

            val greenOnset =
                greenSearch.onset

            if (greenOnset != null) {
                greenCenterX = greenOnset.centerX
                greenCenterY = greenOnset.centerY

                /*
                 * Re-anchor RED search to the newly detected GREEN position.
                 *
                 * GREEN is our transmitter-localization pilot, so whenever a new
                 * GREEN position is detected, the RED search must follow it rather
                 * than remaining at an old RED location.
                 */
                redCenterX = greenOnset.centerX
                redCenterY = greenOnset.centerY
            }

            /*
             * Sample GREEN at the localized GREEN position.
             *
             * If localization has not happened yet, use the center
             * only as a harmless background sample.
             */
            val greenSample =
                if (
                    greenCenterX != null &&
                    greenCenterY != null
                ) {
                    sampleRegionAroundCenter(
                        image = image,
                        centerX = greenCenterX!!,
                        centerY = greenCenterY!!
                    )
                } else {
                    sampleCenterChroma(image)
                }

            /*
             * RED starts from the GREEN position until an actual RED
             * optical signal provides a stronger location.
             */
            val initialRedCenterX =
                redCenterX
                    ?: greenCenterX

            val initialRedCenterY =
                redCenterY
                    ?: greenCenterY

            val redLocatedSample =
                if (
                    initialRedCenterX != null &&
                    initialRedCenterY != null
                ) {
                    sampleAndTrackRed(
                        image = image,
                        centerX = initialRedCenterX,
                        centerY = initialRedCenterY
                    )
                } else {
                    LocatedSample(
                        centerX = image.width / 2,
                        centerY = image.height / 2,
                        sample = sampleCenterChroma(image)
                    )
                }

            if (
                redCenterX == null &&
                redCenterY == null &&
                redLocatedSample.sample.redness >=
                RED_TRACK_THRESHOLD
            ) {
                redCenterX =
                    redLocatedSample.centerX

                redCenterY =
                    redLocatedSample.centerY
            }

            val redSignal =
                redLocatedSample.sample.redness
                    .coerceAtLeast(0f)

            val greenSignal =
                greenSample.greenness
                    .coerceAtLeast(0f)

            val redTracked =
                redTracker.update(
                    redSignal
                )

            val greenTracked =
                greenTracker.update(
                    greenSignal
                )

            onOpticalSample(
                image.imageInfo.timestamp,
                redTracked.isOn,
                greenTracked.isOn
            )

            /*
             * Keep the legacy callback behavior unchanged.
             */
            @Suppress("UNUSED_VARIABLE")
            val legacyBitCallback = onBit

            onDebug(
                DebugSample(
                    redness =
                        redTracked.redness,

                    baseline =
                        redTracked.baseline,

                    envelope =
                        redTracked.envelope,

                    threshold =
                        redTracked.threshold,

                    bitOn =
                        redTracked.isOn,

                    signalLocked =
                        greenTracked.isOn,

                    greenness =
                        greenTracked.greenness,

                    greenBaseline =
                        greenTracked.baseline,

                    greenEnvelope =
                        greenTracked.envelope,

                    greenThreshold =
                        greenTracked.threshold,

                    greenOn =
                        greenTracked.isOn,

                    width =
                        image.width,

                    height =
                        image.height
                )
            )

            logDiagnosticSample(
                timestampNs =
                    image.imageInfo.timestamp,

                redSample =
                    redLocatedSample.sample,

                greenSample =
                    greenSample,

                redOn =
                    redTracked.isOn,

                greenOn =
                    greenTracked.isOn
            )
        } catch (_: Throwable) {
        } finally {
            image.close()
            busy.set(false)
        }
    }

    fun resetSignalState() {
        redTracker.reset()
        greenTracker.reset()

        lastDiagnosticTimestampNs = null
        fpsWindowStartNs = null
        fpsFrameCount = 0

        lastLoggedRedOn = false
        lastLoggedGreenOn = false

        greenCenterX = null
        greenCenterY = null

        redCenterX = null
        redCenterY = null

        previousGreenScores =
            FloatArray(
                GREEN_GRID_COLUMNS *
                        GREEN_GRID_ROWS
            )

        greenHistoryInitialized = false
    }

    /**
     * Find a GREEN region using a coarse spatial grid.
     *
     * A static green object should not normally qualify because
     * the detector requires a positive frame-to-frame rise.
     */
    private fun findGreenCandidate(
        image: ImageProxy
    ): GreenSearchResult {
        val totalCells =
            GREEN_GRID_COLUMNS *
                    GREEN_GRID_ROWS

        val currentScores =
            FloatArray(totalCells)

        val currentSamples =
            arrayOfNulls<LocatedSample>(
                totalCells
            )

        for (
        row in 0 until GREEN_GRID_ROWS
        ) {
            for (
            column in 0 until GREEN_GRID_COLUMNS
            ) {
                val index =
                    row *
                            GREEN_GRID_COLUMNS +
                            column

                val centerX =
                    (
                            (column + 0.5f) /
                                    GREEN_GRID_COLUMNS *
                                    image.width
                            )
                        .toInt()
                        .coerceIn(
                            0,
                            image.width - 1
                        )

                val centerY =
                    (
                            (row + 0.5f) /
                                    GREEN_GRID_ROWS *
                                    image.height
                            )
                        .toInt()
                        .coerceIn(
                            0,
                            image.height - 1
                        )

                val sample =
                    sampleRegionAroundCenter(
                        image = image,
                        centerX = centerX,
                        centerY = centerY
                    )

                val greenScore =
                    sample.greenness
                        .coerceAtLeast(0f)

                currentScores[index] =
                    greenScore

                currentSamples[index] =
                    LocatedSample(
                        centerX = centerX,
                        centerY = centerY,
                        sample = sample
                    )
            }
        }

        if (!greenHistoryInitialized) {
            previousGreenScores =
                currentScores.copyOf()

            greenHistoryInitialized = true

            return GreenSearchResult(
                best = null,
                onset = null
            )
        }

        val sortedScores =
            currentScores
                .copyOf()
                .sortedArray()

        val median =
            sortedScores[
                sortedScores.size / 2
            ]

        var bestCandidate: GreenCandidate? = null
        var onsetCandidate: GreenCandidate? = null

        for (index in 0 until totalCells) {
            val sample =
                currentSamples[index]
                    ?: continue

            val score =
                currentScores[index]

            val delta =
                score -
                        previousGreenScores[index]

            val spatialMargin =
                score - median

            val row =
                index / GREEN_GRID_COLUMNS

            val column =
                index % GREEN_GRID_COLUMNS

            val candidate =
                GreenCandidate(
                    index = index,
                    row = row,
                    column = column,
                    centerX = sample.centerX,
                    centerY = sample.centerY,
                    sample = sample.sample,
                    score = score,
                    delta = delta,
                    spatialMargin = spatialMargin
                )

            if (
                bestCandidate == null ||
                candidate.score >
                bestCandidate!!.score
            ) {
                bestCandidate =
                    candidate
            }

            if (
                candidate.score >=
                GREEN_START_THRESHOLD &&
                candidate.delta >=
                GREEN_START_DELTA &&
                candidate.spatialMargin >=
                GREEN_SPATIAL_MARGIN
            ) {
                if (
                    onsetCandidate == null ||
                    candidate.delta >
                    onsetCandidate!!.delta
                ) {
                    onsetCandidate =
                        candidate
                }
            }
        }

        previousGreenScores =
            currentScores

        return GreenSearchResult(
            best = bestCandidate,
            onset = onsetCandidate
        )
    }

    /**
     * Search locally for the strongest RED region.
     *
     * The search remains close to the previously known transmitter
     * position, preventing a static red object elsewhere in the frame
     * from immediately taking over the detector.
     */
    private fun sampleAndTrackRed(
        image: ImageProxy,
        centerX: Int,
        centerY: Int
    ): LocatedSample {
        val current =
            LocatedSample(
                centerX = centerX,
                centerY = centerY,
                sample =
                    sampleRegionAroundCenter(
                        image = image,
                        centerX = centerX,
                        centerY = centerY
                    )
            )

        var best =
            current

        val offsets =
            floatArrayOf(
                -RED_SEARCH_RADIUS_FRACTION,
                -RED_SEARCH_STEP_FRACTION,
                0f,
                RED_SEARCH_STEP_FRACTION,
                RED_SEARCH_RADIUS_FRACTION
            )

        val currentRed =
            current.sample.redness
                .coerceAtLeast(0f)

        for (offsetY in offsets) {
            for (offsetX in offsets) {
                if (
                    offsetX == 0f &&
                    offsetY == 0f
                ) {
                    continue
                }

                val candidateCenterX =
                    (
                            centerX +
                                    offsetX *
                                    image.width
                            )
                        .toInt()
                        .coerceIn(
                            0,
                            image.width - 1
                        )

                val candidateCenterY =
                    (
                            centerY +
                                    offsetY *
                                    image.height
                            )
                        .toInt()
                        .coerceIn(
                            0,
                            image.height - 1
                        )

                val sample =
                    sampleRegionAroundCenter(
                        image =
                            image,

                        centerX =
                            candidateCenterX,

                        centerY =
                            candidateCenterY
                    )

                val candidateRed =
                    sample.redness
                        .coerceAtLeast(0f)

                val bestRed =
                    best.sample.redness
                        .coerceAtLeast(0f)

                if (
                    candidateRed >
                    bestRed
                ) {
                    best =
                        LocatedSample(
                            centerX =
                                candidateCenterX,

                            centerY =
                                candidateCenterY,

                            sample =
                                sample
                        )
                }
            }
        }

        val bestRed =
            best.sample.redness
                .coerceAtLeast(0f)

        /*
         * Only move the RED tracking center when the new location
         * provides meaningful evidence above the current location.
         */
        if (
            bestRed >= RED_TRACK_THRESHOLD &&
            bestRed >=
            currentRed +
            RED_TRACK_MARGIN
        ) {
            redCenterX =
                best.centerX

            redCenterY =
                best.centerY
        }

        /*
         * Use the stronger local sample for the current frame when
         * there is enough RED evidence to justify it.
         */
        return if (
            bestRed >= RED_SAMPLE_MIN &&
            bestRed >
            currentRed +
            0.02f
        ) {
            best
        } else {
            current
        }
    }

    /**
     * Sample an 8% × 8% ROI around a requested center.
     */
    private fun sampleRegionAroundCenter(
        image: ImageProxy,
        centerX: Int,
        centerY: Int
    ): ChromaSample {
        val halfWidth =
            max(
                1,
                (
                        image.width *
                                ROI_FRACTION /
                                2f
                        ).toInt()
            )

        val halfHeight =
            max(
                1,
                (
                        image.height *
                                ROI_FRACTION /
                                2f
                        ).toInt()
            )

        val left =
            max(
                0,
                centerX - halfWidth
            )

        val right =
            min(
                image.width - 1,
                centerX + halfWidth
            )

        val top =
            max(
                0,
                centerY - halfHeight
            )

        val bottom =
            min(
                image.height - 1,
                centerY + halfHeight
            )

        return sampleChromaRegion(
            image = image,
            left = left,
            right = right,
            top = top,
            bottom = bottom
        )
    }

    /**
     * Diagnostic logging.
     *
     * RED and GREEN are now sampled from their own localized ROIs,
     * so their Y/U/V values are reported separately.
     */
    private fun logDiagnosticSample(
        timestampNs: Long,
        redSample: ChromaSample,
        greenSample: ChromaSample,
        redOn: Boolean,
        greenOn: Boolean
    ) {
        fpsFrameCount++

        val fpsStart =
            fpsWindowStartNs

        if (fpsStart == null) {
            fpsWindowStartNs =
                timestampNs
        } else if (
            timestampNs - fpsStart >=
            1_000_000_000L
        ) {
            val elapsedSeconds =
                (
                        timestampNs -
                                fpsStart
                        ) / 1_000_000_000f

            val fps =
                if (
                    elapsedSeconds > 0f
                ) {
                    fpsFrameCount /
                            elapsedSeconds
                } else {
                    0f
                }

            Log.d(
                DIAGNOSTIC_TAG,
                "FPS=${"%.1f".format(fps)}"
            )

            fpsWindowStartNs =
                timestampNs

            fpsFrameCount = 0
        }

        val previousTimestamp =
            lastDiagnosticTimestampNs

        val shouldLog =
            previousTimestamp == null ||
                    timestampNs -
                    previousTimestamp >=
                    DIAGNOSTIC_INTERVAL_NS

        val stateChanged =
            redOn != lastLoggedRedOn ||
                    greenOn != lastLoggedGreenOn

        if (
            !shouldLog &&
            !stateChanged
        ) {
            return
        }

        lastDiagnosticTimestampNs =
            timestampNs

        lastLoggedRedOn =
            redOn

        lastLoggedGreenOn =
            greenOn

        val width =
            max(
                1,
                greenCenterX?.let {
                    it
                } ?: 0
            )

        val greenLocation =
            if (
                greenCenterX != null &&
                greenCenterY != null
            ) {
                "${greenCenterX}x${greenCenterY}"
            } else {
                "-"
            }

        val redLocation =
            if (
                redCenterX != null &&
                redCenterY != null
            ) {
                "${redCenterX}x${redCenterY}"
            } else {
                "-"
            }

        @Suppress("UNUSED_VARIABLE")
        val unusedWidthGuard = width

        Log.d(
            DIAGNOSTIC_TAG,
            buildString {
                append("RY=")
                append(
                    "%.1f".format(
                        redSample.luma
                    )
                )

                append(" RU=")
                append(
                    redSample.averageU
                )

                append(" RV=")
                append(
                    redSample.averageV
                )

                append(" rawR=")
                append(
                    "%.4f".format(
                        redSample.redness
                    )
                )

                append(" GY=")
                append(
                    "%.1f".format(
                        greenSample.luma
                    )
                )

                append(" GU=")
                append(
                    greenSample.averageU
                )

                append(" GV=")
                append(
                    greenSample.averageV
                )

                append(" rawG=")
                append(
                    "%.4f".format(
                        greenSample.greenness
                    )
                )

                append(" RED=")
                append(
                    if (redOn) 1 else 0
                )

                append(" RBASE=")
                append(
                    "%.4f".format(
                        redTracker.baseline
                    )
                )

                append(" RTHR=")
                append(
                    "%.4f".format(
                        redTracker.threshold
                    )
                )

                append(" GREEN=")
                append(
                    if (greenOn) 1 else 0
                )

                append(" GBASE=")
                append(
                    "%.4f".format(
                        greenTracker.baseline
                    )
                )

                append(" GTHR=")
                append(
                    "%.4f".format(
                        greenTracker.threshold
                    )
                )

                append(" GLOC=")
                append(
                    greenLocation
                )

                append(" RLOC=")
                append(
                    redLocation
                )
            }
        )
    }

    /**
     * Legacy center sampling retained as a fallback before the
     * GREEN transmitter location has been established.
     */
    private fun sampleCenterChroma(
        image: ImageProxy
    ): ChromaSample {
        val centerX =
            image.width / 2

        val centerY =
            image.height / 2

        return sampleRegionAroundCenter(
            image = image,
            centerX = centerX,
            centerY = centerY
        )
    }

    /**
     * Samples a rectangular full-image region and converts its
     * Y/U/V values into RED/GREEN chroma scores.
     */
    private fun sampleChromaRegion(
        image: ImageProxy,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): ChromaSample {
        val planes =
            image.planes

        if (planes.size < 3) {
            return ChromaSample(
                redness = 0f,
                greenness = 0f,
                luma = 0f,
                averageU = 0,
                averageV = 0
            )
        }

        val uPlane =
            planes[1]

        val vPlane =
            planes[2]

        val yPlane =
            planes[0]

        val uBuffer =
            uPlane.buffer

        val vBuffer =
            vPlane.buffer

        val yBuffer =
            yPlane.buffer

        val chromaWidth =
            (image.width + 1) / 2

        val chromaHeight =
            (image.height + 1) / 2

        if (
            chromaWidth <= 0 ||
            chromaHeight <= 0
        ) {
            return ChromaSample(
                redness = 0f,
                greenness = 0f,
                luma = 0f,
                averageU = 0,
                averageV = 0
            )
        }

        val safeLeft =
            left.coerceIn(
                0,
                image.width - 1
            )

        val safeRight =
            right.coerceIn(
                safeLeft,
                image.width - 1
            )

        val safeTop =
            top.coerceIn(
                0,
                image.height - 1
            )

        val safeBottom =
            bottom.coerceIn(
                safeTop,
                image.height - 1
            )

        val xChroma0 =
            (
                    safeLeft *
                            chromaWidth /
                            image.width
                    )
                .coerceIn(
                    0,
                    chromaWidth - 1
                )

        val xChroma1 =
            (
                    safeRight *
                            chromaWidth /
                            image.width
                    )
                .coerceIn(
                    xChroma0,
                    chromaWidth - 1
                )

        val yChroma0 =
            (
                    safeTop *
                            chromaHeight /
                            image.height
                    )
                .coerceIn(
                    0,
                    chromaHeight - 1
                )

        val yChroma1 =
            (
                    safeBottom *
                            chromaHeight /
                            image.height
                    )
                .coerceIn(
                    yChroma0,
                    chromaHeight - 1
                )

        val uPixelStride =
            uPlane.pixelStride

        val vPixelStride =
            vPlane.pixelStride

        val yPixelStride =
            yPlane.pixelStride

        val uRowStride =
            uPlane.rowStride

        val vRowStride =
            vPlane.rowStride

        val yRowStride =
            yPlane.rowStride

        var uSum = 0L
        var vSum = 0L
        var ySum = 0L

        var count = 0

        for (
        y in yChroma0..yChroma1 step 2
        ) {
            for (
            x in xChroma0..xChroma1 step 2
            ) {
                val uIndex =
                    y *
                            uRowStride +
                            x *
                            uPixelStride

                val vIndex =
                    y *
                            vRowStride +
                            x *
                            vPixelStride

                val fullX =
                    (
                            x * 2
                            )
                        .coerceIn(
                            0,
                            image.width - 1
                        )

                val fullY =
                    (
                            y * 2
                            )
                        .coerceIn(
                            0,
                            image.height - 1
                        )

                val yIndex =
                    fullY *
                            yRowStride +
                            fullX *
                            yPixelStride

                if (
                    uIndex in
                    0 until uBuffer.limit() &&
                    vIndex in
                    0 until vBuffer.limit() &&
                    yIndex in
                    0 until yBuffer.limit()
                ) {
                    uSum +=
                        uBuffer
                            .get(uIndex)
                            .toInt() and
                                0xFF

                    vSum +=
                        vBuffer
                            .get(vIndex)
                            .toInt() and
                                0xFF

                    ySum +=
                        yBuffer
                            .get(yIndex)
                            .toInt() and
                                0xFF

                    count++
                }
            }
        }

        if (count == 0) {
            return ChromaSample(
                redness = 0f,
                greenness = 0f,
                luma = 0f,
                averageU = 0,
                averageV = 0
            )
        }

        val averageU =
            (uSum / count).toInt()

        val averageV =
            (vSum / count).toInt()

        val averageY =
            ySum.toFloat() /
                    count

        return ChromaSample(
            redness =
                RednessScorer.score(
                    averageU,
                    averageV
                ),

            greenness =
                RednessScorer.greenScore(
                    averageU,
                    averageV
                ),

            luma =
                averageY,

            averageU =
                averageU,

            averageV =
                averageV
        )
    }

    private data class LocatedSample(
        val centerX: Int,
        val centerY: Int,
        val sample: ChromaSample
    )

    private data class GreenCandidate(
        val index: Int,
        val row: Int,
        val column: Int,
        val centerX: Int,
        val centerY: Int,
        val sample: ChromaSample,
        val score: Float,
        val delta: Float,
        val spatialMargin: Float
    )

    private data class GreenSearchResult(
        val best: GreenCandidate?,
        val onset: GreenCandidate?
    )

    data class ChromaSample(
        val redness: Float,
        val greenness: Float,
        val luma: Float,
        val averageU: Int,
        val averageV: Int
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