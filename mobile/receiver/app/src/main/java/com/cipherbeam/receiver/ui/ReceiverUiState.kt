package com.cipherbeam.receiver.ui

import com.cipherbeam.receiver.camera.RedLedAnalyzer
import com.cipherbeam.receiver.optical.OpticalDecoder
import com.cipherbeam.receiver.packet.CipherBeamPacket

enum class ReceiverMode {
    STANDARD,
    AI_PREVIEW
}

data class ReceiverUiState(
    val waveformSamples: List<OpticalWaveformSample> = emptyList(),
    val mode: ReceiverMode = ReceiverMode.STANDARD,

    val decoderState: OpticalDecoder.State =
        OpticalDecoder.State.WAITING_FOR_START,
    val receivedByteCount: Int = 0,

    val debugSample: RedLedAnalyzer.DebugSample? = null,

    val fps: Double = 0.0,

    val lastReceivedMessage: String? = null,

    val receivedPacket: CipherBeamPacket? = null,

    val packetStatus: String? = null,

    val packetFailureReason: String? = null
)

data class OpticalWaveformSample(
    val redOn: Boolean,
    val greenOn: Boolean,
    val timestampNs: Long
)