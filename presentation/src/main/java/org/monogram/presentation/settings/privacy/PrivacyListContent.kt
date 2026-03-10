package org.monogram.presentation.settings.privacy

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.PrivacyValue
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyListContent(component: PrivacyListComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current

    val blueColor = Color(0xFF4285F4)
    val greenColor = Color(0xFF34A853)
    val orangeColor = Color(0xFFF9AB00)
    val pinkColor = Color(0xFFFF6D66)
    val tealColor = Color(0xFF00BFA5)
    val purpleColor = Color(0xFFAF52DE)
    val indigoColor = Color(0xFF536DFE)
    val redColor = Color(0xFFFF3B30)

    var showTtlSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Privacy and Security",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AnimatedContent(
            targetState = state.isLoading,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "PrivacyListContent",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { isLoading ->
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        SectionHeader("Privacy")
                        SettingsTile(
                            icon = Icons.Rounded.Block,
                            title = "Blocked Users",
                            subtitle = if (state.blockedUsersCount > 0) "${state.blockedUsersCount} users" else "None",
                            iconColor = redColor,
                            position = ItemPosition.TOP,
                            onClick = component::onBlockedUsersClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Phone,
                            title = "Phone Number",
                            subtitle = state.phoneNumberPrivacy.toUiString(),
                            iconColor = orangeColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onPhoneNumberClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.AccessTime,
                            title = "Last Seen & Online",
                            subtitle = state.lastSeenPrivacy.toUiString(),
                            iconColor = blueColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onLastSeenClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Person,
                            title = "Profile Photos",
                            subtitle = state.profilePhotoPrivacy.toUiString(),
                            iconColor = purpleColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onProfilePhotoClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Info,
                            title = "Bio",
                            subtitle = state.bioPrivacy.toUiString(),
                            iconColor = tealColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onBioClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Forward,
                            title = "Forwarded Messages",
                            subtitle = state.forwardedMessagesPrivacy.toUiString(),
                            iconColor = greenColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onForwardedMessagesClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Call,
                            title = "Calls",
                            subtitle = state.callsPrivacy.toUiString(),
                            iconColor = indigoColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onCallsClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Group,
                            title = "Groups & Channels",
                            subtitle = state.groupsAndChannelsPrivacy.toUiString(),
                            iconColor = pinkColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onGroupsAndChannelsClicked
                        )
                    }

                    item {
                        SectionHeader("Security")
                        SettingsTile(
                            icon = Icons.Rounded.Lock,
                            title = "Passcode Lock",
                            subtitle = if (state.isPasscodeEnabled) "On" else "Off",
                            iconColor = blueColor,
                            position = ItemPosition.TOP,
                            onClick = component::onPasscodeClicked
                        )
                        if (state.isPasscodeEnabled) {
                            SettingsSwitchTile(
                                icon = Icons.Rounded.Fingerprint,
                                title = "Unlock with Biometrics",
                                subtitle = "Use fingerprint or face to unlock",
                                iconColor = greenColor,
                                checked = state.isBiometricEnabled,
                                position = ItemPosition.MIDDLE,
                                onCheckedChange = component::onBiometricChanged
                            )
                        }
                        SettingsTile(
                            icon = Icons.Rounded.Security,
                            title = "Two-Step Verification",
                            subtitle = if (state.isTwoStepVerificationEnabled) "On" else "Off",
                            iconColor = greenColor,
                            position = if (state.isPasscodeEnabled) ItemPosition.MIDDLE else ItemPosition.MIDDLE,
                            onClick = {
                                Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show()
                            }
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Devices,
                            title = "Active Sessions",
                            subtitle = "Manage your logged-in devices",
                            iconColor = orangeColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onActiveSessionsClicked
                        )
                    }

                    if (state.canShowSensitiveContent && !state.isInstalledFromGooglePlay) {
                        item {
                            SectionHeader("Sensitive Content")
                            SettingsSwitchTile(
                                icon = Icons.Rounded.Visibility,
                                title = "Disable filtering",
                                subtitle = "Display sensitive media in public channels on all your devices.",
                                iconColor = pinkColor,
                                checked = state.isSensitiveContentEnabled,
                                position = ItemPosition.STANDALONE,
                                onCheckedChange = component::onSensitiveContentChanged
                            )
                        }
                    }

                    item {
                        SectionHeader("Advanced")
                        SettingsTile(
                            icon = Icons.Rounded.DeleteForever,
                            title = "Delete My Account",
                            subtitle = "If away for ${state.accountTtlDays.toTtlString()}",
                            iconColor = purpleColor,
                            position = ItemPosition.TOP,
                            onClick = { showTtlSheet = true }
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Warning,
                            title = "Delete Account Now",
                            subtitle = "Permanently delete your account and all data",
                            iconColor = redColor,
                            position = ItemPosition.BOTTOM,
                            onClick = { showDeleteConfirmation = true }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showTtlSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTtlSheet = false },
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Self-Destruct if Inactive For...",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        val options = listOf(30, 90, 180, 365, 548, 720)
                        options.forEachIndexed { index, days ->
                            ListItem(
                                headlineContent = { Text(days.toTtlString()) },
                                trailingContent = {
                                    RadioButton(
                                        selected = state.accountTtlDays == days,
                                        onClick = null
                                    )
                                },
                                modifier = Modifier.clickable {
                                    component.onAccountTtlChanged(days)
                                    showTtlSheet = false
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                )
                            )
                            if (index < options.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Account") },
            text = { Text("Are you sure you want to delete your account? This action is permanent and cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        component.onDeleteAccountClicked("User requested deletion")
                        showDeleteConfirmation = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

private fun Int.toTtlString(): String {
    return when (this) {
        30 -> "1 month"
        90 -> "3 months"
        180 -> "6 months"
        365 -> "1 year"
        548 -> "18 months"
        720 -> "24 months"
        730 -> "2 years"
        else -> if (this % 30 == 0) "${this / 30} months" else "$this days"
    }
}

private fun PrivacyValue.toUiString(): String {
    return when (this) {
        PrivacyValue.EVERYBODY -> "Everybody"
        PrivacyValue.MY_CONTACTS -> "My Contacts"
        PrivacyValue.NOBODY -> "Nobody"
    }
}
