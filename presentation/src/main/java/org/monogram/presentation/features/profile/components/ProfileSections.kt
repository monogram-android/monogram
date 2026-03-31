package org.monogram.presentation.features.profile.components

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import org.monogram.domain.models.UserTypeEnum
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.*
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.koin.compose.koinInject
import org.monogram.presentation.core.util.CountryManager
import org.monogram.presentation.core.util.OperatorManager
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.profile.ProfileComponent
import java.util.*

@Composable
fun ProfileInfoSectionSkeleton(
    itemCount: Int = 4,
    showLinkedChat: Boolean = true
) {
    val shimmer = rememberShimmerBrush()

    Spacer(modifier = Modifier.height(12.dp))

    ProfileQuickActionsSkeleton(shimmer = shimmer)
    if (showLinkedChat) {
        LinkedChatItemSkeleton(shimmer = shimmer)
    }
    SectionHeaderSkeleton(shimmer = shimmer)

    repeat(itemCount) { index ->
        val position = when {
            itemCount == 1 -> ItemPosition.STANDALONE
            index == 0 -> ItemPosition.TOP
            index == itemCount - 1 -> ItemPosition.BOTTOM
            else -> ItemPosition.MIDDLE
        }
        val shape = when (position) {
            ItemPosition.TOP -> RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = 4.dp,
                bottomEnd = 4.dp
            )

            ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
            ItemPosition.BOTTOM -> RoundedCornerShape(
                bottomStart = 24.dp,
                bottomEnd = 24.dp,
                topStart = 4.dp,
                topEnd = 4.dp
            )

            ItemPosition.STANDALONE -> RoundedCornerShape(24.dp)
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = shape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(shimmer)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .height(18.dp)
                            .fillMaxWidth(
                                when (index % 3) {
                                    0 -> 0.54f
                                    1 -> 0.48f
                                    else -> 0.58f
                                }
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(shimmer)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .height(12.dp)
                            .fillMaxWidth(
                                when (index % 3) {
                                    0 -> 0.86f
                                    1 -> 0.78f
                                    else -> 0.9f
                                }
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(shimmer)
                    )
                }
            }
        }
        if (index < itemCount - 1) {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ProfileQuickActionsSkeleton(shimmer: androidx.compose.ui.graphics.Brush) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            repeat(3) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .widthIn(max = 100.dp)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(shimmer)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .height(11.dp)
                            .fillMaxWidth(0.8f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(shimmer)
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun LinkedChatItemSkeleton(shimmer: androidx.compose.ui.graphics.Brush) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .height(18.dp)
                        .fillMaxWidth(0.58f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmer)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmer)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(88.dp)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(shimmer)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SectionHeaderSkeleton(shimmer: androidx.compose.ui.graphics.Brush) {
    Box(
        modifier = Modifier
            .padding(start = 12.dp, top = 16.dp, bottom = 8.dp)
            .height(20.dp)
            .width(164.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(shimmer)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileInfoSection(
    state: ProfileComponent.State,
    videoPlayerPool: VideoPlayerPool,
    clipboardManager: ClipboardManager,
    onOpenMiniApp: (String, String, Long) -> Unit = { _, _, _ -> },
    onSendMessage: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onShowQRCode: () -> Unit = {},
    onEdit: () -> Unit = {},
    onLeave: () -> Unit = {},
    onJoin: () -> Unit = {},
    onShowLogs: () -> Unit = {},
    onShowStatistics: () -> Unit = {},
    onShowRevenueStatistics: () -> Unit = {},
    onLinkedChatClick: () -> Unit = {},
    onShowPermissions: () -> Unit = {},
    onAcceptTOS: () -> Unit = {},
    onToggleContact: () -> Unit = {},
    onLocationClick: (Double, Double, String) -> Unit = { _, _, _ -> }
) {
    val user = state.user
    val chat = state.chat
    val fullInfo = state.fullInfo
    val context = LocalContext.current
    val phoneUtil: PhoneNumberUtil = koinInject()
    var isSponsorSheetVisible by remember { mutableStateOf(false) }

    if (isSponsorSheetVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isSponsorSheetVisible = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color(0xFFFF6D66)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.support_monogram_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sponsor_sheet_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_VIEW, "https://boosty.to/monogram".toUri()),
                                null
                            )
                        )
                        isSponsorSheetVisible = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.action_support_boosty))
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = { isSponsorSheetVisible = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_maybe_later))
                }
            }
        }
    }

    val isGroupOrChannel = chat?.isGroup == true || chat?.isChannel == true
    val isCurrentUser = user != null && state.currentUser?.id == user.id
    val canEdit = when {
        isCurrentUser -> true
        isGroupOrChannel -> chat?.isAdmin == true || chat?.permissions?.canChangeInfo == true
        else -> false
    }

    if (!isCurrentUser) {
        ProfileQuickActions(
            state = state,
            isGroupOrChannel = isGroupOrChannel,
            isCurrentUser = isCurrentUser,
            onSendMessage = onSendMessage,
            onLeave = onLeave,
            onJoin = onJoin,
            onShowQRCode = onShowQRCode
        )
    }

    state.linkedChat?.let { linkedChat ->
        LinkedChatItem(
            chat = linkedChat,
            isDiscussion = chat?.isChannel == true,
            onClick = onLinkedChatClick,
            videoPlayerPool = videoPlayerPool
        )
    }

    val items = mutableListOf<@Composable (ItemPosition) -> Unit>()

    if (user?.isSponsor == true) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Favorite,
                title = stringResource(R.string.sponsor_profile_title),
                subtitle = stringResource(R.string.sponsor_profile_subtitle),
                iconColor = Color(0xFFFF6D66),
                position = pos,
                onClick = { isSponsorSheetVisible = true }
            )
        }
    }

    if (!isCurrentUser && !isGroupOrChannel && (state.personalAvatarPath != null || chat?.personalAvatarPath != null)) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Portrait,
                title = stringResource(R.string.personal_photo_title),
                subtitle = stringResource(R.string.personal_photo_subtitle),
                iconColor = MaterialTheme.colorScheme.primary,
                position = pos,
                onClick = { }
            )
        }
    }

    if (user?.type == UserTypeEnum.BOT && !state.botWebAppUrl.isNullOrEmpty()) {
        val botName = listOfNotNull(user.firstName, user.lastName).joinToString(" ").trim()
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.RocketLaunch,
                title = state.botWebAppName ?: stringResource(R.string.open_mini_app),
                subtitle = stringResource(R.string.open_mini_app_subtitle),
                iconColor = MaterialTheme.colorScheme.primary,
                position = pos,
                onClick = { onOpenMiniApp(state.botWebAppUrl, state.botWebAppName ?: botName, state.chatId) }
            )
        }
    }

    if (user?.type == UserTypeEnum.BOT) {
        if (!state.isTOSAccepted) {
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.AssignmentTurnedIn,
                    title = stringResource(R.string.accept_tos),
                    subtitle = stringResource(R.string.accept_tos_subtitle),
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = pos,
                    onClick = onAcceptTOS
                )
            }
        }

        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.bot_permissions),
                subtitle = stringResource(R.string.bot_permissions_subtitle),
                iconColor = MaterialTheme.colorScheme.secondary,
                position = pos,
                onClick = { onShowPermissions() }
            )
        }
    }

    val usernames = user?.usernames ?: chat?.usernames
    val activeUsernames = usernames?.activeUsernames ?: emptyList()
    val disabledUsernames = usernames?.disabledUsernames ?: emptyList()
    val collectibleUsernames = usernames?.collectibleUsernames ?: emptyList()

    if (activeUsernames.isNotEmpty() || collectibleUsernames.isNotEmpty() || disabledUsernames.isNotEmpty()) {
        items.add { pos ->
            UsernamesTile(
                activeUsernames = activeUsernames,
                collectibleUsernames = collectibleUsernames,
                disabledUsernames = disabledUsernames,
                clipboardManager = clipboardManager,
                isGroupOrChannel = isGroupOrChannel,
                position = pos
            )
        }
    } else {
        val displayLink = if (isGroupOrChannel) {
            state.publicLink ?: chat?.username
        } else {
            user?.username ?: chat?.username ?: state.publicLink
        }
        if (!displayLink.isNullOrEmpty()) {
            items.add { pos ->
                val isLink = displayLink.startsWith("http", ignoreCase = true) ||
                        displayLink.startsWith("t.me", ignoreCase = true)
                val isPrivateInviteLink = isGroupOrChannel && isLink && (
                        displayLink.contains("t.me/+", ignoreCase = true) ||
                                displayLink.contains("joinchat", ignoreCase = true)
                        )
                val finalTitle = when {
                    isGroupOrChannel && !isLink -> "https://t.me/$displayLink"
                    isLink -> displayLink
                    else -> "@$displayLink"
                }
                val icon = if (isGroupOrChannel || isLink) Icons.Rounded.Link else Icons.Rounded.AlternateEmail
                val subtitleText = when {
                    isPrivateInviteLink -> stringResource(R.string.invite_link_label)
                    isGroupOrChannel || isLink -> stringResource(R.string.link_label)
                    else -> stringResource(R.string.username_label)
                }

                SettingsTile(
                    icon = icon,
                    title = finalTitle,
                    subtitle = subtitleText,
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = pos,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(finalTitle))
                    }
                )
            }
        }
    }

    val aboutText = fullInfo?.botInfo ?: fullInfo?.description ?: state.about
    if (!aboutText.isNullOrEmpty()) {
        items.add { pos ->
            RichSettingsTile(
                icon = Icons.Rounded.Info,
                title = when {
                    user?.type == UserTypeEnum.BOT -> stringResource(R.string.bot_info_label)
                    isGroupOrChannel -> stringResource(R.string.description_label)
                    else -> stringResource(R.string.bio_label)
                },
                content = aboutText,
                iconColor = MaterialTheme.colorScheme.primary,
                position = pos,
                onCopyClick = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                }
            )
        }
    }

    user?.phoneNumber?.takeIf { it.isNotEmpty() }?.let { phone ->
        val formattedPhone = remember(phone) {
            runCatching {
                phoneUtil.format(phoneUtil.parse(phone, null), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            }.getOrDefault(phone)
        }
        val country = CountryManager.getCountryForPhone(phone)
        val operator = OperatorManager.detectOperator(phone, country?.iso)
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Phone,
                title = formattedPhone,
                subtitle = buildString {
                    country?.let { append("${it.flagEmoji} ${it.name}") }
                    operator?.let {
                        if (isNotEmpty()) append(" • ")
                        append(it)
                    }
                },
                iconColor = Color(0xFF34A853),
                position = pos,
                onClick = { clipboardManager.setText(AnnotatedString(phone)) }
            )
        }
    }

    fullInfo?.birthdate?.let { birthdate ->
        val birthdateText = buildString {
            append("${birthdate.day}/${birthdate.month}")
            birthdate.year?.let { year ->
                append("/$year")
                val today = Calendar.getInstance()
                var age = today.get(Calendar.YEAR) - year
                if (today.get(Calendar.MONTH) + 1 < birthdate.month ||
                    (today.get(Calendar.MONTH) + 1 == birthdate.month && today.get(Calendar.DAY_OF_MONTH) < birthdate.day)
                ) {
                    age--
                }
                append(" ($age)")
            }
        }

        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Cake,
                title = birthdateText,
                subtitle = stringResource(R.string.birthdate_label),
                iconColor = Color(0xFFE91E63),
                position = pos,
                onClick = { }
            )
        }
    }

    fullInfo?.businessInfo?.let { business ->
        business.location?.let { loc ->
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.LocationOn,
                    title = loc.address,
                    subtitle = stringResource(R.string.location_label),
                    iconColor = Color(0xFFEA4335),
                    position = pos,
                    onClick = { onLocationClick(loc.latitude, loc.longitude, loc.address) }
                )
            }
        }

        business.openingHours?.let { hours ->
            items.add { pos ->
                val days = listOf(
                    stringResource(R.string.monday_short),
                    stringResource(R.string.tuesday_short),
                    stringResource(R.string.wednesday_short),
                    stringResource(R.string.thursday_short),
                    stringResource(R.string.friday_short),
                    stringResource(R.string.saturday_short),
                    stringResource(R.string.sunday_short)
                )
                val intervalsByDay = hours.intervals.groupBy { it.startMinute / (24 * 60) }
                val formattedHours = days.mapIndexed { index, day ->
                    val dayIntervals = intervalsByDay[index]
                    if (dayIntervals.isNullOrEmpty()) {
                        "$day: ${stringResource(R.string.opening_hours_closed)}"
                    } else {
                        val times = dayIntervals.joinToString(", ") { interval ->
                            val startHour = (interval.startMinute % (24 * 60)) / 60
                            val startMinute = interval.startMinute % 60
                            val endHour = (interval.endMinute % (24 * 60)) / 60
                            val endMinute = interval.endMinute % 60
                            String.format("%02d:%02d - %02d:%02d", startHour, startMinute, endHour, endMinute)
                        }
                        "$day: $times"
                    }
                }.joinToString("\n")

                SettingsTile(
                    icon = Icons.Rounded.Schedule,
                    title = stringResource(R.string.opening_hours_label),
                    subtitle = formattedHours,
                    iconColor = Color(0xFFFBBC04),
                    position = pos,
                    onClick = { }
                )
            }
        }
    }

    if (chat?.isAdmin == true && isGroupOrChannel) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.recent_actions_title),
                subtitle = stringResource(R.string.recent_actions_subtitle),
                iconColor = MaterialTheme.colorScheme.secondary,
                position = pos,
                onClick = onShowLogs
            )
        }
    }

    val hasStatisticsAccess = isGroupOrChannel && fullInfo?.canGetStatistics == true
    if (hasStatisticsAccess) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.BarChart,
                title = stringResource(R.string.statistics_title),
                subtitle = stringResource(R.string.statistics_subtitle),
                iconColor = Color(0xFF00BCD4),
                position = pos,
                onClick = { onShowStatistics() }
            )
        }
    }

    if (!isGroupOrChannel && !isCurrentUser && user != null && user.type != UserTypeEnum.BOT) {
        val savedByYou = user.isContact
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.PersonAdd,
                title = if (savedByYou) stringResource(R.string.action_remove_contact) else stringResource(R.string.action_add_contact),
                subtitle = if (savedByYou) {
                    stringResource(R.string.contact_saved_by_you)
                } else {
                    stringResource(R.string.contact_not_saved_by_you)
                },
                iconColor = Color(0xFF5C6BC0),
                position = pos,
                onClick = onToggleContact
            )
        }
    }

    if (!isGroupOrChannel && isCurrentUser && fullInfo != null) {
        if (fullInfo.hasPostedToProfileStories) {
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.Collections,
                    title = stringResource(R.string.profile_feature_stories_title),
                    subtitle = stringResource(R.string.profile_feature_stories_subtitle),
                    iconColor = Color(0xFFAB47BC),
                    position = pos,
                    onClick = { }
                )
            }
        }

        if (fullInfo.setChatBackground) {
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.profile_feature_background_title),
                    subtitle = stringResource(R.string.profile_feature_background_subtitle),
                    iconColor = Color(0xFF26A69A),
                    position = pos,
                    onClick = { }
                )
            }
        }

        if (fullInfo.hasRestrictedVoiceAndVideoNoteMessages) {
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.MicOff,
                    title = stringResource(R.string.profile_feature_voice_restricted_title),
                    subtitle = stringResource(R.string.profile_feature_voice_restricted_subtitle),
                    iconColor = Color(0xFFFF7043),
                    position = pos,
                    onClick = { }
                )
            }
        }
    }

    if (isGroupOrChannel && (fullInfo?.slowModeDelay ?: 0) > 0) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Timer,
                title = stringResource(R.string.slow_mode_title),
                subtitle = stringResource(
                    R.string.slow_mode_subtitle_format,
                    formatInterval(fullInfo?.slowModeDelay ?: 0)
                ),
                iconColor = Color(0xFF009688),
                position = pos,
                onClick = { }
            )
        }
    }

    if (isGroupOrChannel && chat?.hasProtectedContent == true) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Shield,
                title = stringResource(R.string.protected_content_title),
                subtitle = stringResource(R.string.protected_content_subtitle),
                iconColor = Color(0xFF7E57C2),
                position = pos,
                onClick = { }
            )
        }
    }

    if (!isGroupOrChannel && fullInfo?.hasPrivateForwards == true) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.ForwardToInbox,
                title = stringResource(R.string.private_forwards_title),
                subtitle = stringResource(R.string.private_forwards_subtitle),
                iconColor = Color(0xFF5E35B1),
                position = pos,
                onClick = { }
            )
        }
    }

    val hasRevenueAccess = chat?.isChannel == true && fullInfo?.canGetRevenueStatistics == true

    if (hasRevenueAccess) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Payments,
                title = stringResource(R.string.revenue_title),
                subtitle = stringResource(R.string.revenue_subtitle),
                iconColor = Color(0xFFFF9800),
                position = pos,
                onClick = { onShowRevenueStatistics() }
            )
        }
    }

    val id = user?.id ?: chat?.id
    if (id != null) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Numbers,
                title = id.toString(),
                subtitle = stringResource(R.string.label_id),
                iconColor = MaterialTheme.colorScheme.outline,
                position = pos,
                onClick = { clipboardManager.setText(AnnotatedString(id.toString())) }
            )
        }
    }

    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            SectionHeader(
                text = stringResource(R.string.info_section_header),
                onEditClick = if (canEdit) onEdit else null
            )
            items.forEachIndexed { index, item ->
                val position = when {
                    items.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == items.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                item(position)
            }
        }
    }

    val settingsItems = mutableListOf<@Composable (ItemPosition) -> Unit>()

    if (!isCurrentUser) {
        settingsItems.add { pos ->
            SettingsSwitchTile(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.notifications_title),
                checked = chat?.isMuted == false,
                iconColor = Color(0xFFFF6D66),
                position = pos,
                onCheckedChange = { onToggleMute() }
            )
        }
    }

    if (chat != null && chat.messageAutoDeleteTime > 0) {
        settingsItems.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Timer,
                title = "${chat.messageAutoDeleteTime}s",
                subtitle = stringResource(R.string.auto_delete_subtitle),
                iconColor = Color(0xFF009688),
                position = pos,
                onClick = { /* */ }
            )
        }
    }

    AnimatedVisibility(
        visible = settingsItems.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            SectionHeader(
                text = stringResource(R.string.settings_section_header),
                onEditClick = null
            )
            settingsItems.forEachIndexed { index, item ->
                val position = when {
                    settingsItems.size == 1 -> ItemPosition.STANDALONE
                    index == 0 -> ItemPosition.TOP
                    index == settingsItems.size - 1 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                item(position)
            }
        }
    }
}

