package com.cipherbeam.receiver.optical

import com.cipherbeam.receiver.packet.CipherBeamPacket
import com.cipherbeam.receiver.packet.CipherBeamPacketParser
import com.cipherbeam.receiver.packet.CipherBeamPacketSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalDecoderBinaryTest {

    @Test
    fun serializedPacketSurvivesOpticalTransportAndParsesBack() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val originalPacket =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = 0x00,
                payload = payload
            )

        /*
         * Packet layer:
         *
         * CipherBeamPacket
         *      ↓
         * Serializer
         *      ↓
         * raw packet bytes
         */
        val serializedPacket =
            CipherBeamPacketSerializer.serialize(
                originalPacket
            )

        /*
         * Convert every packet byte into
         * MSB-first optical RED bits.
         */
        val bits =
            serializedPacket.flatMap { byte ->
                (7 downTo 0).map { bitIndex ->
                    ((byte.toInt() shr bitIndex) and 1) == 1
                }
            }

        val decoder =
            OpticalDecoder()

        var timestampNs = 0L

        /*
         * GREEN START.
         */
        repeat(3) {

            decoder.feedOpticalSample(
                timestampNs = timestampNs,
                redOn = false,
                greenOn = true
            )

            timestampNs += 50_000_000L
        }

        /*
         * GREEN OFF.
         *
         * This establishes the 200 ms start guard.
         */
        decoder.feedOpticalSample(
            timestampNs = timestampNs,
            redOn = false,
            greenOn = false
        )

        /*
         * Move to the beginning of the RED data window.
         */
        timestampNs += 200_000_000L

        /*
         * Simulate camera sampling at 20 ms intervals.
         *
         * 10 samples per 200 ms optical bit.
         */
        for (bit in bits) {

            repeat(10) {

                timestampNs += 20_000_000L

                decoder.feedOpticalSample(
                    timestampNs = timestampNs,
                    redOn = bit,
                    greenOn = false
                )
            }
        }

        /*
         * RED end guard.
         */
        timestampNs += 200_000_000L

        /*
         * GREEN END.
         */
        repeat(3) {

            decoder.feedOpticalSample(
                timestampNs = timestampNs,
                redOn = false,
                greenOn = true
            )

            timestampNs += 50_000_000L
        }

        val opticalSnapshot =
            decoder.snapshot()

        /*
         * Optical layer must successfully recover
         * the exact serialized packet bytes.
         */
        assertEquals(
            OpticalDecoder.State.MESSAGE_COMPLETE,
            opticalSnapshot.state
        )

        assertTrue(
            opticalSnapshot.completedBytes != null
        )

        assertArrayEquals(
            serializedPacket,
            opticalSnapshot.completedBytes
        )

        /*
         * Packet layer:
         *
         * recovered optical bytes
         *          ↓
         * packet parser
         */
        val parseResult =
            CipherBeamPacketParser.parse(
                opticalSnapshot.completedBytes!!
            )

        assertTrue(
            parseResult is CipherBeamPacketParser.Result.Success
        )

        parseResult as CipherBeamPacketParser.Result.Success

        val parsedPacket =
            parseResult.packet

        /*
         * Verify that the packet recovered after
         * optical transport is identical to the
         * original logical packet.
         */
        assertEquals(
            originalPacket.version,
            parsedPacket.version
        )

        assertEquals(
            originalPacket.flags,
            parsedPacket.flags
        )

        assertEquals(
            originalPacket.length,
            parsedPacket.length
        )

        assertArrayEquals(
            originalPacket.payload,
            parsedPacket.payload
        )

        /*
         * Phase 13 CRC is not implemented yet,
         * so the current placeholder is 0x0000.
         */
        assertEquals(
            0x0000,
            parsedPacket.crc
        )
    }
}