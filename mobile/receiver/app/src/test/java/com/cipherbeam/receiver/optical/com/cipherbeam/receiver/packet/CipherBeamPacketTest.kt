package com.cipherbeam.receiver.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CipherBeamPacketTest {

    @Test
    fun helloPacket_serializesAndParsesSuccessfully() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = 0x00,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val expected =
            byteArrayOf(
                0xA5.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x05.toByte(),
                0x48.toByte(),
                0x45.toByte(),
                0x4C.toByte(),
                0x4C.toByte(),
                0x4F.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )

        assertArrayEquals(
            expected,
            serialized
        )

        val result =
            CipherBeamPacketParser.parse(
                serialized
            )

        assertTrue(
            result is CipherBeamPacketParser.Result.Success
        )

        result as CipherBeamPacketParser.Result.Success

        assertEquals(
            CipherBeamPacket.PROTOCOL_VERSION,
            result.packet.version
        )

        assertEquals(
            0x00,
            result.packet.flags
        )

        assertEquals(
            5,
            result.packet.length
        )

        assertArrayEquals(
            payload,
            result.packet.payload
        )

        assertEquals(
            0x0000,
            result.packet.crc
        )

        assertEquals(
            0,
            result.startIndex
        )

        assertEquals(
            expected.size,
            result.bytesConsumed
        )
    }

    @Test
    fun parserRejectsMissingSync() {

        val data =
            byteArrayOf(
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00
            )

        val result =
            CipherBeamPacketParser.parse(data)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "SYNC byte not found",
            result.reason
        )
    }

    @Test
    fun parserRejectsUnsupportedVersion() {

        val data =
            byteArrayOf(
                0xA5.toByte(),
                0x02.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )

        val result =
            CipherBeamPacketParser.parse(data)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "Unsupported protocol version: 2",
            result.reason
        )
    }

    @Test
    fun parserRejectsPayloadLargerThanMaximum() {

        val data =
            byteArrayOf(
                0xA5.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                101.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )

        val result =
            CipherBeamPacketParser.parse(data)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "Payload length exceeds maximum: 101",
            result.reason
        )
    }

    @Test
    fun parserRejectsIncompletePacket() {

        /*
         * LENGTH says 5 bytes of payload.
         *
         * A complete packet would therefore require:
         *
         * 4 header bytes
         * + 5 payload bytes
         * + 2 CRC bytes
         * = 11 bytes
         *
         * Only 9 bytes are supplied.
         */
        val data =
            byteArrayOf(
                0xA5.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x05.toByte(),
                0x48.toByte(),
                0x45.toByte(),
                0x4C.toByte(),
                0x4C.toByte(),
                0x4F.toByte()
            )

        val result =
            CipherBeamPacketParser.parse(data)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "Incomplete packet: expected 11 bytes",
            result.reason
        )
    }

    @Test
    fun parserCanFindPacketAfterLeadingNoise() {

        val data =
            byteArrayOf(
                0x11.toByte(),
                0x22.toByte(),
                0x33.toByte(),

                0xA5.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x41.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )

        val result =
            CipherBeamPacketParser.parse(data)

        assertTrue(
            result is CipherBeamPacketParser.Result.Success
        )

        result as CipherBeamPacketParser.Result.Success

        assertEquals(
            3,
            result.startIndex
        )

        assertEquals(
            7,
            result.bytesConsumed
        )

        assertArrayEquals(
            byteArrayOf(0x41.toByte()),
            result.packet.payload
        )
    }
}