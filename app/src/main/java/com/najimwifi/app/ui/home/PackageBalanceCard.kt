package com.najimwifi.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Reusable color-coded package tile for home, customers and package management. */
@Composable
fun PackageBalanceCard(
    packageName: String,
    displayValue: String,
    colorArgb: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    caption: String = "كرت",
    selected: Boolean = false,
    compact: Boolean = false
) {
    val accent = Color(if (colorArgb ushr 24 == 0) colorArgb or 0xFF000000.toInt() else colorArgb)
    Card(
        modifier = modifier.then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        shape = RoundedCornerShape(if (compact) 14.dp else 16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(
            horizontal = if (compact) 4.dp else 7.dp,
            vertical = if (compact) 7.dp else 13.dp
        ), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(if (compact) 28.dp else 40.dp).background(accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null,
                    tint = accent, modifier = Modifier.size(if (compact) 17.dp else 23.dp))
            }
            Text(packageName, style = if (compact) MaterialTheme.typography.labelSmall
                else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(displayValue, style = if (compact) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleLarge,
                color = accent, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (selected) "✓ محددة" else caption, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
