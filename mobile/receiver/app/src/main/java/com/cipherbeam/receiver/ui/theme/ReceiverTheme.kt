package com.cipherbeam.receiver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CipherDarkColorScheme = darkColorScheme(
    primary = CipherCyan,
    onPrimary = CipherBlack,

    secondary = OpticalGreen,
    onSecondary = CipherBlack,

    tertiary = OpticalRed,
    onTertiary = CipherTextPrimary,

    background = CipherBlack,
    onBackground = CipherTextPrimary,

    surface = CipherSurface,
    onSurface = CipherTextPrimary,

    surfaceVariant = CipherSurfaceRaised,
    onSurfaceVariant = CipherTextSecondary,

    outline = CipherBorder,
    outlineVariant = CipherBorderBright
)

@Composable
fun CipherBeamReceiverTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CipherDarkColorScheme,
        content = content
    )
}