package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportChatDialog(
    onDismiss: () -> Unit,
    onReasonSelected: (String) -> Unit
) {
    var showCustomInput by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    val reasons = listOf(
        ReportReason("spam", "Spam", "Unwanted commercial content or scams", Icons.Outlined.Report),
        ReportReason("violence", "Violence", "Threats or glorification of violence", Icons.Outlined.Gavel),
        ReportReason(
            "pornography",
            "Pornography",
            "Inappropriate media or explicit language",
            Icons.Outlined.NoAdultContent
        ),
        ReportReason("child_abuse", "Child safety", "Content involving harm to minors", Icons.Outlined.ChildCare),
        ReportReason("copyright", "Copyright", "Using someone else's intellectual property", Icons.Outlined.Copyright),
        ReportReason("fake", "Impersonation", "Pretending to be someone else or a bot", Icons.Outlined.AccountCircle),
        ReportReason(
            "illegal_drugs",
            "Illegal drugs",
            "Promoting the sale or use of prohibited substances",
            Icons.Outlined.Warning
        ),
        ReportReason(
            "personal_details",
            "Privacy violation",
            "Sharing private contact info or addresses",
            Icons.Outlined.VisibilityOff
        ),
        ReportReason(
            "unrelated_location",
            "Unrelated location",
            "Content not relevant to this specific place",
            Icons.Outlined.LocationOff
        ),
        ReportReason("custom", "Other", "Something else that violates our terms", Icons.Outlined.MoreHoriz)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            if (!showCustomInput) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "Why are you reporting this?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your report is anonymous. We will review the chat history to ensure safety.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    itemsIndexed(reasons) { _, reason ->
                        if (reason.id == "custom") {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }

                        ReportReasonItem(
                            reason = reason,
                            onClick = {
                                if (reason.id == "custom") {
                                    showCustomInput = true
                                } else {
                                    onReasonSelected(reason.id)
                                }
                            }
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "Report details",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = "Describe the issue...",
                        icon = Icons.Rounded.Edit,
                        position = ItemPosition.STANDALONE,
                        minLines = 3
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCustomInput = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Back", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onReasonSelected(customText) },
                            enabled = customText.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Send Report", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportReasonItem(
    reason: ReportReason,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = reason.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Text(
                text = reason.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = reason.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

private data class ReportReason(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector
)
