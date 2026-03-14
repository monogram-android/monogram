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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.R
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
        ReportReason("spam", stringResource(R.string.report_reason_spam), stringResource(R.string.report_reason_spam_description), Icons.Outlined.Report),
        ReportReason("violence", stringResource(R.string.report_reason_violence), stringResource(R.string.report_reason_violence_description), Icons.Outlined.Gavel),
        ReportReason(
            "pornography",
            stringResource(R.string.report_reason_pornography),
            stringResource(R.string.report_reason_pornography_description),
            Icons.Outlined.NoAdultContent
        ),
        ReportReason("child_abuse", stringResource(R.string.report_reason_child_abuse), stringResource(R.string.report_reason_child_abuse_description), Icons.Outlined.ChildCare),
        ReportReason("copyright", stringResource(R.string.report_reason_copyright), stringResource(R.string.report_reason_copyright_description), Icons.Outlined.Copyright),
        ReportReason("fake", stringResource(R.string.report_reason_fake), stringResource(R.string.report_reason_fake_description), Icons.Outlined.AccountCircle),
        ReportReason(
            "illegal_drugs",
            stringResource(R.string.report_reason_illegal_drugs),
            stringResource(R.string.report_reason_illegal_drugs_description),
            Icons.Outlined.Warning
        ),
        ReportReason(
            "personal_details",
            stringResource(R.string.report_reason_personal_details),
            stringResource(R.string.report_reason_personal_details_description),
            Icons.Outlined.VisibilityOff
        ),
        ReportReason(
            "unrelated_location",
            stringResource(R.string.report_reason_unrelated_location),
            stringResource(R.string.report_reason_unrelated_location_description),
            Icons.Outlined.LocationOff
        ),
        ReportReason("custom", stringResource(R.string.report_reason_other), stringResource(R.string.report_reason_other_description), Icons.Outlined.MoreHoriz)
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
                        text = stringResource(R.string.report_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.report_description),
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
                        text = stringResource(R.string.report_details_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = stringResource(R.string.report_details_placeholder),
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
                            Text(stringResource(R.string.cd_back), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onReasonSelected(customText) },
                            enabled = customText.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.report_send), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
