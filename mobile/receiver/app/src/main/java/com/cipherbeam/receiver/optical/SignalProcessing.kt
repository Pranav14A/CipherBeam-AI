package com.cipherbeam.receiver.optical

/**
 * Tracks ambient redness and detects strong optical LED pulses.
 *
 * The baseline moves slowly so that short red LED pulses do not
 * immediately become part of the ambient baseline.
 */
class SignalTracker(
    private val baselineAlpha: Float = 0.008f,
    private val attackAlpha: Float = 0.70f,
    private val releaseAlpha: Float = 0.20f,
    private val thresholdOffset: Float = 0.055f
) {
    var baseline: Float = 0f
        private set

    var envelope: Float = 0f
        private set

    var threshold: Float = thresholdOffset
        private set

    var isOn: Boolean = false
        private set

    private var initialized = false

    fun reset() {
        baseline = 0f
        envelope = 0f
        threshold = thresholdOffset
        isOn = false
        initialized = false
    }

    fun update(redness: Float): Result {
        val sample = redness.coerceIn(-1f, 1f)

        if (!initialized) {
            baseline = sample
            envelope = sample
            initialized = true
        } else {
            /*
             * Fast envelope:
             * - attack quickly follows a rising optical signal
             * - release is slower so the LED pulse remains detectable
             */
            val envelopeAlpha =
                if (sample > envelope) attackAlpha else releaseAlpha

            envelope += envelopeAlpha * (sample - envelope)

            /*
             * Slowly follow ambient conditions.
             *
             * Do not allow strong optical pulses to pull the baseline
             * upward too quickly.
             */
            if (sample <= baseline + thresholdOffset) {
                baseline += baselineAlpha * (sample - baseline)
            }
        }

        threshold = baseline + thresholdOffset
        isOn = envelope > threshold

        return Result(
            redness = sample,
            baseline = baseline,
            envelope = envelope,
            threshold = threshold,
            isOn = isOn
        )
    }

    data class Result(
        val redness: Float,
        val baseline: Float,
        val envelope: Float,
        val threshold: Float,
        val isOn: Boolean
    )
}

/**
 * Converts camera signal into timestamp-aligned 200 ms optical bits.
 *
 * Important:
 * - Does NOT generate bits while the receiver is idle.
 * - Waits for a sustained optical ON signal.
 * - The first bit window is anchored to the first confirmed ON signal.
 * - After locking, one bit is produced for every 200 ms window.
 */
class BitWindowAggregator(
    private val bitDurationNs: Long = 200_000_000L,
    private val startConfirmNs: Long = 50_000_000L
) {
    private var windowStartNs: Long? = null

    private var candidateStartNs: Long? = null

    private var onSamples = 0
    private var totalSamples = 0

    private var locked = false

    fun reset() {
        windowStartNs = null
        candidateStartNs = null
        onSamples = 0
        totalSamples = 0
        locked = false
    }

    fun isLocked(): Boolean = locked

    fun add(timestampNs: Long, bitOn: Boolean): Boolean? {
        /*
         * IDLE:
         *
         * Wait for the beginning of a real optical transmission.
         * We require the signal to remain ON for a short confirmation
         * period so a random camera spike does not start decoding.
         */
        if (!locked) {
            if (bitOn) {
                if (candidateStartNs == null) {
                    candidateStartNs = timestampNs
                }

                val candidateStart = candidateStartNs!!

                if (timestampNs - candidateStart >= startConfirmNs) {
                    locked = true

                    /*
                     * The first 200 ms bit interval starts from the
                     * confirmed optical rising edge.
                     */
                    windowStartNs = candidateStart

                    onSamples = 1
                    totalSamples = 1

                    candidateStartNs = null
                }
            } else {
                /*
                 * Optical signal disappeared before the confirmation
                 * period completed.
                 */
                candidateStartNs = null
            }

            return null
        }

        val start = windowStartNs ?: timestampNs.also {
            windowStartNs = it
        }

        /*
         * If a complete 200 ms window has elapsed, finalize that bit.
         */
        if (timestampNs - start >= bitDurationNs) {
            val completed =
                totalSamples > 0 &&
                        onSamples * 2 >= totalSamples

            /*
             * Start the next window at the current camera timestamp.
             * No catch-up windows are generated.
             */
            windowStartNs = timestampNs
            onSamples = 0
            totalSamples = 0

            onSamples += if (bitOn) 1 else 0
            totalSamples = 1

            return completed
        }

        onSamples += if (bitOn) 1 else 0
        totalSamples++

        return null
    }
}

object RednessScorer {

    /**
     * Brightness-independent red chroma score.
     *
     * For YUV camera frames:
     * - lower U
     * - higher V
     *
     * generally indicates stronger red chroma.
     */
    fun score(u: Int, v: Int): Float {
        return ((v - u) / 255f)
            .coerceIn(-1f, 1f)
    }

    fun normalizedScore(u: Int, v: Int): Float {
        return score(u, v)
    }

    /**
     * Green chroma score for YUV camera frames.
     *
     * GREEN is used only as the control/framing channel.
     *
     * A higher value indicates stronger green/cyan chroma
     * relative to red.
     */
    fun greenScore(u: Int, v: Int): Float {
        return ((u - v) / 255f)
            .coerceIn(-1f, 1f)
    }
}

/**
 * Tracks the GREEN control LED used for optical frame synchronization.
 *
 * GREEN is not data. It marks:
 * - the beginning of a transmission
 * - the end of a transmission
 *
 * This tracker is intentionally separate from SignalTracker because
 * RED and GREEN are independent optical channels.
 */
class GreenSignalTracker(
    private val baselineAlpha: Float = 0.008f,
    private val attackAlpha: Float = 0.70f,
    private val releaseAlpha: Float = 0.20f,
    private val thresholdOffset: Float = 0.055f
) {
    var baseline: Float = 0f
        private set

    var envelope: Float = 0f
        private set

    var threshold: Float = thresholdOffset
        private set

    var isOn: Boolean = false
        private set

    private var initialized = false

    fun reset() {
        baseline = 0f
        envelope = 0f
        threshold = thresholdOffset
        isOn = false
        initialized = false
    }

    fun update(greenness: Float): Result {
        val sample = greenness.coerceIn(-1f, 1f)

        if (!initialized) {
            baseline = sample
            envelope = sample
            initialized = true
        } else {
            /*
             * Fast envelope:
             * - attack quickly follows a rising green signal
             * - release is slower so the control pulse remains detectable
             */
            val envelopeAlpha =
                if (sample > envelope) attackAlpha else releaseAlpha

            envelope += envelopeAlpha * (sample - envelope)

            /*
             * Slowly follow ambient conditions.
             *
             * Do not allow strong green optical pulses to pull the
             * baseline upward too quickly.
             */
            if (sample <= baseline + thresholdOffset) {
                baseline += baselineAlpha * (sample - baseline)
            }
        }

        threshold = baseline + thresholdOffset
        isOn = envelope > threshold

        return Result(
            greenness = sample,
            baseline = baseline,
            envelope = envelope,
            threshold = threshold,
            isOn = isOn
        )
    }

    data class Result(
        val greenness: Float,
        val baseline: Float,
        val envelope: Float,
        val threshold: Float,
        val isOn: Boolean
    )
}