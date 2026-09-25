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
 * CRC:
 *   CRC-16/CCITT-FALSE
 *   Polynomial: 0x1021
 *   Initial value: 0xFFFF
 *   Input/output reflection: disabled
 *   XOR output: 0x0000
 *
 * CRC is calculated over:
 *
 *   VERSION + FLAGS + LENGTH + PAYLOAD
 *
 * SYNC is excluded from CRC.
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
         * If a CRC was explicitly supplied in the packet,
         * preserve it.
         *
         * Otherwise calculate CRC-16/CCITT-FALSE automatically.
         */
        val crc =
            packet.crc
                ?: CipherBeamPacket.calculateCrc(
                    version = packet.version,
                    flags = packet.flags,
                    payload = packet.payload
                )

        /*
         * CRC is transmitted big-endian:
         *
         * CRC high byte
         * CRC low byte
         */
        result[index++] =
            ((crc ushr 8) and 0xFF).toByte()

        result[index] =
            (crc and 0xFF).toByte()

        return result
    }
}