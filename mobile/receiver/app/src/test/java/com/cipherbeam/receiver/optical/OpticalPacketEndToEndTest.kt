package com.cipherbeam.receiver.optical

import com.cipherbeam.receiver.packet.CipherBeamPacket
import com.cipherbeam.receiver.packet.CipherBeamPacketParser
import com.cipherbeam.receiver.packet.CipherBeamPacketSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalPacketEndToEndTest {

    companion object {

        private const val BIT_DURATION_NS = 150_000_000L
        private const val GUARD_DURATION_NS = 200_000_000L
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
     * Sends the GREEN START pulse.
     */
    private fun feedGreenStart(
        decoder: OpticalDecoder,
        startNs: Long
    ): Long {

        feed(
            decoder,
            startNs,
            redOn = false,
            greenOn = true
        )

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
     * Sends GREEN OFF and the 200 ms start guard.
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

        return greenOffNs + GUARD_DURATION_NS
    }

    /**
     * Sends one complete 150 ms RED bit window.
     *
     * Samples are deliberately placed inside the window.
     *
     * The next bit begins at the exact 150 ms boundary.
     * We do NOT add an artificial sample at that boundary
     * using the previous bit value because the decoder's
     * RED stability filter needs time to recognize a state change.
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
            windowStartNs + 75_000_000L,
            redOn = bit,
            greenOn = false
        )

        feed(
            decoder,
            windowStartNs + 120_000_000L,
            redOn = bit,
            greenOn = false
        )
    }

    /**
     * Sends one byte MSB first.
     */
    private fun feedByte(
        decoder: OpticalDecoder,
        windowStartNs: Long,
        value: Int
    ): Long {

        var currentWindow = windowStartNs

        for (shift in 7 downTo 0) {

            val bit =
                ((value shr shift) and 1) != 0

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
     * Sends the complete serialized packet through the optical decoder.
     *
     * GREEN remains ON at the end so MESSAGE_COMPLETE can be observed.
     */
    private fun feedPacketUntilCompletion(
        decoder: OpticalDecoder,
        packetBytes: ByteArray
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
         * GREEN OFF + START GUARD
         */
        timestampNs = beginData(
            decoder = decoder,
            greenStartNs = timestampNs
        )

        /*
         * RED DATA
         */
        for (byteValue in packetBytes) {

            timestampNs = feedByte(
                decoder = decoder,
                windowStartNs = timestampNs,
                value = byteValue.toInt() and 0xFF
            )
        }

        /*
         * RED OFF END GUARD
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
         * GREEN END
         *
         * Keep GREEN ON so MESSAGE_COMPLETE remains observable.
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
    }

    @Test
    fun opticalTransmission_decodesAndParsesCipherBeamPacket() {

        /*
         * Build the same logical packet used by the
         * CipherBeam packet layer.
         *
         * Algorithm ID:
         *   0x01 = ChaCha20-Poly1305
         *
         * No actual encryption is performed.
         */
        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = payload
            )

        /*
         * Serialize the packet.
         *
         * Expected:
         *
         * A5 01 01 05
         * 48 45 4C 4C 4F
         * D5 57
         */
        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val expectedBytes =
            byteArrayOf(
                0xA5.toByte(),
                0x01.toByte(),
                0x01.toByte(),
                0x05.toByte(),
                0x48.toByte(),
                0x45.toByte(),
                0x4C.toByte(),
                0x4C.toByte(),
                0x4F.toByte(),
                0xD5.toByte(),
                0x57.toByte()
            )

        /*
         * First verify the packet that will enter
         * the optical layer is exactly correct.
         */
        assertArrayEquals(
            expectedBytes,
            serialized
        )

        /*
         * Send those packet bytes through the
         * optical decoder.
         */
        val decoder =
            OpticalDecoder()

        feedPacketUntilCompletion(
            decoder = decoder,
            packetBytes = serialized
        )


        /*
         * Optical layer must complete successfully.
         */
        val opticalSnapshot =
            decoder.snapshot()

        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            opticalSnapshot.state
        )

        /*
         * The optical layer must return the exact
         * binary packet bytes.
         */
        assertArrayEquals(
            serialized,
            opticalSnapshot.completedBytes
        )

        /*
         * Parse the bytes produced by the optical layer.
         */
        val parseResult =
            CipherBeamPacketParser.parse(
                opticalSnapshot.completedBytes!!
            )

        /*
         * Packet parser must accept the packet.
         */
        assertTrue(
            parseResult is CipherBeamPacketParser.Result.Success
        )

        parseResult as CipherBeamPacketParser.Result.Success

        /*
         * Verify protocol metadata.
         */
        assertEquals(
            CipherBeamPacket.PROTOCOL_VERSION,
            parseResult.packet.version
        )

        assertEquals(
            CipherBeamPacket.SECURITY_PROFILE_DEMO,
            parseResult.packet.securityProfile
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
            parseResult.packet.algorithmId
        )

        /*
         * Verify payload.
         */
        assertArrayEquals(
            payload,
            parseResult.packet.payload
        )

        /*
         * Verify CRC.
         */
        assertEquals(
            0xD557,
            parseResult.packet.crc
        )

        /*
         * Verify packet boundaries.
         */
        assertEquals(
            0,
            parseResult.startIndex
        )

        assertEquals(
            serialized.size,
            parseResult.bytesConsumed
        )
    }
}