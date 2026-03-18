package org.monogram.presentation.features.profile.logs.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.monogram.presentation.R

@Composable
fun StatusChangeRow(label: String, status: String) {
    val restrictedStr = stringResource(R.string.logs_role_restricted)
    val memberStr = stringResource(R.string.logs_role_member)
    val adminStr = stringResource(R.string.logs_role_admin)
    val ownerStr = stringResource(R.string.logs_role_owner)
    val bannedStr = stringResource(R.string.logs_role_banned)
    val unknownStr = "Unknown"

    val formattedStatus = remember(status) {
        val name = status.substringBefore("{").trim()
        if (name.isEmpty() || name.length > 30) {
            when {
                status.contains("Restricted", ignoreCase = true) -> restrictedStr
                status.contains("Member", ignoreCase = true) -> memberStr
                status.contains("Administrator", ignoreCase = true) -> adminStr
                status.contains("Creator", ignoreCase = true) -> ownerStr
                status.contains("Banned", ignoreCase = true) -> bannedStr
                status.contains("Left", ignoreCase = true) -> "Left"
                else -> unknownStr
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
                restrictedStr, bannedStr -> MaterialTheme.colorScheme.error
                adminStr, ownerStr, "Administrator", "Creator" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
