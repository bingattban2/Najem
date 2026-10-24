package com.najimwifi.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared Compose-only primitives adapted from the owner's Stitch reference screens. */
@Composable
fun StitchPageHeader(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (onBack != null) TextButton(onClick = onBack) { Text("رجوع") }
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StitchTag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50)) {
        Text(westernDigits(text), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StitchPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}


/** Consistent 1dp outline in light/dark modes; form validation still uses the error color. */
@Composable
fun softOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
)

/** Readable Western digits in any string that may mix Arabic-Indic numeral glyphs. */
fun westernDigits(value: String): String = buildString(value.length) {
    for (char in value) {
        append(when (char) {
            in '٠'..'٩' -> '0' + (char - '٠')
            in '۰'..'۹' -> '0' + (char - '۰')
            else -> char
        })
    }
}
