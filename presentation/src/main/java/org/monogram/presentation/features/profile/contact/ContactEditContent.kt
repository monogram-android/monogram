package org.monogram.presentation.features.profile.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.core.ui.shimmerBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditContent(component: ContactEditComponent) {
    val state by component.state.subscribeAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.editor.error) {
        val message = state.editor.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        component.onDismissError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.edit_contact_title_short),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = component::onSave,
                        enabled = state.editor.canSave && state.firstName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.isLoading) {
            ContactEditLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ContactEditHeader(state = state)
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        SettingsTextField(
                            value = state.firstName,
                            onValueChange = component::onUpdateFirstName,
                            placeholder = stringResource(R.string.first_name_label),
                            icon = Icons.Rounded.Person,
                            position = ItemPosition.TOP,
                            itemSpacing = 0.dp
                        )
                        SettingsTextField(
                            value = state.lastName,
                            onValueChange = component::onUpdateLastName,
                            placeholder = stringResource(R.string.last_name_label),
                            icon = Icons.Rounded.Person,
                            position = ItemPosition.BOTTOM,
                            itemSpacing = 0.dp
                        )
                    }
                }

                if (!state.phoneNumber.isNullOrBlank()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.contact_edit_share_phone_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (state.needPhoneNumberPrivacyException) {
                                            stringResource(R.string.contact_edit_share_phone_required)
                                        } else {
                                            stringResource(R.string.contact_edit_share_phone_subtitle)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    modifier = Modifier.padding(start = 12.dp),
                                    checked = state.sharePhoneNumber,
                                    onCheckedChange = component::onToggleSharePhoneNumber,
                                    enabled = !state.editor.isSaving
                                )
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = component::onRemoveContact,
                        enabled = state.user?.isContact == true && !state.editor.isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text(
                            text = stringResource(R.string.action_remove_contact),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }

    if (state.editor.showDiscardChangesDialog) {
        ConfirmationSheet(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.photo_editor_discard_title),
            description = stringResource(R.string.photo_editor_discard_message),
            confirmText = stringResource(R.string.photo_editor_discard_button),
            onConfirm = component::onConfirmDiscardChanges,
            onDismiss = component::onDismissDiscardChanges
        )
    }
}

@Composable
private fun ContactEditHeader(state: ContactEditComponent.State) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(
                path = state.user?.avatarPath,
                fallbackPath = state.user?.personalAvatarPath,
                name = contactDisplayName(
                    state.user?.firstName,
                    state.user?.lastName,
                    state.phoneNumber
                ),
                size = 96.dp,
                fontSize = 22
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = listOfNotNull(
                    state.user?.firstName?.takeIf { it.isNotBlank() },
                    state.user?.lastName?.takeIf { it.isNotBlank() }
                ).joinToString(" "),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (!state.phoneNumber.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.phoneNumber.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun contactDisplayName(
    firstName: String?,
    lastName: String?,
    phoneNumber: String?
): String {
    val fullName = listOfNotNull(
        firstName?.takeIf { it.isNotBlank() },
        lastName?.takeIf { it.isNotBlank() }
    ).joinToString(" ")
    return fullName.ifBlank { phoneNumber.orEmpty() }
}

@Composable
private fun ContactEditLoading(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .shimmerBackground(shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(24.dp)
                            .shimmerBackground(shape = RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(16.dp)
                            .shimmerBackground(shape = RoundedCornerShape(12.dp))
                    )
                }
            }
        }

        items((0 until 3).toList()) { index ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(16.dp)
                            .shimmerBackground(shape = RoundedCornerShape(12.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (index == 2) 52.dp else 64.dp)
                            .shimmerBackground(shape = RoundedCornerShape(20.dp))
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shimmerBackground(shape = RoundedCornerShape(16.dp))
            )
        }
    }
}
