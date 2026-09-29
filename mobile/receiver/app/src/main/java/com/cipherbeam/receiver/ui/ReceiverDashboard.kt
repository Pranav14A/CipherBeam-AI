package com.cipherbeam.receiver.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cipherbeam.receiver.camera.RedLedAnalyzer
import com.cipherbeam.receiver.optical.OpticalDecoder
import com.cipherbeam.receiver.packet.CipherBeamPacket
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max


@Composable
fun ReceiverDashboard(
    uiState: ReceiverUiState,
    cameraContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val packetVerified =
        uiState.receivedPacket != null

    val packetRejected =
        uiState.packetFailureReason != null

    val isReceiving =
        uiState.decoderState ==
                OpticalDecoder.State.RECEIVING_PAYLOAD

    /*
     * ----------------------------------------------------------------------
     * LOCAL DISPLAY MODE
     *
     * UI ONLY.
     *
     * AI PREVIEW does not alter the optical decoder or packet pipeline.
     * ----------------------------------------------------------------------
     */

    var displayMode by remember {
        mutableStateOf(uiState.mode)
    }

    /*
     * ----------------------------------------------------------------------
     * SESSION TELEMETRY
     * ----------------------------------------------------------------------
     */

    var sessionTransmissions by remember {
        mutableIntStateOf(0)
    }

    var sessionVerified by remember {
        mutableIntStateOf(0)
    }

    var sessionRejected by remember {
        mutableIntStateOf(0)
    }

    var lastFrameBytes by remember {
        mutableIntStateOf(0)
    }

    var countedPacket by remember {
        mutableStateOf<CipherBeamPacket?>(null)
    }

    var countedFailure by remember {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(
        uiState.receivedPacket,
        uiState.packetFailureReason
    ) {
        val packet =
            uiState.receivedPacket

        val failure =
            uiState.packetFailureReason

        if (
            packet != null &&
            packet !== countedPacket
        ) {
            sessionTransmissions++
            sessionVerified++

            lastFrameBytes =
                packet.length + 6

            countedPacket =
                packet
        }

        if (
            failure != null &&
            failure != countedFailure
        ) {
            sessionTransmissions++
            sessionRejected++

            countedFailure =
                failure
        }

        if (
            packet == null &&
            failure == null
        ) {
            countedPacket = null
            countedFailure = null
        }
    }

    /*
     * ----------------------------------------------------------------------
     * SUCCESS GLOW
     * ----------------------------------------------------------------------
     */

    var successGlowActive by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(
        uiState.receivedPacket,
        uiState.lastReceivedMessage
    ) {
        if (packetVerified) {

            successGlowActive = true

            delay(3_000L)

            successGlowActive = false
        }
    }

    val edgeGlow =
        when {
            packetRejected ->
                EdgeGlowState.FAILURE

            successGlowActive ->
                EdgeGlowState.SUCCESS

            isReceiving ->
                EdgeGlowState.RECEIVING

            else ->
                EdgeGlowState.NONE
        }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CipherBlack)
    ) {

        /*
         * CAMERA
         */
        cameraContent()

        /*
         * TOP READABILITY VEIL
         */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(
                    CipherBlack.copy(
                        alpha = 0.34f
                    )
                )
                .align(Alignment.TopCenter)
        )

        /*
         * BOTTOM READABILITY VEIL
         */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .background(
                    CipherBlack.copy(
                        alpha = 0.46f
                    )
                )
                .align(Alignment.BottomCenter)
        )

        /*
         * OPTICAL ALIGNMENT CROSSHAIR
         */
        OpticalCrosshair(
            modifier = Modifier.align(
                Alignment.Center
            )
        )

        /*
         * EVENT EDGE ILLUMINATION
         */
        EdgeStatusGlow(
            state = edgeGlow,
            modifier = Modifier.fillMaxSize()
        )

        /*
         * TOP HUD
         */
        ReceiverTopHud(
            mode = displayMode,
            fps = uiState.fps,
            decoderState = uiState.decoderState,
            onToggleMode = {
                displayMode =
                    if (
                        displayMode ==
                        ReceiverMode.STANDARD
                    ) {
                        ReceiverMode.AI_PREVIEW
                    } else {
                        ReceiverMode.STANDARD
                    }
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(
                    horizontal = 18.dp,
                    vertical = 14.dp
                )
        )

        /*
         * STANDARD / AI LIVE HUD
         */
        if (!packetVerified && !packetRejected) {

            ReceiverLiveHud(
                debug = uiState.debugSample,
                decoderState = uiState.decoderState,
                samples = uiState.waveformSamples,
                receivedByteCount =
                    uiState.receivedByteCount,
                sessionTransmissions =
                    sessionTransmissions,
                sessionVerified =
                    sessionVerified,
                sessionRejected =
                    sessionRejected,
                lastFrameBytes =
                    lastFrameBytes,
                fps = uiState.fps,
                mode = displayMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(
                        horizontal = 14.dp,
                        vertical = 14.dp
                    )
            )
        }

        /*
         * VERIFIED PACKET
         */
        if (packetVerified) {

            PacketVerifiedHud(
                packet = uiState.receivedPacket,
                message = uiState.lastReceivedMessage,
                sessionTransmissions =
                    sessionTransmissions,
                sessionVerified =
                    sessionVerified,
                sessionRejected =
                    sessionRejected,
                lastFrameBytes =
                    lastFrameBytes,
                debug = uiState.debugSample,
                fps = uiState.fps,
                mode = displayMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(
                        horizontal = 14.dp,
                        vertical = 14.dp
                    )
            )
        }

        /*
         * REJECTED PACKET
         */
        if (packetRejected) {

            PacketFailureHud(
                reason =
                    uiState.packetFailureReason
                        ?: "UNKNOWN ERROR",
                sessionTransmissions =
                    sessionTransmissions,
                sessionVerified =
                    sessionVerified,
                sessionRejected =
                    sessionRejected,
                lastFrameBytes =
                    lastFrameBytes,
                debug = uiState.debugSample,
                fps = uiState.fps,
                mode = displayMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(
                        horizontal = 14.dp,
                        vertical = 14.dp
                    )
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* EDGE STATUS GLOW                                                           */
/* -------------------------------------------------------------------------- */

private enum class EdgeGlowState {
    NONE,
    RECEIVING,
    FAILURE,
    SUCCESS
}


@Composable
private fun EdgeStatusGlow(
    state: EdgeGlowState,
    modifier: Modifier = Modifier
) {
    if (state == EdgeGlowState.NONE) {
        return
    }

    val color =
        when (state) {
            EdgeGlowState.RECEIVING ->
                DecoderReceivingYellow

            EdgeGlowState.FAILURE ->
                OpticalRed

            EdgeGlowState.SUCCESS ->
                OpticalGreen

            EdgeGlowState.NONE ->
                Color.Transparent
        }

    val infiniteTransition =
        rememberInfiniteTransition(
            label = "edge_glow"
        )

    val pulseAlpha by
    infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = 1200,
                        easing =
                            FastOutSlowInEasing
                    ),
                repeatMode =
                    RepeatMode.Reverse
            ),
        label = "edge_glow_alpha"
    )

    val baseAlpha =
        when (state) {
            EdgeGlowState.RECEIVING ->
                0.58f

            EdgeGlowState.FAILURE ->
                0.62f

            EdgeGlowState.SUCCESS ->
                0.68f

            EdgeGlowState.NONE ->
                0f
        }

    val animatedAlpha =
        if (
            state == EdgeGlowState.FAILURE ||
            state == EdgeGlowState.RECEIVING
        ) {
            pulseAlpha
        } else {
            1f
        }

    Canvas(
        modifier = modifier
    ) {

        val outerSpread =
            58.dp.toPx()

        drawRect(
            brush =
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0.00f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.42f
                                    ),
                            0.18f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.30f
                                    ),
                            0.42f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.16f
                                    ),
                            1.00f to
                                    Color.Transparent
                        ),
                    startY = 0f,
                    endY = outerSpread
                )
        )

        drawRect(
            brush =
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0.00f to
                                    Color.Transparent,
                            0.45f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.035f
                                    ),
                            0.78f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.07f
                                    ),
                            1.00f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.10f
                                    )
                        ),
                    startY =
                        size.height -
                                outerSpread,
                    endY =
                        size.height
                )
        )

        drawRect(
            brush =
                Brush.horizontalGradient(
                    colorStops =
                        arrayOf(
                            0.00f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.42f
                                    ),
                            0.18f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.30f
                                    ),
                            0.42f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.16f
                                    ),
                            1.00f to
                                    Color.Transparent
                        ),
                    startX = 0f,
                    endX = outerSpread
                )
        )

        drawRect(
            brush =
                Brush.horizontalGradient(
                    colorStops =
                        arrayOf(
                            0.00f to
                                    Color.Transparent,
                            0.45f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.035f
                                    ),
                            0.78f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.07f
                                    ),
                            1.00f to
                                    color.copy(
                                        alpha =
                                            baseAlpha *
                                                    animatedAlpha *
                                                    0.10f
                                    )
                        ),
                    startX =
                        size.width -
                                outerSpread,
                    endX =
                        size.width
                )
        )
    }
}


