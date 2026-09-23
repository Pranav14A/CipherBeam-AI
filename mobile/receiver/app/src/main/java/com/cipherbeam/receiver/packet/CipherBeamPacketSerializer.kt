package com.cipherbeam.receiver.packet

/**
 * Serializes a CipherBeamPacket into its logical wire format.
 *
 * Packet layout:
 *
 *   SYNC    1 byte
 *   VERSION 1 byte
 *   FLAGS   1 byte
 *   LENGTH  1 byte
 *   PAYLOAD 0–100 bytes
 *   CRC     2 bytes
 *
 * Phase 10 currently defines the packet structure.
 *
 * The CRC algorithm is intentionally not implemented yet because
 * it is frozen in Phase 13. Until then, a packet without a CRC
 * uses 0x0000 as the two-byte CRC placeholder.
 */
object CipherBeamPacketSerializer {

    fun serialize(packet: CipherBeamPacket): ByteArray {
        val result =
            ByteArray(
                CipherBeamPacket.HEADER_SIZE +
                        packet.payload.size +
                        CipherBeamPacket.CRC_SIZE
            )

        var index = 0

        /*
         * SYNC
         */
        result[index++] =
            CipherBeamPacket.SYNC.toByte()

        /*
         * VERSION
         */
        result[index++] =
            packet.version.toByte()

        /*
         * FLAGS
         */
        result[index++] =
            packet.flags.toByte()

        /*
         * LENGTH
         */
        result[index++] =
            packet.length.toByte()

        /*
         * PAYLOAD
         */
        packet.payload.copyInto(
            destination = result,
            destinationOffset = index
        )

        index += packet.payload.size

        /*
         * CRC
         *
         * Phase 13 will define the actual CRC algorithm.
         *
         * Until then, an absent CRC is represented by
         * the placeholder value 0x0000.
         */
        val crc =
            packet.crc ?: 0x0000

        result[index++] =
            ((crc ushr 8) and 0xFF).toByte()

        result[index] =
            (crc and 0xFF).toByte()

        return result
    }
}