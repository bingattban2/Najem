package com.najimwifi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najimwifi.app.R
import com.najimwifi.app.data.settings.AppearanceMode

/** Cairo typography, rounded surfaces and day/night colors from TransVibe's visual language. */
private val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_medium, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
    Font(R.font.cairo_extrabold, FontWeight.ExtraBold)
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Cairo, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val NajimTypography = Typography(
    displayLarge = type(32, 44, FontWeight.Bold),
    displayMedium = type(28, 40, FontWeight.Bold),
    displaySmall = type(26, 38, FontWeight.Bold),
    headlineLarge = type(24, 36, FontWeight.Bold),
    headlineMedium = type(20, 30, FontWeight.Bold),
    headlineSmall = type(19, 28, FontWeight.Bold),
    titleLarge = type(18, 28, FontWeight.SemiBold),
    titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(14, 22, FontWeight.SemiBold),
    bodyLarge = type(16, 26),
    bodyMedium = type(14, 22),
    bodySmall = type(12, 19),
    labelLarge = type(14, 21, FontWeight.SemiBold),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(10, 18, FontWeight.Medium)
)

private val NajimShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NajimWifiTheme(appearance: AppearanceMode = AppearanceMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (when (appearance) {
            AppearanceMode.SYSTEM -> isSystemInDarkTheme()
            AppearanceMode.DARK -> true
            AppearanceMode.LIGHT -> false
        }) NajimDarkColorScheme else NajimLightColorScheme,
        typography = NajimTypography,
        shapes = NajimShapes,
        content = content
    )
}