/* -------------------------------------------------------------------------- */
/* TOP HUD                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun ReceiverTopHud(
    mode: ReceiverMode,
    fps: Double,
    decoderState: OpticalDecoder.State,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.Top
    ) {

        Column {

            Text(
                text = "CIPHERBEAM",
                color = CipherTextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.8.sp
            )

            Text(
                text = "OPTICAL RECEIVER",
                color = CipherTextMuted,
                fontSize = 8.sp,
                letterSpacing = 1.7.sp
            )
        }

        Column(
            horizontalAlignment =
                Alignment.End
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                DecoderStateIndicator(
                    state = decoderState
                )

                Spacer(
                    modifier = Modifier.width(9.dp)
                )

                FpsIndicator(
                    fps = fps
                )
            }

            Spacer(
                modifier = Modifier.height(7.dp)
            )

            ModeIndicator(
                mode = mode,
                onToggleMode = onToggleMode
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* DECODER STATE                                                              */
/* -------------------------------------------------------------------------- */

@Composable
private fun DecoderStateIndicator(
    state: OpticalDecoder.State
) {
    val active =
        when (state) {
            OpticalDecoder.State.START_DETECTED,
            OpticalDecoder.State.WAITING_FOR_DATA,
            OpticalDecoder.State.RECEIVING_PAYLOAD ->
                true

            else ->
                false
        }

    val stateColor =
        when (state) {
            OpticalDecoder.State.MESSAGE_COMPLETE ->
                CipherCyan

            OpticalDecoder.State.START_DETECTED,
            OpticalDecoder.State.WAITING_FOR_DATA,
            OpticalDecoder.State.RECEIVING_PAYLOAD ->
                OpticalGreen

            else ->
                CipherTextMuted
        }

    val label =
        when (state) {
            OpticalDecoder.State.WAITING_FOR_START ->
                "SEARCHING"

            OpticalDecoder.State.START_DETECTED ->
                "ACQUIRED"

            OpticalDecoder.State.WAITING_FOR_DATA ->
                "SYNC"

            OpticalDecoder.State.RECEIVING_PAYLOAD ->
                "RECEIVING"

            OpticalDecoder.State.MESSAGE_COMPLETE ->
                "VERIFIED"
        }

    Row(
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(
                    RoundedCornerShape(50)
                )
                .background(
                    stateColor.copy(
                        alpha =
                            if (active) {
                                1f
                            } else {
                                0.65f
                            }
                    )
                )
        )

        Spacer(
            modifier = Modifier.width(5.dp)
        )

        Text(
            text = label,
            color = stateColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.0.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* FPS                                                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun FpsIndicator(
    fps: Double
) {
    val safeFps =
        if (fps.isFinite()) {
            fps.coerceAtLeast(0.0)
        } else {
            0.0
        }

    val isCritical =
        safeFps < 23.0

    val isWarning =
        safeFps >= 23.0 &&
                safeFps < 27.0

    val indicatorColor =
        when {
            isCritical ->
                OpticalRed

            isWarning ->
                FpsAmber

            else ->
                OpticalGreen
        }

    val infiniteTransition =
        rememberInfiniteTransition(
            label = "fps_warning_transition"
        )

    val pulseAlpha by
    infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 1.0f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = 900,
                        easing =
                            FastOutSlowInEasing
                    ),
                repeatMode =
                    RepeatMode.Reverse
            ),
        label = "fps_warning_alpha"
    )

    Row(
        modifier = Modifier
            .clip(
                RoundedCornerShape(4.dp)
            )
            .background(
                indicatorColor.copy(
                    alpha =
                        if (isCritical) {
                            0.10f +
                                    (pulseAlpha * 0.06f)
                        } else {
                            0.06f
                        }
                )
            )
            .border(
                width = 1.dp,
                color =
                    indicatorColor.copy(
                        alpha =
                            if (isCritical) {
                                pulseAlpha
                            } else {
                                0.45f
                            }
                    ),
                shape =
                    RoundedCornerShape(4.dp)
            )
            .padding(
                horizontal = 8.dp,
                vertical = 5.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(
                    RoundedCornerShape(50)
                )
                .background(
                    indicatorColor.copy(
                        alpha =
                            if (isCritical) {
                                pulseAlpha
                            } else {
                                1f
                            }
                    )
                )
        )

        Spacer(
            modifier = Modifier.width(5.dp)
        )

        Text(
            text = String.format(
                Locale.US,
                "%.1f FPS",
                safeFps
            ),
            color = indicatorColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* MODE                                                                       */
/* -------------------------------------------------------------------------- */

@Composable
private fun ModeIndicator(
    mode: ReceiverMode,
    onToggleMode: () -> Unit
) {
    val isAi =
        mode == ReceiverMode.AI_PREVIEW

    val color =
        if (isAi) {
            AiAccent
        } else {
            CipherCyan
        }

    Row(
        modifier = Modifier
            .clip(
                RoundedCornerShape(4.dp)
            )
            .clickable(
                onClick = onToggleMode
            )
            .background(
                color.copy(
                    alpha = 0.06f
                )
            )
            .border(
                1.dp,
                color.copy(
                    alpha = 0.28f
                ),
                RoundedCornerShape(4.dp)
            )
            .padding(
                horizontal = 7.dp,
                vertical = 5.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(
                    RoundedCornerShape(50)
                )
                .background(color)
        )

        Spacer(
            modifier = Modifier.width(5.dp)
        )

        Text(
            text =
                if (isAi) {
                    "AI PREVIEW"
                } else {
                    "STANDARD"
                },
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp
        )

        Spacer(
            modifier = Modifier.width(6.dp)
        )

        Text(
            text = "↕",
            color = color.copy(
                alpha = 0.55f
            ),
            fontSize = 9.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* LIVE HUD                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun ReceiverLiveHud(
    debug: RedLedAnalyzer.DebugSample?,
    decoderState: OpticalDecoder.State,
    samples: List<OpticalWaveformSample>,
    receivedByteCount: Int,
    sessionTransmissions: Int,
    sessionVerified: Int,
    sessionRejected: Int,
    lastFrameBytes: Int,
    fps: Double,
    mode: ReceiverMode,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
    ) {

        OpticalStatusStrip(
            debug = debug,
            decoderState = decoderState
        )

        Spacer(
            modifier = Modifier.height(7.dp)
        )

        OpticalWaveformPanel(
            samples = samples,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(
            modifier = Modifier.height(7.dp)
        )

        ReceiverStateBar(
            state = decoderState,
            modifier = Modifier.fillMaxWidth()
        )

        if (
            decoderState ==
            OpticalDecoder.State.RECEIVING_PAYLOAD
        ) {
            Spacer(
                modifier = Modifier.height(7.dp)
            )

            FrameAssemblyStrip(
                receivedByteCount = receivedByteCount,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(
            modifier = Modifier.height(7.dp)
        )

        SessionTelemetryStrip(
            transmissions = sessionTransmissions,
            verified = sessionVerified,
            rejected = sessionRejected,
            lastFrameBytes = lastFrameBytes,
            modifier = Modifier.fillMaxWidth()
        )

        if (
            mode == ReceiverMode.AI_PREVIEW
        ) {
            Spacer(
                modifier = Modifier.height(7.dp)
            )

            AiPreviewStrip(
                debug = debug,
                decoderState = decoderState,
                receivedByteCount = receivedByteCount,
                fps = fps,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* OPTICAL STATUS                                                             */
/* -------------------------------------------------------------------------- */

@Composable
private fun OpticalStatusStrip(
    debug: RedLedAnalyzer.DebugSample?,
    decoderState: OpticalDecoder.State
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        OpticalStatusItem(
            name = "RED",
            role = "DATA",
            active = debug?.bitOn == true,
            color = OpticalRed
        )

        OpticalStatusItem(
            name = "GREEN",
            role = "CONTROL",
            active = debug?.greenOn == true,
            color = OpticalGreen
        )

        Text(
            text = when (decoderState) {
                OpticalDecoder.State.WAITING_FOR_START ->
                    "WAITING FOR START"

                OpticalDecoder.State.START_DETECTED ->
                    "SIGNAL ACQUIRED"

                OpticalDecoder.State.WAITING_FOR_DATA ->
                    "WAITING FOR DATA"

                OpticalDecoder.State.RECEIVING_PAYLOAD ->
                    "RECEIVING DATA"

                OpticalDecoder.State.MESSAGE_COMPLETE ->
                    "PACKET VERIFIED"
            },
            color = CipherTextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp
        )
    }
}


@Composable
private fun OpticalStatusItem(
    name: String,
    role: String,
    active: Boolean,
    color: Color
) {
    Row(
        modifier = Modifier.padding(
            top = 3.dp
        ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(
                    RoundedCornerShape(50)
                )
                .background(
                    if (active) {
                        color.copy(alpha = 0.82f)
                    } else {
                        color.copy(alpha = 0.24f)
                    }
                )
        )

        Spacer(
            modifier = Modifier.width(5.dp)
        )

        Column {

            Text(
                text = name,
                color =
                    if (active) {
                        color.copy(alpha = 0.88f)
                    } else {
                        CipherTextSecondary
                    },
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.0.sp
            )

            Text(
                text = role,
                color = CipherTextMuted,
                fontSize = 6.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.7.sp
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* RECEIVER STATE BAR                                                         */
/* -------------------------------------------------------------------------- */

@Composable
private fun ReceiverStateBar(
    state: OpticalDecoder.State,
    modifier: Modifier = Modifier
) {
    val label =
        when (state) {
            OpticalDecoder.State.WAITING_FOR_START ->
                "SEARCHING FOR OPTICAL START"

            OpticalDecoder.State.START_DETECTED ->
                "OPTICAL START DETECTED"

            OpticalDecoder.State.WAITING_FOR_DATA ->
                "SYNCHRONIZING DATA CHANNEL"

            OpticalDecoder.State.RECEIVING_PAYLOAD ->
                "RECEIVING OPTICAL FRAME"

            OpticalDecoder.State.MESSAGE_COMPLETE ->
                "FRAME COMPLETE"
        }

    Row(
        modifier = modifier
            .clip(
                RoundedCornerShape(5.dp)
            )
            .background(
                CipherBlack.copy(
                    alpha = 0.64f
                )
            )
            .border(
                1.dp,
                CipherBorder.copy(
                    alpha = 0.75f
                ),
                RoundedCornerShape(5.dp)
            )
            .padding(
                horizontal = 10.dp,
                vertical = 7.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(
                    RoundedCornerShape(50)
                )
                .background(
                    when (state) {
                        OpticalDecoder.State.RECEIVING_PAYLOAD ->
                            DecoderReceivingYellow

                        OpticalDecoder.State.MESSAGE_COMPLETE ->
                            CipherCyan

                        else ->
                            CipherTextMuted
                    }
                )
        )

        Spacer(
            modifier = Modifier.width(7.dp)
        )

        Text(
            text = label,
            color = CipherTextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.0.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* FRAME ASSEMBLY                                                             */
/* -------------------------------------------------------------------------- */

@Composable
private fun FrameAssemblyStrip(
    receivedByteCount: Int,
    modifier: Modifier = Modifier
) {
    val safeByteCount =
        receivedByteCount.coerceAtLeast(0)

    Row(
        modifier = modifier
            .clip(
                RoundedCornerShape(5.dp)
            )
            .background(
                CipherBlack.copy(
                    alpha = 0.64f
                )
            )
            .border(
                1.dp,
                CipherBorder.copy(
                    alpha = 0.75f
                ),
                RoundedCornerShape(5.dp)
            )
            .padding(
                horizontal = 10.dp,
                vertical = 7.dp
            ),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Row(
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(
                        RoundedCornerShape(50)
                    )
                    .background(
                        CipherCyan.copy(
                            alpha = 0.90f
                        )
                    )
            )

            Spacer(
                modifier = Modifier.width(7.dp)
            )

            Text(
                text = "FRAME ASSEMBLY",
                color = CipherTextMuted,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp
            )
        }

        Text(
            text = String.format(
                Locale.US,
                "%02d BYTES ASSEMBLED",
                safeByteCount
            ),
            color = CipherCyan,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* SESSION TELEMETRY                                                          */
/* -------------------------------------------------------------------------- */

@Composable
private fun SessionTelemetryStrip(
    transmissions: Int,
    verified: Int,
    rejected: Int,
    lastFrameBytes: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(
                RoundedCornerShape(5.dp)
            )
            .background(
                CipherBlack.copy(
                    alpha = 0.64f
                )
            )
            .border(
                1.dp,
                CipherBorder.copy(
                    alpha = 0.75f
                ),
                RoundedCornerShape(5.dp)
            )
            .padding(
                horizontal = 10.dp,
                vertical = 8.dp
            ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(
            modifier = Modifier.width(62.dp)
        ) {

            Text(
                text = "SESSION",
                color = CipherTextSecondary,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp
            )

            Text(
                text = "TELEMETRY",
                color = CipherTextMuted,
                fontSize = 6.sp,
                letterSpacing = 0.7.sp
            )
        }

        Spacer(
            modifier = Modifier.width(9.dp)
        )

        SessionTelemetryValue(
            label = "TX",
            value =
                transmissions
                    .coerceAtLeast(0)
                    .toString()
        )

        Spacer(
            modifier = Modifier.width(13.dp)
        )

        SessionTelemetryValue(
            label = "OK",
            value =
                verified
                    .coerceAtLeast(0)
                    .toString(),
            valueColor = OpticalGreen
        )

        Spacer(
            modifier = Modifier.width(13.dp)
        )

        SessionTelemetryValue(
            label = "REJ",
            value =
                rejected
                    .coerceAtLeast(0)
                    .toString(),
            valueColor =
                if (rejected > 0) {
                    OpticalRed
                } else {
                    CipherTextSecondary
                }
        )

        Spacer(
            modifier = Modifier.width(13.dp)
        )

        SessionTelemetryValue(
            label = "LAST",
            value =
                if (lastFrameBytes > 0) {
                    String.format(
                        Locale.US,
                        "%02d B",
                        lastFrameBytes
                    )
                } else {
                    "--"
                }
        )

        Spacer(
            modifier = Modifier.weight(1f)
        )

        Column(
            horizontalAlignment =
                Alignment.End
        ) {

            Text(
                text = "CHANNEL",
                color = CipherTextMuted,
                fontSize = 6.sp,
                letterSpacing = 0.7.sp
            )

            Text(
                text = "VISIBLE LIGHT",
                color = CipherCyan,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
        }
    }
}


@Composable
private fun SessionTelemetryValue(
    label: String,
    value: String,
    valueColor: Color = CipherTextSecondary
) {
    Column {

        Text(
            text = label,
            color = CipherTextMuted,
            fontSize = 6.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp
        )

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        Text(
            text = value,
            color = valueColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* FINAL AI PREVIEW — OPTICAL INTELLIGENCE VISUALIZATION                     */
/* -------------------------------------------------------------------------- */

@Composable
private fun AiPreviewStrip(
    debug: RedLedAnalyzer.DebugSample?,
    decoderState: OpticalDecoder.State,
    receivedByteCount: Int,
    fps: Double,
    modifier: Modifier = Modifier
) {
    val infiniteTransition =
        rememberInfiniteTransition(
            label = "ai_optical_analysis"
        )

    val pulseAlpha by
    infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.82f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = 1500,
                        easing =
                            FastOutSlowInEasing
                    ),
                repeatMode =
                    RepeatMode.Reverse
            ),
        label = "ai_optical_analysis_alpha"
    )

    val safeFps =
        if (fps.isFinite()) {
            fps.coerceAtLeast(0.0)
        } else {
            0.0
        }

    val decoderLabel =
        when (decoderState) {
            OpticalDecoder.State.WAITING_FOR_START ->
                "SEARCHING"

            OpticalDecoder.State.START_DETECTED ->
                "ACQUIRED"

            OpticalDecoder.State.WAITING_FOR_DATA ->
                "SYNC"

            OpticalDecoder.State.RECEIVING_PAYLOAD ->
                "RECEIVING"

            OpticalDecoder.State.MESSAGE_COMPLETE ->
                "COMPLETE"
        }

    Column(
        modifier = modifier
            .clip(
                RoundedCornerShape(6.dp)
            )
            .background(
                CipherBlack.copy(
                    alpha = 0.82f
                )
            )
            .border(
                1.dp,
                AiAccent.copy(
                    alpha =
                        0.38f +
                                pulseAlpha * 0.16f
                ),
                RoundedCornerShape(6.dp)
            )
            .padding(
                horizontal = 11.dp,
                vertical = 9.dp
            )
    ) {

        /*
         * HEADER
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(
                            RoundedCornerShape(50)
                        )
                        .background(
                            AiAccent.copy(
                                alpha = pulseAlpha
                            )
                        )
                )

                Spacer(
                    modifier = Modifier.width(7.dp)
                )

                Column {

                    Text(
                        text = "AI OPTICAL ANALYSIS",
                        color = AiAccent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.15.sp
                    )

                    Text(
                        text = "LIVE SENSOR TELEMETRY",
                        color = CipherTextMuted,
                        fontSize = 5.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.75.sp
                    )
                }
            }

            Text(
                text = "PREVIEW",
                color = AiAccent.copy(
                    alpha = 0.72f
                ),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )
        }

        Spacer(
            modifier = Modifier.height(9.dp)
        )

        /*
         * RED DATA CHANNEL
         */
        AiOpticalChannel(
            title = "RED DATA CHANNEL",
            state =
                if (debug?.bitOn == true) {
                    "ON"
                } else {
                    "OFF"
                },
            stateColor =
                if (debug?.bitOn == true) {
                    OpticalRed
                } else {
                    CipherTextMuted
                },
            signal = debug?.redness,
            baseline = debug?.baseline,
            envelope = debug?.envelope,
            threshold = debug?.threshold,
            accentColor = OpticalRed
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        /*
         * GREEN CONTROL CHANNEL
         */
        AiOpticalChannel(
            title = "GREEN CONTROL",
            state =
                if (debug?.greenOn == true) {
                    "ON"
                } else {
                    "OFF"
                },
            stateColor =
                if (debug?.greenOn == true) {
                    OpticalGreen
                } else {
                    CipherTextMuted
                },
            signal = debug?.greenness,
            baseline = debug?.greenBaseline,
            envelope = debug?.greenEnvelope,
            threshold = debug?.greenThreshold,
            accentColor = OpticalGreen
        )

        Spacer(
            modifier = Modifier.height(9.dp)
        )

        /*
         * CAMERA / DECODER TELEMETRY
         */
        Text(
            text = "FRAME TELEMETRY",
            color = CipherTextMuted,
            fontSize = 6.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            AiTelemetryValue(
                label = "FPS",
                value =
                    String.format(
                        Locale.US,
                        "%.1f",
                        safeFps
                    ),
                valueColor = FpsAmber
            )

            AiTelemetryValue(
                label = "FRAME",
                value =
                    if (
                        debug != null &&
                        debug.width > 0 &&
                        debug.height > 0
                    ) {
                        "${debug.width} × ${debug.height}"
                    } else {
                        "—"
                    },
                valueColor = CipherTextSecondary
            )

            AiTelemetryValue(
                label = "ASSEMBLED",
                value =
                    String.format(
                        Locale.US,
                        "%02d B",
                        receivedByteCount
                            .coerceAtLeast(0)
                    ),
                valueColor = CipherCyan
            )

            AiTelemetryValue(
                label = "DECODER",
                value = decoderLabel,
                valueColor = CipherCyan
            )
        }

        Spacer(
            modifier = Modifier.height(6.dp)
        )

        /*
         * CONTROL INDICATION
         *
         * signalLocked in DebugSample represents the current green
         * tracked/control state. It is deliberately NOT described as
         * spatial/positional lock.
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(4.dp)
                )
                .background(
                    CipherSurface.copy(
                        alpha = 0.55f
                    )
                )
                .border(
                    1.dp,
                    CipherBorder.copy(
                        alpha = 0.60f
                    ),
                    RoundedCornerShape(4.dp)
                )
                .padding(
                    horizontal = 8.dp,
                    vertical = 6.dp
                ),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            AiTelemetryValue(
                label = "CONTROL STATE",
                value =
                    if (debug?.signalLocked == true) {
                        "ACTIVE"
                    } else {
                        "IDLE"
                    },
                valueColor =
                    if (debug?.signalLocked == true) {
                        OpticalGreen
                    } else {
                        CipherTextMuted
                    }
            )

            AiTelemetryValue(
                label = "INPUT",
                value = "CAMERA",
                valueColor = CipherTextSecondary
            )

            AiTelemetryValue(
                label = "AI MODEL",
                value = "NOT CONNECTED",
                valueColor =
                    AiAccent.copy(
                        alpha = 0.78f
                    )
            )

            AiTelemetryValue(
                label = "INFERENCE",
                value = "—",
                valueColor = CipherTextMuted
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* AI OPTICAL CHANNEL                                                         */
/* -------------------------------------------------------------------------- */

@Composable
private fun AiOpticalChannel(
    title: String,
    state: String,
    stateColor: Color,
    signal: Float?,
    baseline: Float?,
    envelope: Float?,
    threshold: Float?,
    accentColor: Color
) {
    val scaleMax =
        opticalScaleMax(
            signal = signal,
            baseline = baseline,
            envelope = envelope,
            threshold = threshold
        )

    val signalRatio =
        opticalRatio(
            value = signal,
            scaleMax = scaleMax
        )

    Column {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = title,
                color = CipherTextSecondary,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp
            )

            Text(
                text = state,
                color = stateColor,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        /*
         * SIGNAL BAR
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = "SIGNAL",
                color = CipherTextMuted,
                fontSize = 5.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.55.sp,
                modifier = Modifier.width(43.dp)
            )

            OpticalTelemetryBar(
                progress = signalRatio,
                color = accentColor,
                modifier = Modifier.weight(1f)
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text(
                text =
                    formatSignalValue(
                        signal
                    ),
                color = accentColor.copy(
                    alpha = 0.90f
                ),
                fontSize = 6.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.25.sp,
                modifier = Modifier.width(38.dp)
            )
        }

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        /*
         * RAW MEASUREMENTS
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            AiChannelMetric(
                label = "BASELINE",
                value =
                    formatSignalValue(
                        baseline
                    )
            )

            AiChannelMetric(
                label = "ENVELOPE",
                value =
                    formatSignalValue(
                        envelope
                    )
            )

            AiChannelMetric(
                label = "THRESHOLD",
                value =
                    formatSignalValue(
                        threshold
                    )
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* OPTICAL TELEMETRY BAR                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun OpticalTelemetryBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val safeProgress =
        progress.coerceIn(
            0f,
            1f
        )

    Box(
        modifier = modifier
            .height(5.dp)
            .clip(
                RoundedCornerShape(3.dp)
            )
            .background(
                CipherSurfaceRaised.copy(
                    alpha = 0.92f
                )
            )
            .border(
                1.dp,
                CipherBorder.copy(
                    alpha = 0.48f
                ),
                RoundedCornerShape(3.dp)
            )
    ) {

        if (safeProgress > 0f) {

            Box(
                modifier = Modifier
                    .fillMaxWidth(
                        safeProgress
                    )
                    .height(5.dp)
                    .clip(
                        RoundedCornerShape(3.dp)
                    )
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                color.copy(
                                    alpha = 0.28f
                                ),
                                color.copy(
                                    alpha = 0.88f
                                )
                            )
                        )
                    )
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* AI CHANNEL METRIC                                                          */
/* -------------------------------------------------------------------------- */

@Composable
private fun AiChannelMetric(
    label: String,
    value: String
) {
    Column {

        Text(
            text = label,
            color = CipherTextMuted,
            fontSize = 5.2.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.45.sp
        )

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        Text(
            text = value,
            color = CipherTextSecondary,
            fontSize = 6.4.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.25.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* TELEMETRY HELPERS                                                          */
/* -------------------------------------------------------------------------- */

private fun opticalScaleMax(
    signal: Float?,
    baseline: Float?,
    envelope: Float?,
    threshold: Float?
): Float {
    val values =
        listOf(
            signal,
            baseline,
            envelope,
            threshold
        )
            .filter {
                it != null &&
                        it.isFinite() &&
                        it >= 0f
            }
            .map {
                it ?: 0f
            }

    if (values.isEmpty()) {
        return 1f
    }

    return maxOf(
        1f,
        values.maxOrNull() ?: 1f
    )
}


private fun opticalRatio(
    value: Float?,
    scaleMax: Float
): Float {
    if (
        value == null ||
        !value.isFinite() ||
        scaleMax <= 0f
    ) {
        return 0f
    }

    return (
            value /
                    scaleMax
            )
        .coerceIn(
            0f,
            1f
        )
}


private fun formatSignalValue(
    value: Float?
): String {
    if (
        value == null ||
        !value.isFinite()
    ) {
        return "—"
    }

    return String.format(
        Locale.US,
        "%.3f",
        value
    )
}


@Composable
private fun AiTelemetryValue(
    label: String,
    value: String,
    valueColor: Color = AiAccent.copy(
        alpha = 0.78f
    )
) {
    Column {

        Text(
            text = label,
            color = CipherTextMuted,
            fontSize = 5.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.55.sp
        )

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        Text(
            text = value,
            color = valueColor,
            fontSize = 6.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.30.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* PACKET VERIFIED / PACKET ANALYSIS                                          */
/* -------------------------------------------------------------------------- */

@Composable
private fun PacketVerifiedHud(
    packet: CipherBeamPacket?,
    message: String?,
    sessionTransmissions: Int,
    sessionVerified: Int,
    sessionRejected: Int,
    lastFrameBytes: Int,
    debug: RedLedAnalyzer.DebugSample?,
    fps: Double,
    mode: ReceiverMode,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(
                RoundedCornerShape(7.dp)
            )
            .background(
                CipherSurfaceGlass.copy(
                    alpha = 0.95f
                )
            )
            .border(
                1.dp,
                CipherCyan.copy(
                    alpha = 0.55f
                ),
                RoundedCornerShape(7.dp)
            )
            .padding(
                horizontal = 13.dp,
                vertical = 12.dp
            )
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(
                            RoundedCornerShape(50)
                        )
                        .background(
                            CipherCyan
                        )
                )

                Spacer(
                    modifier = Modifier.width(7.dp)
                )

                Text(
                    text = "PACKET ANALYSIS",
                    color = CipherCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
            }

            Text(
                text = "VERIFIED",
                color = OpticalGreen,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp
            )
        }

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        if (packet != null) {

            PacketProtocolMetadata(
                packet = packet
            )

            Spacer(
                modifier = Modifier.height(9.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "PAYLOAD",
                    color = CipherTextMuted,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )

                Text(
                    text = "${packet.length} BYTE",
                    color = CipherTextSecondary,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(
                modifier = Modifier.height(5.dp)
            )

            if (!message.isNullOrEmpty()) {

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(5.dp)
                        )
                        .background(
                            CipherBlack.copy(
                                alpha = 0.62f
                            )
                        )
                        .border(
                            1.dp,
                            CipherBorder.copy(
                                alpha = 0.72f
                            ),
                            RoundedCornerShape(5.dp)
                        )
                        .padding(
                            horizontal = 10.dp,
                            vertical = 9.dp
                        )
                ) {

                    Text(
                        text = message,
                        color = CipherTextPrimary,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp
                    )
                }
            } else {

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(5.dp)
                        )
                        .background(
                            CipherBlack.copy(
                                alpha = 0.62f
                            )
                        )
                        .border(
                            1.dp,
                            CipherBorder.copy(
                                alpha = 0.72f
                            ),
                            RoundedCornerShape(5.dp)
                        )
                        .padding(
                            horizontal = 10.dp,
                            vertical = 9.dp
                        )
                ) {

                    Text(
                        text = "NON-PRINTABLE PAYLOAD",
                        color = CipherTextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(7.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "PAYLOAD MODE",
                    color = CipherTextMuted,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                Text(
                    text = "PLAINTEXT • ENCRYPTION DEFERRED",
                    color = CipherTextSecondary,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(
                modifier = Modifier.height(9.dp)
            )

            SessionTelemetryStrip(
                transmissions = sessionTransmissions,
                verified = sessionVerified,
                rejected = sessionRejected,
                lastFrameBytes = lastFrameBytes,
                modifier = Modifier.fillMaxWidth()
            )

            if (
                mode == ReceiverMode.AI_PREVIEW
            ) {
                Spacer(
                    modifier = Modifier.height(7.dp)
                )

                AiPreviewStrip(
                    debug = debug,
                    decoderState =
                        OpticalDecoder.State.MESSAGE_COMPLETE,
                    receivedByteCount = packet.length,
                    fps = fps,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


/* -------------------------------------------------------------------------- */
/* PACKET PROTOCOL METADATA                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun PacketProtocolMetadata(
    packet: CipherBeamPacket
) {
    Column {

        PacketMetadataRow(
            label = "PROTOCOL",
            value = "CIPHERBEAM v1"
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "VERSION",
            value =
                "0x%02X".format(
                    packet.version
                )
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "FLAGS",
            value =
                "0x%02X".format(
                    packet.flags
                )
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "ALGORITHM",
            value =
                formatAlgorithm(
                    packet.flags
                )
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "PAYLOAD",
            value =
                "${packet.length} BYTE"
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "FRAME",
            value =
                "${packet.length + 6} BYTES"
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "CRC",
            value =
                "0x%04X".format(
                    packet.crc ?: 0
                )
        )

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        PacketMetadataRow(
            label = "CRC STATUS",
            value = "VALID",
            valueColor = OpticalGreen
        )
    }
}


/* -------------------------------------------------------------------------- */
/* ALGORITHM METADATA                                                         */
/* -------------------------------------------------------------------------- */

private fun formatAlgorithm(
    flags: Int
): String {
    val algorithmId =
        flags and 0x0F

    val algorithmName =
        when (algorithmId) {
            0x01 ->
                "CHACHA20-POLY1305"

            0x02 ->
                "AES-256-GCM"

            else ->
                "UNKNOWN"
        }

    return String.format(
        Locale.US,
        "0x%02X  %s",
        algorithmId,
        algorithmName
    )
}


/* -------------------------------------------------------------------------- */
/* PACKET METADATA ROW                                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun PacketMetadataRow(
    label: String,
    value: String,
    valueColor: Color = CipherTextSecondary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text = label,
            color = CipherTextMuted,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )

        Text(
            text = value,
            color = valueColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.35.sp
        )
    }
}


/* -------------------------------------------------------------------------- */
/* PACKET FAILURE                                                             */
/* -------------------------------------------------------------------------- */

@Composable
private fun PacketFailureHud(
    reason: String,
    sessionTransmissions: Int,
    sessionVerified: Int,
    sessionRejected: Int,
    lastFrameBytes: Int,
    debug: RedLedAnalyzer.DebugSample?,
    fps: Double,
    mode: ReceiverMode,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(
                RoundedCornerShape(7.dp)
            )
            .background(
                CipherBlack.copy(
                    alpha = 0.90f
                )
            )
            .border(
                1.dp,
                OpticalRed.copy(
                    alpha = 0.55f
                ),
                RoundedCornerShape(7.dp)
            )
            .padding(
                horizontal = 13.dp,
                vertical = 11.dp
            )
    ) {

        Row(
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(
                        RoundedCornerShape(50)
                    )
                    .background(
                        OpticalRed
                    )
            )

            Spacer(
                modifier = Modifier.width(7.dp)
            )

            Text(
                text = "PACKET REJECTED",
                color = OpticalRed,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp
            )
        }

        Spacer(
            modifier = Modifier.height(6.dp)
        )

        Text(
            text = reason,
            color = CipherTextSecondary,
            fontSize = 9.sp
        )

        Spacer(
            modifier = Modifier.height(9.dp)
        )

        SessionTelemetryStrip(
            transmissions = sessionTransmissions,
            verified = sessionVerified,
            rejected = sessionRejected,
            lastFrameBytes = lastFrameBytes,
            modifier = Modifier.fillMaxWidth()
        )

        if (
            mode == ReceiverMode.AI_PREVIEW
        ) {
            Spacer(
                modifier = Modifier.height(7.dp)
            )

            AiPreviewStrip(
                debug = debug,
                decoderState =
                    OpticalDecoder.State.WAITING_FOR_START,
                receivedByteCount = 0,
                fps = fps,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


/* -------------------------------------------------------------------------- */
/* CROSSHAIR                                                                  */
/* -------------------------------------------------------------------------- */

@Composable
private fun OpticalCrosshair(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.size(62.dp)
    ) {

        val center =
            Offset(
                x = size.width / 2f,
                y = size.height / 2f
            )

        val crosshairSize =
            29.dp.toPx()

        val gap =
            8.dp.toPx()

        val strokeWidth =
            1.35.dp.toPx()

        val crosshairColor =
            Color.White.copy(
                alpha = 0.52f
            )

        drawLine(
            color = crosshairColor,
            start =
                Offset(
                    center.x -
                            crosshairSize,
                    center.y
                ),
            end =
                Offset(
                    center.x - gap,
                    center.y
                ),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = crosshairColor,
            start =
                Offset(
                    center.x + gap,
                    center.y
                ),
            end =
                Offset(
                    center.x +
                            crosshairSize,
                    center.y
                ),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = crosshairColor,
            start =
                Offset(
                    center.x,
                    center.y -
                            crosshairSize
                ),
            end =
                Offset(
                    center.x,
                    center.y - gap
                ),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = crosshairColor,
            start =
                Offset(
                    center.x,
                    center.y + gap
                ),
            end =
                Offset(
                    center.x,
                    center.y +
                            crosshairSize
                ),
            strokeWidth = strokeWidth
        )

        drawCircle(
            color =
                Color.White.copy(
                    alpha = 0.68f
                ),
            radius = 1.2.dp.toPx(),
            center = center
        )
    }
}


/* -------------------------------------------------------------------------- */
/* WAVEFORM                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun OpticalWaveformPanel(
    samples: List<OpticalWaveformSample>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(
                RoundedCornerShape(6.dp)
            )
            .background(
                CipherBlack.copy(
                    alpha = 0.72f
                )
            )
            .border(
                1.dp,
                CipherBorder.copy(
                    alpha = 0.75f
                ),
                RoundedCornerShape(6.dp)
            )
            .padding(
                horizontal = 10.dp,
                vertical = 8.dp
            )
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = "OPTICAL SIGNAL",
                color = CipherTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                WaveformLegend(
                    color = OpticalRed,
                    label = "RED"
                )

                Spacer(
                    modifier = Modifier.width(9.dp)
                )

                WaveformLegend(
                    color = OpticalGreen,
                    label = "GREEN"
                )
            }
        }

        Spacer(
            modifier = Modifier.height(5.dp)
        )

        OpticalWaveformCanvas(
            samples = samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        )
    }
}


@Composable
private fun WaveformLegend(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(
                    RoundedCornerShape(50)
                )
                .background(color)
        )

        Spacer(
            modifier = Modifier.width(4.dp)
        )

        Text(
            text = label,
            color = CipherTextMuted,
            fontSize = 7.sp,
            letterSpacing = 0.7.sp
        )
    }
}


@Composable
private fun OpticalWaveformCanvas(
    samples: List<OpticalWaveformSample>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(
                RoundedCornerShape(3.dp)
            )
            .background(
                CipherSurface.copy(
                    alpha = 0.48f
                )
            )
    ) {

        val width =
            size.width

        val height =
            size.height

        val redHigh =
            height * 0.20f

        val redLow =
            height * 0.37f

        val greenHigh =
            height * 0.63f

        val greenLow =
            height * 0.80f

        drawLine(
            color = CipherBorder.copy(
                alpha = 0.32f
            ),
            start = Offset(
                0f,
                redLow
            ),
            end = Offset(
                width,
                redLow
            ),
            strokeWidth = 1.dp.toPx()
        )

        drawLine(
            color = CipherBorder.copy(
                alpha = 0.32f
            ),
            start = Offset(
                0f,
                greenLow
            ),
            end = Offset(
                width,
                greenLow
            ),
            strokeWidth = 1.dp.toPx()
        )

        val gridSpacing =
            width / 10f

        for (index in 1 until 10) {

            val x =
                gridSpacing * index

            drawLine(
                color = CipherBorder.copy(
                    alpha = 0.15f
                ),
                start = Offset(
                    x,
                    0f
                ),
                end = Offset(
                    x,
                    height
                ),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (samples.isEmpty()) {
            return@Canvas
        }

        val visibleSamples =
            samples.takeLast(200)

        val sampleCount =
            max(
                visibleSamples.size,
                1
            )

        val stepX =
            width /
                    max(
                        sampleCount - 1,
                        1
                    )

        val redPath =
            Path()

        val greenPath =
            Path()

        visibleSamples.forEachIndexed {
                index,
                sample
            ->

            val x =
                index * stepX

            val redY =
                if (sample.redOn) {
                    redHigh
                } else {
                    redLow
                }

            if (index == 0) {

                redPath.moveTo(
                    x,
                    redY
                )

            } else {

                val previous =
                    visibleSamples[index - 1]

                val previousY =
                    if (previous.redOn) {
                        redHigh
                    } else {
                        redLow
                    }

                redPath.lineTo(
                    x,
                    previousY
                )

                redPath.lineTo(
                    x,
                    redY
                )
            }

            val greenY =
                if (sample.greenOn) {
                    greenHigh
                } else {
                    greenLow
                }

            if (index == 0) {

                greenPath.moveTo(
                    x,
                    greenY
                )

            } else {

                val previous =
                    visibleSamples[index - 1]

                val previousY =
                    if (previous.greenOn) {
                        greenHigh
                    } else {
                        greenLow
                    }

                greenPath.lineTo(
                    x,
                    previousY
                )

                greenPath.lineTo(
                    x,
                    greenY
                )
            }
        }

        drawPath(
            path = redPath,
            color = OpticalRed.copy(
                alpha = 0.90f
            ),
            style = Stroke(
                width = 1.4.dp.toPx()
            )
        )

        drawPath(
            path = greenPath,
            color = OpticalGreen.copy(
                alpha = 0.90f
            ),
            style = Stroke(
                width = 1.4.dp.toPx()
            )
        )
    }
}