package com.najimwifi.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Consistent name avatar without stored photos or extra network resources. */
@Composable
fun CustomerAvatar(name: String) {
    Box(
        modifier = Modifier.size(44.dp).background(
            MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().firstOrNull()?.toString() ?: "؟",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold)
    }
}