@Composable
private fun ProfileQuickActions(
    state: ProfileComponent.State,
    isGroupOrChannel: Boolean,
    isCurrentUser: Boolean,
    onSendMessage: () -> Unit,
    onLeave: () -> Unit,
    onJoin: () -> Unit,
    onShowQRCode: () -> Unit
) {
    val chat = state.chat

    val items = mutableListOf<@Composable (Modifier) -> Unit>()

    if (!isCurrentUser) {
        items.add { mod ->
            QuickActionItem(
                if (chat?.isChannel == true) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Filled.Chat,
                if (chat?.isChannel == true) stringResource(R.string.action_open) else stringResource(R.string.action_message),
                onClick = onSendMessage,
                modifier = mod
            )
        }
    }

    if (isGroupOrChannel) {
        if (chat?.isMember == true) {
            items.add { mod ->
                QuickActionItem(
                    Icons.AutoMirrored.Rounded.Logout, stringResource(R.string.menu_leave),
                    onClick = onLeave,
                    modifier = mod
                )
            }
        } else {
            items.add { mod ->
                QuickActionItem(
                    Icons.AutoMirrored.Rounded.Login,
                    stringResource(R.string.action_join_chat),
                    onClick = onJoin,
                    modifier = mod
                )
            }
        }
    }

    if (!isCurrentUser) {
        items.add { mod ->
            QuickActionItem(
                Icons.Default.QrCode,
                stringResource(R.string.action_qr_code),
                onClick = onShowQRCode,
                modifier = mod
            )
        }
    }

    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                items.forEach { item ->
                    item(Modifier
                        .weight(1f, fill = true)
                        .widthIn(max = 100.dp))
                }
            }
        }
    }
}

