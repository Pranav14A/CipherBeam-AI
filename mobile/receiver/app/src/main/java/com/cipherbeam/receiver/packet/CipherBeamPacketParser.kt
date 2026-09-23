package com.cipherbeam.receiver.packet

/**
 * Parses a logical CipherBeam-AI packet from a byte stream.
 *
 * Phase 10 responsibilities:
 * - find SYNC
 * - validate VERSION
 * - read FLAGS
 * - read LENGTH
 * - validate payload length
 * - ensure the complete packet is present
 * - extract PAYLOAD
 * - extract the two CRC bytes
 *
 * CRC verification is intentionally deferred until Phase 13,
 * because the CRC algorithm and byte order are not frozen yet.
 */
object CipherBeamPacketParser {

    sealed class Result {

        data class Success(
            val packet: CipherBeamPacket,
            val startIndex: Int,
            val bytesConsumed: Int
        ) : Result()

        data class Failure(
            val reason: String
        ) : Result()
    }

    fun parse(data: ByteArray): Result {

        /*
         * A packet must contain at least:
         *
         * SYNC + VERSION + FLAGS + LENGTH + CRC
         *
         * = 6 bytes
         */
        if (data.size < CipherBeamPacket.MIN_PACKET_SIZE) {
            return Result.Failure(
                "Packet is too short"
            )
        }

        /*
         * Find the logical SYNC byte.
         */
        val syncIndex =
            data.indexOf(
                CipherBeamPacket.SYNC.toByte()
            )

        if (syncIndex < 0) {
            return Result.Failure(
                "SYNC byte not found"
            )
        }

        /*
         * We need at least VERSION, FLAGS, LENGTH,
         * and two CRC bytes after SYNC.
         */
        if (
            data.size - syncIndex <
            CipherBeamPacket.MIN_PACKET_SIZE
        ) {
            return Result.Failure(
                "Incomplete packet header"
            )
        }

        var index = syncIndex

        /*
         * SYNC
         */
        val sync =
            data[index].toInt() and 0xFF

        if (sync != CipherBeamPacket.SYNC) {
            return Result.Failure(
                "Invalid SYNC byte"
            )
        }

        index++

        /*
         * VERSION
         */
        val version =
            data[index].toInt() and 0xFF

        index++

        if (
            version !=
            CipherBeamPacket.PROTOCOL_VERSION
        ) {
            return Result.Failure(
                "Unsupported protocol version: $version"
            )
        }

        /*
         * FLAGS
         */
        val flags =
            data[index].toInt() and 0xFF

        index++

        /*
         * LENGTH
         */
        val payloadLength =
            data[index].toInt() and 0xFF

        index++

        if (
            payloadLength >
            CipherBeamPacket.MAX_PAYLOAD_LENGTH
        ) {
            return Result.Failure(
                "Payload length exceeds maximum: $payloadLength"
            )
        }

        /*
         * Complete packet size:
         *
         * HEADER + PAYLOAD + CRC
         */
        val packetSize =
            CipherBeamPacket.HEADER_SIZE +
                    payloadLength +
                    CipherBeamPacket.CRC_SIZE

        if (
            data.size - syncIndex <
            packetSize
        ) {
            return Result.Failure(
                "Incomplete packet: expected $packetSize bytes"
            )
        }

        /*
         * PAYLOAD
         */
        val payload =
            data.copyOfRange(
                index,
                index + payloadLength
            )

        index += payloadLength

        /*
         * CRC is currently extracted but not verified.
         *
         * Phase 13 will define the actual CRC algorithm
         * and byte-order validation.
         */
        val crcHigh =
            data[index].toInt() and 0xFF

        index++

        val crcLow =
            data[index].toInt() and 0xFF

        index++

        val crc =
            (crcHigh shl 8) or crcLow

        val packet =
            CipherBeamPacket(
                version = version,
                flags = flags,
                payload = payload,
                crc = crc
            )

        return Result.Success(
            packet = packet,
            startIndex = syncIndex,
            bytesConsumed = index - syncIndex
        )
    }

    private fun ByteArray.indexOf(
        value: Byte
    ): Int {

        for (index in indices) {
            if (this[index] == value) {
                return index
            }
        }

        return -1
    }
}