package com.cipherbeam.receiver.optical

import android.util.Log

/**
 * Phase 8 optical protocol decoder.
 *
 * Physical channels:
 *
 * GREEN = control / frame synchronization
 * RED   = data
 *
 * Frame:
 *
 * GREEN ON  ~600 ms
 * GREEN OFF ~200 ms guard
 * RED data  8-bit ASCII, MSB-first, 200 ms per bit
 * RED OFF   ~200 ms end guard
 * GREEN ON  ~600 ms
 * GREEN OFF
 *
 * RED OFF during the data section is a valid binary 0.
 * GREEN is the only signal that marks frame START and END.
 *
 * Phase 8 robustness:
 *
 * RED camera detection can briefly flicker when the phone angle
 * changes or the camera exposure shifts.
 *
 * A short 50 ms temporal stability filter is therefore applied
 * before RED samples enter the 200 ms bit window.
 *
 * This is intentionally much shorter than the 200 ms bit duration,
 * so genuine bit transitions remain detectable while very short
 * RED glitches are suppressed.
 */
class OpticalDecoder(
    private val maxMessageLength: Int = 100
) {

    enum class State {
        WAITING_FOR_START,
        START_DETECTED,
        WAITING_FOR_DATA,
        RECEIVING_PAYLOAD,
        MESSAGE_COMPLETE
    }

    data class Snapshot(
        val state: State,
        val message: String,
        val lastByte: Int? = null,
        val completedMessage: String? = null
    )

    companion object {

        private const val BIT_DURATION_NS = 200_000_000L

        /*
         * Require several consecutive GREEN camera samples before
         * accepting GREEN as a real control signal.
         */
        private const val GREEN_CONFIRM_SAMPLES = 3

        /*
         * RED must remain in the new state for this duration before
         * the decoder accepts the transition.
         *
         * 50 ms is intentionally much shorter than the 200 ms
         * optical bit duration.
         */
        private const val RED_STABILITY_DURATION_NS = 50_000_000L
    }

    private var state = State.WAITING_FOR_START

    private val message = StringBuilder()

    /*
     * GREEN start/end detection.
     */
    private var greenCandidateStartNs: Long? = null
    private var greenCandidateSamples = 0

    /*
     * After GREEN START falls, wait exactly one 200 ms guard
     * before beginning RED data collection.
     */
    private var dataStartNs: Long? = null

    /*
     * RED data window.
     *
     * Each window represents one 200 ms optical bit.
     */
    private var dataWindowStartNs: Long? = null

    private var redOnSamples = 0
    private var redTotalSamples = 0

    /*
     * RED temporal stability filter.
     *
     * filteredRedOn is the RED state that is actually allowed
     * into the 200 ms bit accumulator.
     *
     * A raw RED transition must remain present for
     * RED_STABILITY_DURATION_NS before it is accepted.
     */
    private var filteredRedOn = false
    private var redCandidateState: Boolean? = null
    private var redCandidateStartNs: Long? = null

    /*
     * Completed 200 ms RED windows are temporarily held.
     *
     * The final RED-OFF window is the END GUARD and must not
     * be decoded as a data zero.
     */
    private val completedBitWindows = ArrayList<Boolean>()

    /*
     * Current byte being assembled from RED bits.
     */
    private var currentByte = 0
    private var currentByteBitCount = 0

    /**
     * Reset the decoder to the initial state.
     */
    fun reset() {
        state = State.WAITING_FOR_START

        message.clear()

        greenCandidateStartNs = null
        greenCandidateSamples = 0

        dataStartNs = null

        dataWindowStartNs = null
        redOnSamples = 0
        redTotalSamples = 0

        filteredRedOn = false
        redCandidateState = null
        redCandidateStartNs = null

        completedBitWindows.clear()

        currentByte = 0
        currentByteBitCount = 0
    }

    /**
     * Feed one camera analysis sample into the Phase 8 decoder.
     *
     * @param timestampNs camera frame timestamp
     * @param redOn detected RED optical state
     * @param greenOn detected GREEN optical state
     */
    fun feedOpticalSample(
        timestampNs: Long,
        redOn: Boolean,
        greenOn: Boolean
    ): Snapshot {

        /*
         * Stabilize RED before the state-specific decoder consumes it.
         *
         * GREEN handling remains unchanged because GREEN is used
         * for frame synchronization rather than data bits.
         */
        val stableRedOn =
            stabilizeRedState(
                timestampNs = timestampNs,
                rawRedOn = redOn
            )

        when (state) {

            State.WAITING_FOR_START -> {
                handleWaitingForStart(
                    timestampNs = timestampNs,
                    greenOn = greenOn
                )
            }

            State.START_DETECTED -> {
                handleStartDetected(
                    timestampNs = timestampNs,
                    greenOn = greenOn
                )
            }

            State.WAITING_FOR_DATA -> {
                handleWaitingForData(
                    timestampNs = timestampNs,
                    redOn = stableRedOn,
                    greenOn = greenOn
                )
            }

            State.RECEIVING_PAYLOAD -> {
                handleReceivingData(
                    timestampNs = timestampNs,
                    redOn = stableRedOn,
                    greenOn = greenOn
                )
            }

            State.MESSAGE_COMPLETE -> {
                /*
                 * The message has been decoded, but GREEN END may still
                 * be physically ON. Do not reset while that end marker
                 * is active, otherwise the same GREEN pulse can be
                 * mistaken for a new GREEN START.
                 *
                 * Once GREEN turns OFF, re-arm the decoder for the next
                 * transmission.
                 */
                if (!greenOn) {
                    reset()
                }
            }
        }

        return snapshot()
    }

    /**
     * Stabilize the RED signal against short camera-frame glitches.
     *
     * A raw RED transition must remain continuously present for
     * RED_STABILITY_DURATION_NS before the filtered RED state changes.
     *
     * This prevents short RED flicker from consuming samples in the
     * wrong 200 ms bit window.
     */
    private fun stabilizeRedState(
        timestampNs: Long,
        rawRedOn: Boolean
    ): Boolean {

        /*
         * No transition is currently pending.
         *
         * The raw state already matches the filtered state.
         */
        if (rawRedOn == filteredRedOn) {
            redCandidateState = null
            redCandidateStartNs = null

            return filteredRedOn
        }

        /*
         * A new candidate transition has started.
         */
        if (redCandidateState != rawRedOn) {
            redCandidateState = rawRedOn
            redCandidateStartNs = timestampNs

            return filteredRedOn
        }

        val candidateStart =
            redCandidateStartNs ?: run {
                redCandidateStartNs = timestampNs
                return filteredRedOn
            }

        /*
         * Accept the transition only after the new RED state has
         * remained stable long enough.
         */
        if (
            timestampNs - candidateStart >=
            RED_STABILITY_DURATION_NS
        ) {
            filteredRedOn = rawRedOn

            redCandidateState = null
            redCandidateStartNs = null
        }

        return filteredRedOn
    }

    /**
     * GREEN START detection.
     */
    private fun handleWaitingForStart(
        timestampNs: Long,
        greenOn: Boolean
    ) {
        if (!greenOn) {
            greenCandidateStartNs = null
            greenCandidateSamples = 0
            return
        }

        if (greenCandidateStartNs == null) {
            greenCandidateStartNs = timestampNs
            greenCandidateSamples = 1
        } else {
            greenCandidateSamples++
        }

        if (greenCandidateSamples >= GREEN_CONFIRM_SAMPLES) {
            state = State.START_DETECTED
        }
    }

    /**
     * GREEN START has been confirmed.
     *
     * Wait for GREEN to turn OFF.
     */
    private fun handleStartDetected(
        timestampNs: Long,
        greenOn: Boolean
    ) {
        if (greenOn) {
            return
        }

        /*
         * GREEN has turned OFF.
         *
         * The protocol requires a 200 ms guard before the
         * first RED data bit.
         */
        dataStartNs = timestampNs + BIT_DURATION_NS

        dataWindowStartNs = null
        redOnSamples = 0
        redTotalSamples = 0

        completedBitWindows.clear()

        currentByte = 0
        currentByteBitCount = 0

        state = State.WAITING_FOR_DATA

        greenCandidateStartNs = null
        greenCandidateSamples = 0

        /*
         * Start each transmission with a clean RED temporal filter.
         *
         * The optical protocol guarantees RED is OFF during GREEN
         * and during the 200 ms start guard.
         */
        filteredRedOn = false
        redCandidateState = null
        redCandidateStartNs = null
    }

    /**
     * Wait for the 200 ms START guard to finish.
     */
    private fun handleWaitingForData(
        timestampNs: Long,
        redOn: Boolean,
        greenOn: Boolean
    ) {
        /*
         * If GREEN returns before data begins, the frame is malformed.
         */
        if (greenOn) {
            reset()
            return
        }

        val start = dataStartNs ?: return

        if (timestampNs < start) {
            return
        }

        /*
         * The 200 ms guard has finished.
         *
         * Anchor the first RED bit window to the exact expected
         * end of the guard.
         */
        dataWindowStartNs = start

        redOnSamples = 0
        redTotalSamples = 0

        state = State.RECEIVING_PAYLOAD

        /*
         * The current camera frame is the first available sample
         * inside the RED data region.
         */
        addRedSample(
            timestampNs = timestampNs,
            redOn = redOn
        )
    }

    /**
     * Receive RED data while GREEN remains OFF.
     */
    private fun handleReceivingData(
        timestampNs: Long,
        redOn: Boolean,
        greenOn: Boolean
    ) {
        /*
         * GREEN indicates the beginning of the END section.
         *
         * IMPORTANT:
         *
         * The final RED-OFF guard is still represented by the
         * current RED window. Because GREEN has now appeared,
         * there will be no later RED sample to finalize that
         * window.
         *
         * Therefore we explicitly finalize the RED window at
         * the GREEN timestamp before completing the transmission.
         */
        if (greenOn) {

            if (greenCandidateStartNs == null) {
                greenCandidateStartNs = timestampNs
                greenCandidateSamples = 1
            } else {
                greenCandidateSamples++
            }

            /*
             * Finalize any RED window that has elapsed before
             * the GREEN END pulse.
             *
             * RED is OFF during the end guard, so feeding
             * redOn = false here is correct.
             */
            addRedSample(
                timestampNs = timestampNs,
                redOn = false
            )

            if (greenCandidateSamples >= GREEN_CONFIRM_SAMPLES) {
                completeTransmission()
            }

            return
        }

        /*
         * GREEN disappeared again before confirmation.
         * Continue receiving RED data.
         */
        greenCandidateStartNs = null
        greenCandidateSamples = 0

        addRedSample(
            timestampNs = timestampNs,
            redOn = redOn
        )
    }

    /**
     * Add one RED camera sample to the current 200 ms bit window.
     */
    private fun addRedSample(
        timestampNs: Long,
        redOn: Boolean
    ) {
        var windowStart = dataWindowStartNs ?: return

        /*
         * Finalize every complete 200 ms window that has elapsed.
         */
        while (timestampNs - windowStart >= BIT_DURATION_NS) {

            if (redTotalSamples > 0) {

                val bit =
                    redOnSamples * 2 >= redTotalSamples

                completedBitWindows.add(bit)

                /*
                 * Keep the buffer bounded.
                 *
                 * 100 printable characters × 8 bits,
                 * plus one possible end-guard window.
                 */
                if (
                    completedBitWindows.size >
                    (maxMessageLength * 8 + 1)
                ) {
                    reset()
                    return
                }
            }

            windowStart += BIT_DURATION_NS

            redOnSamples = 0
            redTotalSamples = 0
        }

        dataWindowStartNs = windowStart

        redOnSamples += if (redOn) 1 else 0
        redTotalSamples++
    }

    /**
     * GREEN END has been confirmed.
     *
     * Depending on the exact camera sampling moment, the final
     * 200 ms RED-OFF end guard may or may not have become a
     * completed RED window yet.
     *
     * Therefore:
     *
     *   completedBitWindows.size % 8 == 0
     *       -> all completed windows are payload data
     *
     *   completedBitWindows.size % 8 == 1
     *       -> the final completed window is the RED-OFF end guard
     *
     * Any other remainder means the payload is not byte-aligned
     * and the frame is considered invalid.
     */
    private fun completeTransmission() {

        Log.d(
            "CipherBeamDecoder",
            "COMPLETE attempt: completedWindows=${completedBitWindows.size}"
        )

        if (completedBitWindows.isEmpty()) {
            Log.d(
                "CipherBeamDecoder",
                "COMPLETE FAILED: no completed RED windows"
            )
            reset()
            return
        }

        val completedWindowCount =
            completedBitWindows.size

        /*
         * Depending on when GREEN END is detected:
         *
         * remainder 0:
         *   the RED-OFF end guard is still the current window
         *
         * remainder 1:
         *   the RED-OFF end guard has already completed
         */
        val dataBitCount =
            when {

                completedWindowCount % 8 == 0 -> {
                    completedWindowCount
                }

                completedWindowCount % 8 == 1 -> {
                    completedWindowCount - 1
                }

                else -> {
                    Log.d(
                        "CipherBeamDecoder",
                        "COMPLETE FAILED: invalid window count " +
                                "$completedWindowCount " +
                                "(remainder=${completedWindowCount % 8})"
                    )

                    reset()
                    return
                }
            }

        Log.d(
            "CipherBeamDecoder",
            "dataBitCount=$dataBitCount"
        )

        if (dataBitCount <= 0) {
            Log.d(
                "CipherBeamDecoder",
                "COMPLETE FAILED: dataBitCount <= 0"
            )

            reset()
            return
        }

        /*
         * Print the exact RED bit sequence that the phone decoded.
         */
        val bitString =
            completedBitWindows
                .take(dataBitCount)
                .joinToString(separator = "") { bit ->
                    if (bit) "1" else "0"
                }

        Log.d(
            "CipherBeamDecoder",
            "DECODED BITS=$bitString"
        )

        message.clear()

        currentByte = 0
        currentByteBitCount = 0

        for (index in 0 until dataBitCount) {

            val bit =
                completedBitWindows[index]

            if (!consumeDataBit(bit)) {

                Log.d(
                    "CipherBeamDecoder",
                    "COMPLETE FAILED: invalid printable ASCII " +
                            "at bit index=$index " +
                            "messageSoFar='$message'"
                )

                reset()
                return
            }
        }

        if (currentByteBitCount != 0) {

            Log.d(
                "CipherBeamDecoder",
                "COMPLETE FAILED: incomplete final byte, " +
                        "currentByteBitCount=$currentByteBitCount"
            )

            reset()
            return
        }

        val completed =
            message.toString()

        Log.d(
            "CipherBeamDecoder",
            "COMPLETE SUCCESS: message='$completed'"
        )

        state = State.MESSAGE_COMPLETE

        greenCandidateStartNs = null
        greenCandidateSamples = 0

        dataStartNs = null
        dataWindowStartNs = null
        redOnSamples = 0
        redTotalSamples = 0
    }

    private fun consumeDataBit(
        bit: Boolean
    ): Boolean {

        currentByte =
            (
                    (currentByte shl 1) or
                            if (bit) 1 else 0
                    ) and 0xFF

        currentByteBitCount++

        if (currentByteBitCount < 8) {
            return true
        }

        val byteValue =
            currentByte

        currentByte = 0
        currentByteBitCount = 0

        /*
         * Phase 7 accepts printable ASCII only.
         */
        if (byteValue !in 0x20..0x7E) {
            return false
        }

        /*
         * Do not allow a frame to exceed the configured maximum.
         */
        if (message.length >= maxMessageLength) {
            return false
        }

        message.append(
            byteValue.toChar()
        )

        return true
    }

    /**
     * Legacy compatibility method.
     *
     * The old RED-only Phase 7 decoder used feedBit().
     *
     * The new protocol MUST use feedOpticalSample().
     */
    @Deprecated(
        message = "Use feedOpticalSample(timestampNs, redOn, greenOn)"
    )
    fun feedBit(
        bit: Boolean
    ): Snapshot {
        /*
         * Do not decode RED-only bits anymore.
         */
        return snapshot()
    }

    /**
     * Caller explicitly chooses when to begin waiting for another
     * message.
     */
    fun acknowledgeCompletion() {
        if (state == State.MESSAGE_COMPLETE) {
            reset()
        }
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            state = state,
            message = message.toString(),
            lastByte = null,
            completedMessage =
                if (state == State.MESSAGE_COMPLETE) {
                    message.toString()
                } else {
                    null
                }
        )
    }
}