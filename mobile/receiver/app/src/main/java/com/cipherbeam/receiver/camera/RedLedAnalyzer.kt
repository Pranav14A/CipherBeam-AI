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
         * Performance instrumentation.
         *
         * These values are diagnostic only.
         * They do not affect decoding.
         */
        const val PERFORMANCE_TAG = "CipherBeamPerf"
        const val PERFORMANCE_WINDOW_NS = 1_000_000_000L
        const val PERFORMANCE_FRAME_GAP_THRESHOLD_NS = 50_000_000L

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
        const val RED_ACQUIRE_THRESHOLD = 0.15f
        const val RED_ACQUIRE_MARGIN = 0.04f

/*
 * Physical geometry constraint.
 *
 * RED is always physically LEFT of GREEN.
 *
 * A small margin is required so that RED candidates
 * immediately adjacent to GREEN are also rejected.
 */

        const val RED_MUST_BE_LEFT_MARGIN_FRACTION = 0.02f

        /*
         * Small tracking search around the expected RED position.
         *
         * This is deliberately much smaller than the old ±24% search.
         */
        const val RED_TRACK_SEARCH_RADIUS_FRACTION = 0.05f
        const val RED_TRACK_SEARCH_STEP_FRACTION = 0.025f
    }

    private var lastDiagnosticTimestampNs: Long? = null

    private var fpsWindowStartNs: Long? = null
    private var fpsFrameCount = 0

    private var lastLoggedRedOn = false
    private var lastLoggedGreenOn = false

    /*
     * Performance instrumentation state.
     *
     * These counters are diagnostic only and do not participate
     * in optical decoding.
     */
    private var perfWindowStartNs: Long? = null
    private var perfFrameCount = 0

    private var perfGreenTotalNs = 0L
    private var perfRedTotalNs = 0L
    private var perfTotalTotalNs = 0L

    private var perfFrameGapTotalNs = 0L
    private var perfMaxFrameGapNs = 0L
    private var perfGapOver50MsCount = 0

    private var perfPreviousFrameTimestampNs: Long? = null

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
    /*
    * RED localization state.
    *
    * GREEN is the primary spatial anchor.
    * The physical arrangement is always:
    *
    *      RED        GREEN
    *
    * Therefore RED is expected to be LEFT of GREEN.
    */
    private var redCenterX: Int? = null
    private var redCenterY: Int? = null

    /*
     * Learned RED position relative to GREEN.
     *
     * Example:
     * GREEN = 600,400
     * RED   = 500,400
     *
     * offsetX = -100
     * offsetY = 0
     */
    private var redOffsetX: Int? = null
    private var redOffsetY: Int? = null

    private var redGeometryLocked = false
    private var lastRedSearchCurrentRed = 0f
    private var lastRedSearchBestRed = 0f
    private var lastRedSearchReturnedRed = 0f

    private var lastRedSearchCurrentX: Int? = null
    private var lastRedSearchCurrentY: Int? = null
    private var lastRedSearchBestX: Int? = null
    private var lastRedSearchBestY: Int? = null

    private var lastRedSearchMoved = false
    private var lastLoggedRedTrackerOn = false

    /*
 * OpenCV diagnostic instrumentation.
 *
 * Diagnostic only:
 * - does not control RED/GREEN detection
 * - does not control decoding
 * - runs periodically, not every frame
 */
    private fun isRedPhysicallyLeftOfGreen(
        candidateX: Int,
        candidateY: Int,
        greenX: Int,
        greenY: Int,
        rotationDegrees: Int,
        minimumMarginPx: Int
    ): Boolean {
        val dx = candidateX - greenX
        val dy = candidateY - greenY

        return when (rotationDegrees) {
            0 -> {
                dx <= -minimumMarginPx
            }

            90 -> {
                dy >= minimumMarginPx
            }

            180 -> {
                dx >= minimumMarginPx
            }

            270 -> {
                dy <= -minimumMarginPx
            }

            else -> {
                false
            }
        }
    }
    private val openCvDiagnosticProcessor =
        OpenCvSignalProcessor()

    private var lastOpenCvDiagnosticTimestampNs: Long? = null

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        Log.d(
            "CipherBeamFrameInfo",
            "width=${image.width} " +
                    "height=${image.height} " +
                    "cropRect=${image.cropRect} " +
                    "rotation=${image.imageInfo.rotationDegrees}"
        )

        /*
         * Performance timing starts after the busy guard.
         *
         * This measures the actual work performed by the analyzer
         * for an accepted frame.
         */
        val analyzerStartNs =
            System.nanoTime()

        val frameTimestampNs =
            image.imageInfo.timestamp

        /*
         * These are initialized before the try block so that every
         * normal analyzer path has valid timing values.
         */
        var greenElapsedNs = 0L
        var redElapsedNs = 0L

        try {
            /*
             * First perform GREEN localization.
             *
             * Both GREEN START and GREEN END create a rising edge,
             * so this same mechanism can relocate the transmitter
             * after camera movement.
             */
            val greenStartNs =
                System.nanoTime()

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
                redTracker.reset()

                /*
                 * Re-anchor RED search to the newly detected GREEN position.
                 */
                val lockedOffsetX = redOffsetX
                val lockedOffsetY = redOffsetY

                if (
                    redGeometryLocked &&
                    lockedOffsetX != null &&
                    lockedOffsetY != null
                ) {
                    /*
                     * Follow RED using the learned RED↔GREEN relationship.
                     */
                    redCenterX =
                        (
                                greenOnset.centerX +
                                        lockedOffsetX
                                ).coerceIn(
                                0,
                                image.width - 1
                            )

                    redCenterY =
                        (
                                greenOnset.centerY +
                                        lockedOffsetY
                                ).coerceIn(
                                0,
                                image.height - 1
                            )
                } else {
                    /*
                     * RED has not been acquired yet.
                     *
                     * Start from GREEN and let the acquisition search
                     * find the actual RED position.
                     */
                    redCenterX = greenOnset.centerX
                    redCenterY = greenOnset.centerY
                }
                Log.d(
                    "CipherBeamGeometry",
                    "GREEN=${greenCenterX}x${greenCenterY} " +
                            "RED=${redCenterX}x${redCenterY} " +
                            "offsetX=${redOffsetX} " +
                            "offsetY=${redOffsetY} " +
                            "geometryLocked=$redGeometryLocked"
                )
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
             * GREEN timing includes:
             *
             * - 5 × 5 GREEN grid search
             * - GREEN onset evaluation
             * - localized GREEN sampling
             */
            greenElapsedNs =
                System.nanoTime() - greenStartNs

            /*
             * RED starts from the GREEN position until an actual RED
             * optical signal provides a stronger location.
             */
            val redStartNs =
                System.nanoTime()

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

            /*
             * RED timing includes:
             *
             * - current RED ROI sample
             * - 5 × 5 local search
             * - RED tracking-center update
             */
            redElapsedNs =
                System.nanoTime() - redStartNs

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
            /*
 * OpenCV RED diagnostic.
 *
 * Uses the exact RED ROI selected by the existing analyzer.
 * This does NOT replace or modify the current YUV RED signal.
 */
            val previousOpenCvDiagnosticTimestampNs =
                lastOpenCvDiagnosticTimestampNs

            val shouldRunOpenCvDiagnostic =
                previousOpenCvDiagnosticTimestampNs == null ||
                        frameTimestampNs -
                        previousOpenCvDiagnosticTimestampNs >=
                        DIAGNOSTIC_INTERVAL_NS

            if (shouldRunOpenCvDiagnostic) {
                val openCvResult =
                    openCvDiagnosticProcessor.process(
                        image = image,
                        centerX = redLocatedSample.centerX,
                        centerY = redLocatedSample.centerY
                    )

                if (openCvResult.valid) {
                    Log.d(
                        "CipherBeamOpenCvDiag",
                        "RED_ROI " +
                                "frame=${image.width}x${image.height} " +
                                "center=${redLocatedSample.centerX}x${redLocatedSample.centerY} " +
                                "roi=${openCvResult.roiLeft}," +
                                "${openCvResult.roiTop}," +
                                "${openCvResult.roiWidth}x" +
                                "${openCvResult.roiHeight} " +
                                "YUV_RED=${"%.4f".format(redLocatedSample.sample.redness)} " +
                                "RGB_R=${"%.1f".format(openCvResult.meanRed)} " +
                                "RGB_G=${"%.1f".format(openCvResult.meanGreen)} " +
                                "RGB_B=${"%.1f".format(openCvResult.meanBlue)} " +
                                "RGB_RED_DOM=${"%.4f".format(openCvResult.redDominance)}"
                    )
                } else {
                    Log.d(
                        "CipherBeamOpenCvDiag",
                        "RED_ROI INVALID " +
                                "frame=${image.width}x${image.height} " +
                                "center=${redLocatedSample.centerX}x${redLocatedSample.centerY}"
                    )
                }

                lastOpenCvDiagnosticTimestampNs =
                    frameTimestampNs
            }

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

            if (redTracked.isOn != lastLoggedRedTrackerOn) {
                Log.d(
                    "CipherBeamRedDiag",
                    "RED_STATE " +
                            "tracker=${if (redTracked.isOn) 1 else 0} " +
                            "current=${"%.3f".format(lastRedSearchCurrentRed)} " +
                            "best=${"%.3f".format(lastRedSearchBestRed)} " +
                            "returned=${"%.3f".format(lastRedSearchReturnedRed)} " +
                            "currentLoc=${lastRedSearchCurrentX}x${lastRedSearchCurrentY} " +
                            "bestLoc=${lastRedSearchBestX}x${lastRedSearchBestY} " +
                            "moved=${lastRedSearchMoved} " +
                            "redCenter=${redCenterX}x${redCenterY}"
                )

                lastLoggedRedTrackerOn =
                    redTracked.isOn
            }

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

            /*
             * Performance timing is intentionally recorded after
             * the existing diagnostic/debug callbacks.
             *
             * Therefore totalMs represents the complete analyzer
             * work for this accepted frame.
             */
            val analyzerTotalElapsedNs =
                System.nanoTime() -
                        analyzerStartNs

            logPerformanceSample(
                frameTimestampNs =
                    frameTimestampNs,

                greenElapsedNs =
                    greenElapsedNs,

                redElapsedNs =
                    redElapsedNs,

                totalElapsedNs =
                    analyzerTotalElapsedNs
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

        /*
         * Reset performance instrumentation together with
         * the optical signal state.
         */
        perfWindowStartNs = null
        perfFrameCount = 0

        perfGreenTotalNs = 0L
        perfRedTotalNs = 0L
        perfTotalTotalNs = 0L

        perfFrameGapTotalNs = 0L
        perfMaxFrameGapNs = 0L
        perfGapOver50MsCount = 0

        perfPreviousFrameTimestampNs = null

        lastLoggedRedOn = false
        lastLoggedGreenOn = false
        lastLoggedRedTrackerOn = false

        greenCenterX = null
        greenCenterY = null

        redCenterX = null
        redCenterY = null

        lastRedSearchCurrentRed = 0f
        lastRedSearchBestRed = 0f
        lastRedSearchReturnedRed = 0f

        lastRedSearchCurrentX = null
        lastRedSearchCurrentY = null
        lastRedSearchBestX = null
        lastRedSearchBestY = null

        lastRedSearchMoved = false
        lastOpenCvDiagnosticTimestampNs = null

        previousGreenScores =
            FloatArray(
                GREEN_GRID_COLUMNS *
                        GREEN_GRID_ROWS
            )

        greenHistoryInitialized = false
    }

    /**
     * Performance instrumentation.
     *
     * This function:
     *
     * - measures processed FPS
     * - measures camera frame timestamp gaps
     * - measures GREEN processing time
     * - measures RED processing time
     * - measures total analyzer processing time
     * - counts frame gaps greater than 50 ms
     *
     * It logs approximately once per second.
     *
     * It does not affect optical decoding.
     */
    private fun logPerformanceSample(
        frameTimestampNs: Long,
        greenElapsedNs: Long,
        redElapsedNs: Long,
        totalElapsedNs: Long
    ) {
        val nowNs =
            System.nanoTime()

        if (perfWindowStartNs == null) {
            perfWindowStartNs =
                nowNs
        }

        perfFrameCount++

        perfGreenTotalNs +=
            greenElapsedNs

        perfRedTotalNs +=
            redElapsedNs

        perfTotalTotalNs +=
            totalElapsedNs

        val previousFrameTimestampNs =
            perfPreviousFrameTimestampNs

        if (
            previousFrameTimestampNs != null &&
            frameTimestampNs >=
            previousFrameTimestampNs
        ) {
            val frameGapNs =
                frameTimestampNs -
                        previousFrameTimestampNs

            perfFrameGapTotalNs +=
                frameGapNs

            if (
                frameGapNs >
                perfMaxFrameGapNs
            ) {
                perfMaxFrameGapNs =
                    frameGapNs
            }

            if (
                frameGapNs >
                PERFORMANCE_FRAME_GAP_THRESHOLD_NS
            ) {
                perfGapOver50MsCount++
            }
        }

        perfPreviousFrameTimestampNs =
            frameTimestampNs

        val windowStartNs =
            perfWindowStartNs
                ?: nowNs

        val elapsedWindowNs =
            nowNs - windowStartNs

        if (
            elapsedWindowNs <
            PERFORMANCE_WINDOW_NS
        ) {
            return
        }

        val elapsedWindowSeconds =
            elapsedWindowNs /
                    1_000_000_000.0

        val processedFps =
            if (
                elapsedWindowSeconds > 0.0
            ) {
                perfFrameCount /
                        elapsedWindowSeconds
            } else {
                0.0
            }

        val gapCount =
            max(
                0,
                perfFrameCount - 1
            )

        val avgFrameGapMs =
            if (gapCount > 0) {
                perfFrameGapTotalNs /
                        gapCount /
                        1_000_000.0
            } else {
                0.0
            }

        val avgGreenMs =
            if (perfFrameCount > 0) {
                perfGreenTotalNs /
                        perfFrameCount /
                        1_000_000.0
            } else {
                0.0
            }

        val avgRedMs =
            if (perfFrameCount > 0) {
                perfRedTotalNs /
                        perfFrameCount /
                        1_000_000.0
            } else {
                0.0
            }

        val avgTotalMs =
            if (perfFrameCount > 0) {
                perfTotalTotalNs /
                        perfFrameCount /
                        1_000_000.0
            } else {
                0.0
            }

        val maxFrameGapMs =
            perfMaxFrameGapNs /
                    1_000_000.0

        Log.i(
            PERFORMANCE_TAG,
            "frames=$perfFrameCount " +
                    "fps=${"%.1f".format(processedFps)} " +
                    "avgGapMs=${"%.1f".format(avgFrameGapMs)} " +
                    "maxGapMs=${"%.1f".format(maxFrameGapMs)} " +
                    "gapsOver50=$perfGapOver50MsCount " +
                    "greenMs=${"%.2f".format(avgGreenMs)} " +
                    "redMs=${"%.2f".format(avgRedMs)} " +
                    "totalMs=${"%.2f".format(avgTotalMs)}"
        )

        /*
         * Start a fresh performance window.
         */
        perfWindowStartNs =
            nowNs

        perfFrameCount = 0

        perfGreenTotalNs = 0L
        perfRedTotalNs = 0L
        perfTotalTotalNs = 0L

        perfFrameGapTotalNs = 0L
        perfMaxFrameGapNs = 0L
        perfGapOver50MsCount = 0

        /*
         * Keep the previous camera timestamp.
         *
         * This allows the first frame of the next performance
         * window to still contribute a valid frame gap.
         */
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

            greenHistoryInitialized =
                true

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

        var bestCandidate:
                GreenCandidate? =
            null

        var onsetCandidate:
                GreenCandidate? =
            null

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
                score -
                        median

            val row =
                index /
                        GREEN_GRID_COLUMNS

            val column =
                index %
                        GREEN_GRID_COLUMNS

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

        /*
         * Diagnostic only.
         */
        lastRedSearchCurrentRed =
            currentRed

        lastRedSearchCurrentX =
            current.centerX

        lastRedSearchCurrentY =
            current.centerY

        lastRedSearchMoved =
            false

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

                val currentGreenX =
                    greenCenterX

                val currentGreenY =
                    greenCenterY

                if (
                    currentGreenX != null &&
                    currentGreenY != null
                ) {
                    val minimumLeftMarginPx =
                        (
                                image.width *
                                        RED_MUST_BE_LEFT_MARGIN_FRACTION
                                ).toInt()

                    val physicallyLeft =
                        isRedPhysicallyLeftOfGreen(
                            candidateX = candidateCenterX,
                            candidateY = candidateCenterY,
                            greenX = currentGreenX,
                            greenY = currentGreenY,
                            rotationDegrees =
                                image.imageInfo.rotationDegrees,
                            minimumMarginPx =
                                minimumLeftMarginPx
                        )

                    if (!physicallyLeft) {
                        continue
                    }
                }

                val sample =
                    sampleRegionAroundCenter(
                        image = image,
                        centerX = candidateCenterX,
                        centerY = candidateCenterY
                    )

                val candidateRed =
                    sample.redness
                        .coerceAtLeast(0f)

                val bestRedSoFar =
                    best.sample.redness
                        .coerceAtLeast(0f)

                if (
                    candidateRed >
                    bestRedSoFar
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

        /*
         * Evaluate the FINAL best candidate after
         * the entire search has completed.
         */
        val bestRed =
            best.sample.redness
                .coerceAtLeast(0f)

        /*
         * RED SEARCH DIAGNOSTIC.
         *
         * This is intentionally placed AFTER the complete
         * 5 × 5 search and BEFORE acquisition/geometry locking.
         *
         * It does not affect detection or decoding.
         */
        Log.d(
            "CipherBeamRedSearch",
            "current=${current.centerX}x${current.centerY} " +
                    "currentRed=${"%.3f".format(currentRed)} " +
                    "best=${best.centerX}x${best.centerY} " +
                    "bestRed=${"%.3f".format(bestRed)}"
        )

        /*
         * Diagnostic only.
         */
        lastRedSearchBestRed =
            bestRed

        lastRedSearchBestX =
            best.centerX

        lastRedSearchBestY =
            best.centerY

        /*
         * Only move the RED tracking center when the new location
         * provides meaningful evidence above the current location.
         *
         * Existing behavior unchanged.
         */
        if (
            !redGeometryLocked &&
            bestRed >= RED_ACQUIRE_THRESHOLD &&
            bestRed >=
            currentRed +
            RED_ACQUIRE_MARGIN
        ) {
            val currentGreenX =
                greenCenterX

            val currentGreenY =
                greenCenterY

            if (
                currentGreenX != null &&
                currentGreenY != null
            ) {
                val candidateOffsetX =
                    best.centerX - currentGreenX

                val candidateOffsetY =
                    best.centerY - currentGreenY

                val physicallyLeft =
                    isRedPhysicallyLeftOfGreen(
                        candidateX = best.centerX,
                        candidateY = best.centerY,
                        greenX = currentGreenX,
                        greenY = currentGreenY,
                        rotationDegrees =
                            image.imageInfo.rotationDegrees,
                        minimumMarginPx =
                            (
                                    image.width *
                                            RED_MUST_BE_LEFT_MARGIN_FRACTION
                                    ).toInt()
                    )

                if (physicallyLeft) {
                    redCenterX =
                        best.centerX

                    redCenterY =
                        best.centerY

                    redOffsetX =
                        candidateOffsetX

                    redOffsetY =
                        candidateOffsetY

                    redGeometryLocked =
                        true

                    Log.d(
                        "CipherBeamGeometry",
                        "LOCKED " +
                                "GREEN=${currentGreenX}x${currentGreenY} " +
                                "RED=${best.centerX}x${best.centerY} " +
                                "offsetX=$candidateOffsetX " +
                                "offsetY=$candidateOffsetY " +
                                "rotation=${image.imageInfo.rotationDegrees} " +
                                "redness=${"%.3f".format(bestRed)}"
                    )
                }
            }
        }

        /*
         * Use the stronger local sample for the current frame when
         * there is enough RED evidence to justify it.
         *
         * Existing selection logic unchanged.
         */
        val useBest =
            bestRed >= RED_SAMPLE_MIN &&
                    bestRed >
                    currentRed +
                    0.02f

        /*
         * Diagnostic only.
         */
        lastRedSearchReturnedRed =
            if (useBest) {
                bestRed
            } else {
                currentRed
            }

        return if (useBest) {
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
        val unusedWidthGuard =
            width

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