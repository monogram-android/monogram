package org.monogram.presentation.features.profile.logs.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight

@Composable
fun StatusChangeRow(label: String, status: String) {
    val formattedStatus = remember(status) {
        val name = status.substringBefore("{").trim()
        if (name.isEmpty() || name.length > 30) {
            when {
                status.contains("Restricted", ignoreCase = true) -> "Restricted"
                status.contains("Member", ignoreCase = true) -> "Member"
                status.contains("Administrator", ignoreCase = true) -> "Admin"
                status.contains("Creator", ignoreCase = true) -> "Owner"
                status.contains("Banned", ignoreCase = true) -> "Banned"
                status.contains("Left", ignoreCase = true) -> "Left"
                else -> "Unknown"
            }
        } else {
            name.replace("ChatMemberStatus", "")
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = formattedStatus,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = when (formattedStatus) {
                "Restricted", "Banned" -> MaterialTheme.colorScheme.error
                "Admin", "Owner", "Administrator", "Creator" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
