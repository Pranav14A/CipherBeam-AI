package com.cipherbeam.receiver.optical

import org.junit.Assert.assertEquals
import org.junit.Test

class OpticalDecoderTest {

    companion object {
        private const val BIT_DURATION_NS = 200_000_000L
        private const val GREEN_START_DURATION_NS = 600_000_000L
    }

    /**
     * Feeds one optical sample to the decoder.
     */
    private fun feed(
        decoder: OpticalDecoder,
        timestampNs: Long,
        redOn: Boolean,
        greenOn: Boolean
    ) {
        decoder.feedOpticalSample(
            timestampNs = timestampNs,
            redOn = redOn,
            greenOn = greenOn
        )
    }

    /**
     * Sends a GREEN pulse long enough to pass the decoder's
     * consecutive-sample confirmation requirement.
     *
     * The exact camera FPS is not relevant to the decoder unit test;
     * we provide several samples during the 600 ms GREEN pulse.
     */
    private fun feedGreenStart(
        decoder: OpticalDecoder,
        startNs: Long
    ): Long {

        feed(decoder, startNs, redOn = false, greenOn = true)
        feed(
            decoder,
            startNs + 200_000_000L,
            redOn = false,
            greenOn = true
        )
        feed(
            decoder,
            startNs + 400_000_000L,
            redOn = false,
            greenOn = true
        )

        return startNs + GREEN_START_DURATION_NS
    }

    /**
     * Sends the GREEN falling edge and the 200 ms start guard.
     */
    private fun beginData(
        decoder: OpticalDecoder,
        greenStartNs: Long
    ): Long {

        val greenOffNs = greenStartNs

        feed(
            decoder,
            greenOffNs,
            redOn = false,
            greenOn = false
        )

        return greenOffNs + BIT_DURATION_NS
    }

    /**
     * Sends one complete 200 ms RED bit window.
     *
     * We provide samples at the beginning, middle and end of the
     * window so the majority-vote logic has a clear result.
     */
    private fun feedBitWindow(
        decoder: OpticalDecoder,
        windowStartNs: Long,
        bit: Boolean
    ) {

        feed(
            decoder,
            windowStartNs,
            redOn = bit,
            greenOn = false
        )

        feed(
            decoder,
            windowStartNs + 80_000_000L,
            redOn = bit,
            greenOn = false
        )

        feed(
            decoder,
            windowStartNs + 160_000_000L,
            redOn = bit,
            greenOn = false
        )

        /*
         * This sample crosses the 200 ms boundary and causes the
         * completed window to be finalized.
         */
        feed(
            decoder,
            windowStartNs + BIT_DURATION_NS,
            redOn = bit,
            greenOn = false
        )
    }

    /**
     * Sends one ASCII character MSB first.
     */
    private fun feedAsciiByte(
        decoder: OpticalDecoder,
        windowStartNs: Long,
        value: Int
    ): Long {

        var currentWindow = windowStartNs

        for (shift in 7 downTo 0) {
            val bit = ((value shr shift) and 1) != 0

            feedBitWindow(
                decoder = decoder,
                windowStartNs = currentWindow,
                bit = bit
            )

            currentWindow += BIT_DURATION_NS
        }

        return currentWindow
    }

    /**
     * Sends the complete Phase 7 frame:
     *
     * GREEN START
     * GREEN OFF + 200 ms guard
     * RED DATA
     * RED OFF + 200 ms end guard
     * GREEN END
     */
    private fun feedFrame(
        decoder: OpticalDecoder,
        message: String
    ) {

        var timestampNs = 0L

        /*
         * GREEN START
         */
        timestampNs = feedGreenStart(
            decoder = decoder,
            startNs = timestampNs
        )

        /*
         * GREEN falling edge + START GUARD
         */
        timestampNs = beginData(
            decoder = decoder,
            greenStartNs = timestampNs
        )

        /*
         * RED DATA
         */
        for (character in message) {
            timestampNs = feedAsciiByte(
                decoder = decoder,
                windowStartNs = timestampNs,
                value = character.code
            )
        }

        /*
         * RED OFF END GUARD.
         *
         * The decoder must NOT interpret this as a data zero.
         */
        feed(
            decoder,
            timestampNs,
            redOn = false,
            greenOn = false
        )

        feed(
            decoder,
            timestampNs + 80_000_000L,
            redOn = false,
            greenOn = false
        )

        /*
         * GREEN END.
         */
        val greenEndNs =
            timestampNs + BIT_DURATION_NS

        feed(
            decoder,
            greenEndNs,
            redOn = false,
            greenOn = true
        )

        feed(
            decoder,
            greenEndNs + 200_000_000L,
            redOn = false,
            greenOn = true
        )

        feed(
            decoder,
            greenEndNs + 400_000_000L,
            redOn = false,
            greenOn = true
        )

        /*
         * GREEN turns OFF.
         */
        feed(
            decoder,
            greenEndNs + 600_000_000L,
            redOn = false,
            greenOn = false
        )
    }

