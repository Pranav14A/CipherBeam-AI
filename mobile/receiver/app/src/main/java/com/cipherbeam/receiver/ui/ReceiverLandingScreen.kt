package com.cipherbeam.receiver.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

@Composable
fun ReceiverLandingScreen(
    onInitializeReceiver: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition =
        rememberInfiniteTransition(
            label = "landing_transition"
        )

    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_progress"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CipherBlack)
    ) {

        /*
         * Extremely restrained optical scan.
         *
         * This is only a visual landing animation.
         * It has no connection to the camera or decoder.
         */
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {

            val scanY =
                size.height * (0.20f + scanProgress * 0.60f)

            drawLine(
                color = CipherCyan.copy(alpha = 0.16f),
                start = Offset(
                    x = size.width * 0.08f,
                    y = scanY
                ),
                end = Offset(
                    x = size.width * 0.92f,
                    y = scanY
                ),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            drawLine(
                color = CipherCyan.copy(alpha = 0.05f),
                start = Offset(
                    x = size.width * 0.08f,
                    y = scanY - 16.dp.toPx()
                ),
                end = Offset(
                    x = size.width * 0.92f,
                    y = scanY - 16.dp.toPx()
                ),
                strokeWidth = 1.dp.toPx()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 28.dp,
                    vertical = 32.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(
                modifier = Modifier.weight(0.8f)
            )

            Text(
                text = "CIPHERBEAM",
                color = CipherTextPrimary,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp
            )

            Spacer(
                modifier = Modifier.height(7.dp)
            )

            Text(
                text = "OPTICAL RECEIVER SYSTEM",
                color = CipherTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.5.sp
            )

            Spacer(
                modifier = Modifier.height(62.dp)
            )

            Text(
                text = "READY TO DECRYPT?",
                color = CipherTextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Text(
                text = "Initialize the optical receiver to establish\n"
                        + "a visible-light communication channel.",
                color = CipherTextMuted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                letterSpacing = 0.3.sp
            )

            Spacer(
                modifier = Modifier.height(34.dp)
            )

            /*
             * Main action.
             *
             * The button deliberately looks more like an
             * instrument control than a generic Material button.
             */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = CipherCyan.copy(
                            alpha = pulseAlpha
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .background(
                        CipherSurfaceRaised
                    )
                    .clickable(
                        onClick = onInitializeReceiver
                    ),
                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = "INITIALIZE RECEIVER",
                    color = CipherCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp
                )
            }

            Spacer(
                modifier = Modifier.height(42.dp)
            )

            LandingSystemInfo()

            Spacer(
                modifier = Modifier.weight(1.1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(
                            RoundedCornerShape(50)
                        )
                        .background(
                            OpticalGreen.copy(
                                alpha = pulseAlpha
                            )
                        )
                )

                Spacer(
                    modifier = Modifier.size(8.dp)
                )

                Text(
                    text = "SYSTEM READY",
                    color = CipherTextMuted,
                    fontSize = 9.sp,
                    letterSpacing = 1.8.sp
                )
            }
        }
    }
}

@Composable
private fun LandingSystemInfo() {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(8.dp)
            )
            .background(CipherSurface)
            .border(
                width = 1.dp,
                color = CipherBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(
                horizontal = 18.dp,
                vertical = 15.dp
            ),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {

        LandingInfoRow(
            label = "OPTICAL CHANNEL",
            value = "VISIBLE LIGHT"
        )

        LandingInfoRow(
            label = "PROTOCOL",
            value = "CIPHERBEAM v1"
        )

        LandingInfoRow(
            label = "LINK",
            value = "STANDBY",
            valueColor = OpticalGreen
        )

        LandingInfoRow(
            label = "DECODER",
            value = "STANDARD"
        )
    }
}

@Composable
private fun LandingInfoRow(
    label: String,
    value: String,
    valueColor: Color = CipherTextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = label,
            color = CipherTextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp
        )

        Text(
            text = value,
            color = valueColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
    }
}