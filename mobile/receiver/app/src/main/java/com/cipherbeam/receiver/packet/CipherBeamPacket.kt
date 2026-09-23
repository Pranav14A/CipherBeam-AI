package com.cipherbeam.receiver.packet

/**
 * Logical CipherBeam-AI packet.
 *
 * Protocol Version: 1
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
 * Phase 9 defines the packet structure.
 */
data class CipherBeamPacket(
    val version: Int,
    val flags: Int,
    val payload: ByteArray,
    val crc: Int? = null
) {

    companion object {
        const val PROTOCOL_VERSION = 0x01
        const val SYNC = 0xA5
        const val MAX_PAYLOAD_LENGTH = 100

        const val HEADER_SIZE = 4
        const val CRC_SIZE = 2

        const val MIN_PACKET_SIZE =
            HEADER_SIZE + CRC_SIZE

        const val MAX_PACKET_SIZE =
            HEADER_SIZE +
                    MAX_PAYLOAD_LENGTH +
                    CRC_SIZE
    }

    init {
        require(version in 0..0xFF) {
            "Version must be an unsigned byte"
        }

        require(flags in 0..0xFF) {
            "Flags must be an unsigned byte"
        }

        require(payload.size <= MAX_PAYLOAD_LENGTH) {
            "Payload exceeds maximum length of $MAX_PAYLOAD_LENGTH bytes"
        }

        crc?.let {
            require(it in 0..0xFFFF) {
                "CRC must be a 16-bit unsigned value"
            }
        }
    }

    val length: Int
        get() = payload.size

    val securityProfile: Int
        get() = (flags ushr 4) and 0x0F

    val algorithmId: Int
        get() = flags and 0x0F

    fun payloadCopy(): ByteArray {
        return payload.copyOf()
    }
}