package com.najimwifi.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Visual palette adapted from the owner's TransVibe reference. */
internal val NajimLightColorScheme = lightColorScheme(
    primary = Color(0xFF3525CD), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF), onPrimaryContainer = Color(0xFF0F0069),
    secondary = Color(0xFF712AE2), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFF), onSecondaryContainer = Color(0xFF25005A),
    tertiary = Color(0xFF006982), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB7EAFF), onTertiaryContainer = Color(0xFF001F28),
    background = Color(0xFFFCF8FF), onBackground = Color(0xFF1B1B25),
    surface = Color(0xFFFCF8FF), onSurface = Color(0xFF1B1B25),
    surfaceVariant = Color(0xFFE3E1EF), onSurfaceVariant = Color(0xFF464555),
    surfaceContainerLow = Color(0xFFF5F2FF),
    surfaceContainer = Color(0xFFEFECFB),
    surfaceContainerHigh = Color(0xFFE3E1EF),
    surfaceContainerHighest = Color(0xFFC7C4D8),
    outline = Color(0xFF797784), outlineVariant = Color(0xFFC7C4D8),
    error = Color(0xFFDC2626), onError = Color.White,
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF410002)
)

internal val NajimDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA5A8FF), onPrimary = Color(0xFF1D1B50),
    primaryContainer = Color(0xFF312E81), onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC4B5FD), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4C1D95), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF67E8F9), onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF0E7490), onTertiaryContainer = Color(0xFFBDF3F6),
    background = Color(0xFF0E0E18), onBackground = Color(0xFFE7E5F0),
    surface = Color(0xFF0E0E18), onSurface = Color(0xFFE7E5F0),
    surfaceVariant = Color(0xFF222232), onSurfaceVariant = Color(0xFFC9C5D2),
    surfaceContainerLow = Color(0xFF13131F),
    surfaceContainer = Color(0xFF181826),
    surfaceContainerHigh = Color(0xFF222232),
    surfaceContainerHighest = Color(0xFF2A2A3C),
    outline = Color(0xFF938F9C), outlineVariant = Color(0xFF2A2A3C),
    error = Color(0xFFF87171), onError = Color(0xFF690005),
    errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFFDAD6)
)
