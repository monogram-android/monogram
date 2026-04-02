@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.PrivacyValue
import org.monogram.presentation.R
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
    var verificationPasscode by remember { mutableStateOf("") }

    LaunchedEffect(state.isPasscodeVerificationVisible) {
        if (!state.isPasscodeVerificationVisible) {
            verificationPasscode = ""
        }
    }

    Scaffold(
        modifier = Modifier.semantics { contentDescription = "PrivacyContent" },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.privacy_security_header),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
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
                    ContainedLoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        SectionHeader(stringResource(R.string.privacy_section_header))
                        SettingsTile(
                            icon = Icons.Rounded.Block,
                            title = stringResource(R.string.blocked_users_title),
                            subtitle = if (state.blockedUsersCount > 0) stringResource(
                                R.string.blocked_users_count_format,
                                state.blockedUsersCount
                            ) else stringResource(R.string.none_label),
                            iconColor = redColor,
                            position = ItemPosition.TOP,
                            onClick = component::onBlockedUsersClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Phone,
                            title = stringResource(R.string.phone_number_title),
                            subtitle = state.phoneNumberPrivacy.toUiString(),
                            iconColor = orangeColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onPhoneNumberClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.AccessTime,
                            title = stringResource(R.string.last_seen_title),
                            subtitle = state.lastSeenPrivacy.toUiString(),
                            iconColor = blueColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onLastSeenClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Person,
                            title = stringResource(R.string.profile_photos_title),
                            subtitle = state.profilePhotoPrivacy.toUiString(),
                            iconColor = purpleColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onProfilePhotoClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Info,
                            title = stringResource(R.string.bio_label),
                            subtitle = state.bioPrivacy.toUiString(),
                            iconColor = tealColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onBioClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Forward,
                            title = stringResource(R.string.forwarded_messages_title),
                            subtitle = state.forwardedMessagesPrivacy.toUiString(),
                            iconColor = greenColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onForwardedMessagesClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Call,
                            title = stringResource(R.string.calls_title),
                            subtitle = state.callsPrivacy.toUiString(),
                            iconColor = indigoColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onCallsClicked
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Group,
                            title = stringResource(R.string.groups_channels_title),
                            subtitle = state.groupsAndChannelsPrivacy.toUiString(),
                            iconColor = pinkColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onGroupsAndChannelsClicked
                        )
                    }

                    item {
                        SectionHeader(stringResource(R.string.security_section_header))
                        SettingsTile(
                            icon = Icons.Rounded.Lock,
                            title = stringResource(R.string.passcode_lock_title),
                            subtitle = if (state.isPasscodeEnabled) stringResource(R.string.on_label) else stringResource(
                                R.string.off_label
                            ),
                            iconColor = blueColor,
                            position = ItemPosition.TOP,
                            onClick = component::onPasscodeClicked
                        )
                        if (state.isPasscodeEnabled) {
                            SettingsSwitchTile(
                                icon = Icons.Rounded.Fingerprint,
                                title = stringResource(R.string.biometric_unlock_title),
                                subtitle = stringResource(R.string.biometric_unlock_subtitle),
                                iconColor = greenColor,
                                checked = state.isBiometricEnabled,
                                position = ItemPosition.MIDDLE,
                                onCheckedChange = component::onBiometricChanged
                            )
                        }
                        SettingsTile(
                            icon = Icons.Rounded.Security,
                            title = stringResource(R.string.two_step_verification_title),
                            subtitle = if (state.isTwoStepVerificationEnabled) stringResource(R.string.on_label) else stringResource(
                                R.string.off_label
                            ),
                            iconColor = greenColor,
                            position = if (state.isPasscodeEnabled) ItemPosition.MIDDLE else ItemPosition.MIDDLE,
                            onClick = {
                                Toast.makeText(context, context.getString(R.string.not_implemented), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Devices,
                            title = stringResource(R.string.active_sessions_title),
                            subtitle = stringResource(R.string.active_sessions_subtitle),
                            iconColor = orangeColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onActiveSessionsClicked
                        )
                    }

                    if (state.canShowSensitiveContent && !state.isInstalledFromGooglePlay) {
                        item {
                            SectionHeader(stringResource(R.string.sensitive_content_header))
                            SettingsSwitchTile(
                                icon = Icons.Rounded.Visibility,
                                title = stringResource(R.string.disable_filtering_title),
                                subtitle = stringResource(R.string.disable_filtering_subtitle),
                                iconColor = pinkColor,
                                checked = state.isSensitiveContentEnabled,
                                position = ItemPosition.STANDALONE,
                                onCheckedChange = component::onSensitiveContentChanged
                            )
                        }
                    }

                    item {
                        SectionHeader(stringResource(R.string.advanced_section_header))
                        SettingsTile(
                            icon = Icons.Rounded.DeleteForever,
                            title = stringResource(R.string.delete_my_account_title),
                            subtitle = stringResource(
                                R.string.delete_my_account_subtitle,
                                state.accountTtlDays.toTtlString()
                            ),
                            iconColor = purpleColor,
                            position = ItemPosition.TOP,
                            onClick = { showTtlSheet = true }
                        )
                        SettingsTile(
                            icon = Icons.Rounded.Warning,
                            title = stringResource(R.string.delete_account_now_title),
                            subtitle = stringResource(R.string.delete_account_now_subtitle),
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
                    text = stringResource(R.string.self_destruct_title),
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
            title = { Text(stringResource(R.string.delete_account_confirmation_title)) },
            text = { Text(stringResource(R.string.delete_account_confirmation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        component.onDeleteAccountClicked("User requested deletion")
                        showDeleteConfirmation = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (state.isPasscodeVerificationVisible) {
        AlertDialog(
            onDismissRequest = component::onPasscodeVerificationDismissed,
            title = { Text(stringResource(R.string.passcode_verify_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.passcode_verify_description))
                    OutlinedTextField(
                        value = verificationPasscode,
                        onValueChange = {
                            if (it.length <= 4 && it.all(Char::isDigit)) {
                                verificationPasscode = it
                            }
                        },
                        label = { Text(stringResource(R.string.passcode_current_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        isError = state.isPasscodeVerificationInvalid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.isPasscodeVerificationInvalid) {
                        Text(
                            text = stringResource(R.string.passcode_verify_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { component.onPasscodeVerificationSubmitted(verificationPasscode) },
                    enabled = verificationPasscode.length == 4
                ) {
                    Text(stringResource(R.string.confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = component::onPasscodeVerificationDismissed) {
                    Text(stringResource(R.string.cancel_button))
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

@Composable
private fun Int.toTtlString(): String {
    return when (this) {
        30 -> stringResource(R.string.ttl_1_month)
        90 -> stringResource(R.string.ttl_3_months)
        180 -> stringResource(R.string.ttl_6_months)
        365 -> stringResource(R.string.ttl_1_year)
        548 -> stringResource(R.string.ttl_18_months)
        720 -> stringResource(R.string.ttl_2_years)
        730 -> stringResource(R.string.ttl_2_years)
        else -> if (this % 30 == 0) {
            stringResource(R.string.ttl_months_format, this / 30)
        } else {
            stringResource(R.string.ttl_days_format, this)
        }
    }
}

@Composable
private fun PrivacyValue.toUiString(): String {
    return when (this) {
        PrivacyValue.EVERYBODY -> stringResource(R.string.privacy_everybody)
        PrivacyValue.MY_CONTACTS -> stringResource(R.string.privacy_my_contacts)
        PrivacyValue.NOBODY -> stringResource(R.string.privacy_nobody)
    }
}