@Composable
fun QuickActionItem(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionHeader(
    text: String,
    onEditClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, bottom = 8.dp, top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        if (onEditClick != null) {
            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.menu_edit),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileQRDialog(
    state: ProfileComponent.State,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    if (state.isQrVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val qrContent = state.qrContent
                val username = state.user?.username ?: state.chat?.username ?: state.chat?.title ?: "user"
                val qrDarkGreen = Color(0xFF3E4D36)
                val qrSurfaceShapeColor = Color(0xFFE3E6D8)

                Text(
                    text = stringResource(R.string.action_qr_code),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .background(qrSurfaceShapeColor, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledQRCode(
                            content = qrContent,
                            modifier = Modifier.size(160.dp),
                            primaryColor = qrDarkGreen,
                            backgroundColor = qrSurfaceShapeColor
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (state.user?.username != null || state.chat?.username != null) "@$username" else username,
                            color = qrDarkGreen,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val bitmap = generatePureBitmap(qrContent, 1024)
                            saveBitmapToGallery(context, bitmap)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(stringResource(R.string.action_save), fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            val bitmap = generatePureBitmap(qrContent, 1024)
                            shareBitmap(context, bitmap)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(stringResource(R.string.menu_share), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileReportDialog(
    state: ProfileComponent.State,
    onDismiss: () -> Unit,
    onReport: (String) -> Unit
) {
    if (state.isReportVisible) {
        val reasons = listOf(
            stringResource(R.string.report_spam) to "spam",
            stringResource(R.string.report_violence) to "violence",
            stringResource(R.string.report_pornography) to "pornography",
            stringResource(R.string.report_child_abuse) to "child_abuse",
            stringResource(R.string.report_copyright) to "copyright",
            stringResource(R.string.report_other) to "other"
        )
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_report),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column {
                        reasons.forEachIndexed { index, (label, value) ->
                            ListItem(
                                headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                                modifier = Modifier.clickable { onReport(value) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            if (index < reasons.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.cancel_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePermissionsDialog(
    state: ProfileComponent.State,
    onDismiss: () -> Unit,
    onTogglePermission: (String) -> Unit = {}
) {
    if (state.isPermissionsVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp)
            ) {
                Text(
                    text = stringResource(R.string.bot_permissions),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column {
                        state.botPermissions.entries.forEachIndexed { index, entry ->
                            ListItem(
                                headlineContent = { Text(entry.key, style = MaterialTheme.typography.bodyLarge) },
                                trailingContent = {
                                    Switch(
                                        checked = entry.value,
                                        onCheckedChange = { onTogglePermission(entry.key) }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            if (index < state.botPermissions.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.close_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTOSDialog(
    state: ProfileComponent.State,
    onDismiss: () -> Unit,
    onAccept: () -> Unit
) {
    if (state.isTOSVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.terms_of_service_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.tos_dialog_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !state.isAcceptingTOS
                ) {
                    AnimatedContent(
                        targetState = state.isAcceptingTOS,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "AcceptButtonContent"
                    ) { accepting ->
                        if (accepting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                stringResource(R.string.accept_and_launch),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !state.isAcceptingTOS
                ) {
                    Text(stringResource(R.string.cancel_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsernamesTile(
    activeUsernames: List<String>,
    collectibleUsernames: List<String>,
    disabledUsernames: List<String>,
    clipboardManager: ClipboardManager,
    isGroupOrChannel: Boolean,
    position: ItemPosition
) {
    val allUsernames = (activeUsernames + collectibleUsernames + disabledUsernames).distinct()
    val primaryUsername =
        activeUsernames.firstOrNull() ?: collectibleUsernames.firstOrNull() ?: disabledUsernames.firstOrNull()

    if (primaryUsername == null) return

    val primaryColor = when {
        activeUsernames.contains(primaryUsername) -> MaterialTheme.colorScheme.primary
        collectibleUsernames.contains(primaryUsername) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { clipboardManager.setText(AnnotatedString("@$primaryUsername")) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = primaryColor.copy(0.15f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AlternateEmail,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = primaryColor
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@$primaryUsername",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isGroupOrChannel) stringResource(R.string.link_label) else stringResource(R.string.username_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                val otherUsernames = allUsernames.filter { it != primaryUsername }
                if (otherUsernames.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        otherUsernames.forEach { username ->
                            val chipColor = when {
                                activeUsernames.contains(username) -> MaterialTheme.colorScheme.primary
                                collectibleUsernames.contains(username) -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            }
                            UsernameChip(
                                username = username,
                                color = chipColor,
                                onClick = { clipboardManager.setText(AnnotatedString("@$username")) }
                            )
                        }
                    }
                }
            }
        }
    }
    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun UsernameChip(
    username: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = "@$username",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatInterval(value: Int): String {
    val seconds = value.coerceAtLeast(0)
    return when {
        seconds >= 86400 -> "${seconds / 86400}d"
        seconds >= 3600 -> "${seconds / 3600}h"
        seconds >= 60 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
}