    @Test
    fun decodesGreenFramedMessage() {

        val decoder = OpticalDecoder()

        feedFrame(
            decoder = decoder,
            message = "HI"
        )

        val snapshot = decoder.snapshot()

        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            snapshot.state
        )

        assertEquals(
            "HI",
            snapshot.completedMessage
        )
    }

    @Test
    fun decodesSingleCharacter() {

        val decoder = OpticalDecoder()

        feedFrame(
            decoder = decoder,
            message = "A"
        )

        val snapshot = decoder.snapshot()

        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            snapshot.state
        )

        assertEquals(
            "A",
            snapshot.completedMessage
        )
    }

    @Test
    fun redOffCanRepresentZeroBit() {

        val decoder = OpticalDecoder()

        /*
         * ASCII 'A' = 01000001.
         *
         * Several RED-OFF windows are therefore valid payload
         * bits and must not be interpreted as the end of the frame.
         */
        feedFrame(
            decoder = decoder,
            message = "A"
        )

        val snapshot = decoder.snapshot()

        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            snapshot.state
        )

        assertEquals(
            "A",
            snapshot.completedMessage
        )
    }

    @Test
    fun greenEndCompletesTransmission() {

        val decoder = OpticalDecoder()

        feedFrame(
            decoder = decoder,
            message = "HELLO"
        )

        val snapshot = decoder.snapshot()

        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            snapshot.state
        )

        assertEquals(
            "HELLO",
            snapshot.completedMessage
        )
    }

    @Test
    fun completionRequiresExplicitAcknowledgement() {

        val decoder = OpticalDecoder()

        feedFrame(
            decoder = decoder,
            message = "X"
        )

        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            decoder.snapshot().state
        )

        assertEquals(
            "X",
            decoder.snapshot().completedMessage
        )

        decoder.acknowledgeCompletion()

        assertEquals(
            OpticalDecoder.State.WAITING_FOR_START,
            decoder.snapshot().state
        )

        assertEquals(
            "",
            decoder.snapshot().message
        )

        assertEquals(
            null,
            decoder.snapshot().completedMessage
        )
    }

    @Test
    fun rejectsNonPrintablePayload() {

        val decoder = OpticalDecoder()

        var timestampNs = 0L

        /*
         * GREEN START
         */
        timestampNs = feedGreenStart(
            decoder = decoder,
            startNs = timestampNs
        )

        /*
         * GREEN OFF + START GUARD
         */
        timestampNs = beginData(
            decoder = decoder,
            greenStartNs = timestampNs
        )

        /*
         * Send byte 0x01, which is not printable ASCII.
         */
        timestampNs = feedAsciiByte(
            decoder = decoder,
            windowStartNs = timestampNs,
            value = 0x01
        )

        /*
         * Continue with the RED-OFF end guard.
         */
        feed(
            decoder,
            timestampNs,
            redOn = false,
            greenOn = false
        )

        feed(
            decoder,
            timestampNs + BIT_DURATION_NS,
            redOn = false,
            greenOn = true
        )

        feed(
            decoder,
            timestampNs + BIT_DURATION_NS + 200_000_000L,
            redOn = false,
            greenOn = true
        )

        feed(
            decoder,
            timestampNs + BIT_DURATION_NS + 400_000_000L,
            redOn = false,
            greenOn = true
        )

        assertEquals(
            OpticalDecoder.State.WAITING_FOR_START,
            decoder.snapshot().state
        )
    }
}