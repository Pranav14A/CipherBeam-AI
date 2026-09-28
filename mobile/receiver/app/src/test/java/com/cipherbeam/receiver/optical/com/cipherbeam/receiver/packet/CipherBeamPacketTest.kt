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
                flags = CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val expected =
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
            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
            result.packet.flags
        )

        assertEquals(
            CipherBeamPacket.SECURITY_PROFILE_DEMO,
            result.packet.securityProfile
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
            result.packet.algorithmId
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
            0xD557,
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
    fun crc16CcittFalse_helloPayload_returnsExpectedValue() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val crc =
            CipherBeamPacket.calculateCrc(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = payload
            )

        assertEquals(
            0xD557,
            crc
        )

    }
    @Test
    fun algorithmIds_areCorrect() {
        assertEquals(
            0x01,
            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305
        )

        assertEquals(
            0x02,
            CipherBeamPacket.ALGORITHM_AES_256_GCM
        )

        assertEquals(
            0x00,
            CipherBeamPacket.SECURITY_PROFILE_DEMO
        )
    }

    @Test
    fun parserRejectsCorruptedCrc() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        /*
         * Corrupt the low CRC byte.
         *
         *Correct CRC:
            D5 57

          Corrupted CRC:
           D5 56
         */
        serialized[serialized.lastIndex] =
            0x56.toByte()

        val result =
            CipherBeamPacketParser.parse(serialized)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "CRC mismatch: expected D557, received D556",
            result.reason
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
                0x01.toByte(),
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
                0x01.toByte(),
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
                0x01.toByte(),
                0x01.toByte(),
                0x41.toByte(),
                0xAE.toByte(),
                0x90.toByte()
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
    @Test
    fun parserAcceptsAes256GcmAlgorithm() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = CipherBeamPacket.ALGORITHM_AES_256_GCM,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val result =
            CipherBeamPacketParser.parse(serialized)

        assertTrue(
            result is CipherBeamPacketParser.Result.Success
        )

        result as CipherBeamPacketParser.Result.Success

        assertEquals(
            CipherBeamPacket.SECURITY_PROFILE_DEMO,
            result.packet.securityProfile
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_AES_256_GCM,
            result.packet.algorithmId
        )
    }

    @Test
    fun parserRejectsUnsupportedAlgorithmId() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = 0x03,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val result =
            CipherBeamPacketParser.parse(serialized)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "Unsupported algorithm ID: 3",
            result.reason
        )
    }

    @Test
    fun parserRejectsUnsupportedSecurityProfile() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags = 0x11,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val result =
            CipherBeamPacketParser.parse(serialized)

        assertTrue(
            result is CipherBeamPacketParser.Result.Failure
        )

        result as CipherBeamPacketParser.Result.Failure

        assertEquals(
            "Unsupported security profile: 1",
            result.reason
        )
    }
    @Test
    fun parserAcceptsChaCha20WithDemoProfile() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags =
                    (CipherBeamPacket.SECURITY_PROFILE_DEMO shl 4) or
                            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val result =
            CipherBeamPacketParser.parse(serialized)

        assertTrue(
            result is CipherBeamPacketParser.Result.Success
        )

        result as CipherBeamPacketParser.Result.Success

        assertEquals(
            CipherBeamPacket.SECURITY_PROFILE_DEMO,
            result.packet.securityProfile
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
            result.packet.algorithmId
        )
    }

    @Test
    fun parserAcceptsAes256GcmWithDemoProfile() {

        val payload =
            "HELLO".toByteArray(Charsets.US_ASCII)

        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags =
                    (CipherBeamPacket.SECURITY_PROFILE_DEMO shl 4) or
                            CipherBeamPacket.ALGORITHM_AES_256_GCM,
                payload = payload
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val result =
            CipherBeamPacketParser.parse(serialized)

        assertTrue(
            result is CipherBeamPacketParser.Result.Success
        )

        result as CipherBeamPacketParser.Result.Success

        assertEquals(
            CipherBeamPacket.SECURITY_PROFILE_DEMO,
            result.packet.securityProfile
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_AES_256_GCM,
            result.packet.algorithmId
        )
    }

    @Test
    fun parserAcceptsValidPacketWithLeadingAndTrailingNoise() {
        val packet =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags =
                    (CipherBeamPacket.SECURITY_PROFILE_DEMO shl 4) or
                            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = "HELLO".toByteArray()
            )

        val serialized =
            CipherBeamPacketSerializer.serialize(packet)

        val leadingNoise =
            byteArrayOf(
                0x7E.toByte(),
                0x12.toByte(),
                0x00.toByte()
            )

        val trailingNoise =
            byteArrayOf(
                0x55.toByte(),
                0x33.toByte(),
                0x99.toByte()
            )

        val data =
            leadingNoise +
                    serialized +
                    trailingNoise

        val result =
            CipherBeamPacketParser.parse(data)

        assertTrue(result is CipherBeamPacketParser.Result.Success)

        result as CipherBeamPacketParser.Result.Success

        assertEquals(
            leadingNoise.size,
            result.startIndex
        )

        assertEquals(
            serialized.size,
            result.bytesConsumed
        )

        assertEquals(
            "HELLO",
            result.packet.payload.toString(Charsets.US_ASCII)
        )
    }

    @Test
    fun parserCanParseTwoConsecutivePackets() {
        val firstPacket =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags =
                    (CipherBeamPacket.SECURITY_PROFILE_DEMO shl 4) or
                            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
                payload = "HELLO".toByteArray()
            )

        val secondPacket =
            CipherBeamPacket(
                version = CipherBeamPacket.PROTOCOL_VERSION,
                flags =
                    (CipherBeamPacket.SECURITY_PROFILE_DEMO shl 4) or
                            CipherBeamPacket.ALGORITHM_AES_256_GCM,
                payload = "WORLD".toByteArray()
            )

        val firstSerialized =
            CipherBeamPacketSerializer.serialize(firstPacket)

        val secondSerialized =
            CipherBeamPacketSerializer.serialize(secondPacket)

        val combinedData =
            firstSerialized + secondSerialized

        /*
         * Parse the first packet.
         */
        val firstResult =
            CipherBeamPacketParser.parse(combinedData)

        assertTrue(
            firstResult is CipherBeamPacketParser.Result.Success
        )

        firstResult as CipherBeamPacketParser.Result.Success

        assertEquals(
            0,
            firstResult.startIndex
        )

        assertEquals(
            firstSerialized.size,
            firstResult.bytesConsumed
        )

        assertEquals(
            "HELLO",
            firstResult.packet.payload.toString(Charsets.US_ASCII)
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_CHACHA20_POLY1305,
            firstResult.packet.algorithmId
        )

        /*
         * Start parsing immediately after packet 1.
         */
        val secondStart =
            firstResult.startIndex +
                    firstResult.bytesConsumed

        val remainingData =
            combinedData.copyOfRange(
                secondStart,
                combinedData.size
            )

        /*
         * Parse the second packet.
         */
        val secondResult =
            CipherBeamPacketParser.parse(remainingData)

        assertTrue(
            secondResult is CipherBeamPacketParser.Result.Success
        )

        secondResult as CipherBeamPacketParser.Result.Success

        assertEquals(
            0,
            secondResult.startIndex
        )

        assertEquals(
            secondSerialized.size,
            secondResult.bytesConsumed
        )

        assertEquals(
            "WORLD",
            secondResult.packet.payload.toString(Charsets.US_ASCII)
        )

        assertEquals(
            CipherBeamPacket.ALGORITHM_AES_256_GCM,
            secondResult.packet.algorithmId
        )
    }
}