package com.johnc4rl0.smsforwarder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 theme aligned with graphic mockups (indigo-violet seed).
 */
private val MdPrimary = Color(0xFF4F46E5)
private val MdOnPrimary = Color(0xFFFFFFFF)
private val MdPrimaryContainer = Color(0xFFE0E0FF)
private val MdOnPrimaryContainer = Color(0xFF0F0C5C)

private val MdSecondary = Color(0xFF5B5D72)
private val MdOnSecondary = Color(0xFFFFFFFF)
private val MdSecondaryContainer = Color(0xFFE0E0F9)
private val MdOnSecondaryContainer = Color(0xFF181A2C)

private val MdTertiary = Color(0xFF78536B)
private val MdOnTertiary = Color(0xFFFFFFFF)
private val MdTertiaryContainer = Color(0xFFFFD7EE)
private val MdOnTertiaryContainer = Color(0xFF2E1126)

private val LightColors = lightColorScheme(
    primary = MdPrimary,
    onPrimary = MdOnPrimary,
    primaryContainer = MdPrimaryContainer,
    onPrimaryContainer = MdOnPrimaryContainer,
    secondary = MdSecondary,
    onSecondary = MdOnSecondary,
    secondaryContainer = MdSecondaryContainer,
    onSecondaryContainer = MdOnSecondaryContainer,
    tertiary = MdTertiary,
    onTertiary = MdOnTertiary,
    tertiaryContainer = MdTertiaryContainer,
    onTertiaryContainer = MdOnTertiaryContainer,
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B23),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF1C1A6E),
    primaryContainer = Color(0xFF3632A5),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFC4C4DC),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E0F9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B23),
    surfaceContainer = Color(0xFF1F1F27),
    surfaceContainerHigh = Color(0xFF2A2A32),
    surfaceContainerHighest = Color(0xFF35343D),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
)

@Composable
fun SmsForwarderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
