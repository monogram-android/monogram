package org.monogram.presentation.features.profile.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.presentation.core.ui.StyledQRCode
import org.monogram.presentation.core.ui.generatePureBitmap
import org.monogram.presentation.core.ui.saveBitmapToGallery
import org.monogram.presentation.core.ui.shareBitmap
import org.monogram.presentation.core.util.CountryManager
import org.monogram.presentation.core.util.OperatorManager
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.profile.ProfileComponent
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile
import java.util.*

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
    onReport: () -> Unit = {},
    onShowLogs: () -> Unit = {},
    onShowStatistics: () -> Unit = {},
    onShowRevenueStatistics: () -> Unit = {},
    onLinkedChatClick: () -> Unit = {},
    onShowPermissions: () -> Unit = {},
    onAcceptTOS: () -> Unit = {},
    onLocationClick: (Double, Double, String) -> Unit = { _, _, _ -> }
) {
    val user = state.user
    val chat = state.chat
    val fullInfo = state.fullInfo
    val context = LocalContext.current

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
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickActionItem(
                Icons.AutoMirrored.Filled.Chat, "Message",
                onClick = onSendMessage,
                modifier = Modifier.weight(1f),
            )

            val isMuted = chat?.isMuted == true
            QuickActionItem(
                if (isMuted) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                if (isMuted) "Unmute" else "Mute",
                onClick = onToggleMute,
                modifier = Modifier.weight(1f)
            )

            chat?.let {
                if (it.isGroup || it.isChannel) {
                    if (it.isMember) {
                        QuickActionItem(
                            Icons.AutoMirrored.Rounded.Logout, "Leave",
                            onClick = onLeave,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        QuickActionItem(
                            Icons.AutoMirrored.Rounded.Login, "Join",
                            onClick = onJoin,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    QuickActionItem(
                        Icons.Rounded.Report, "Report",
                        onClick = onReport,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    QuickActionItem(
                        Icons.Default.QrCode, "QR Code",
                        onClick = onShowQRCode,
                        modifier = Modifier.weight(1f)
                    )
                    val isContact = user?.isContact ?: false
                    QuickActionItem(
                        if (isContact) Icons.Default.Edit else Icons.Default.Add,
                        if (isContact) "Edit" else "Add",
                        onClick = onEdit,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
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
    val isGroupOrChannel = chat?.let { it.isGroup || it.isChannel } ?: false
    if (!isGroupOrChannel && (state.personalAvatarPath != null || chat?.personalAvatarPath != null)) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Portrait,
                title = "Personal Photo",
                subtitle = "This photo is only visible to you",
                iconColor = MaterialTheme.colorScheme.primary,
                position = pos,
                onClick = { }
            )
        }
    }

    if (user?.type == UserTypeEnum.BOT && !state.botWebAppUrl.isNullOrEmpty()) {
        val botName = listOfNotNull(user.firstName, user.lastName).joinToString(" ")
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.RocketLaunch,
                title = state.botWebAppName ?: "Open Mini App",
                subtitle = "Launch bot's web application",
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
                    title = "Accept TOS",
                    subtitle = "Review and accept bot's terms of service",
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = pos,
                    onClick = onAcceptTOS
                )
            }
        }

        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Security,
                title = "Bot Permissions",
                subtitle = "Manage permissions for this bot",
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
                position = pos
            )
        }
    } else {
        val displayLink = state.user?.username ?: state.publicLink
        if (!displayLink.isNullOrEmpty()) {
            items.add { pos ->
                val isLink = displayLink.startsWith("http", ignoreCase = true) ||
                        displayLink.startsWith("t.me", ignoreCase = true)
                val finalTitle = if (isLink) displayLink else "@$displayLink"
                val icon = if (isLink) Icons.Rounded.Link else Icons.Rounded.AlternateEmail
                val subtitleText =
                    if (isLink || chat?.isChannel == true || chat?.isGroup == true) "Link" else "Username"

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


    val aboutText = fullInfo?.botInfo ?: state.about
    if (!aboutText.isNullOrEmpty()) {
        items.add { pos ->
            RichSettingsTile(
                icon = Icons.Rounded.Info,
                title = when {
                    user?.type.toString().contains("BOT", true) -> "Bot Info"
                    chat?.isChannel == true || chat?.isGroup == true -> "Description"
                    else -> "Bio"
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
        val formattedPhone = CountryManager.formatPhone(context, phone)
        val country = CountryManager.getCountryForPhone(context, phone)
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
                subtitle = "Birthdate",
                iconColor = Color(0xFFE91E63),
                position = pos,
                onClick = { }
            )
        }
    }

    // Business Info
    fullInfo?.businessInfo?.let { business ->
        business.location?.let { loc ->
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.LocationOn,
                    title = loc.address,
                    subtitle = "Location",
                    iconColor = Color(0xFFEA4335),
                    position = pos,
                    onClick = { onLocationClick(loc.latitude, loc.longitude, loc.address) }
                )
            }
        }

        business.openingHours?.let { hours ->
            items.add { pos ->
                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                val intervalsByDay = hours.intervals.groupBy { it.startMinute / (24 * 60) }
                val formattedHours = days.mapIndexed { index, day ->
                    val dayIntervals = intervalsByDay[index]
                    if (dayIntervals.isNullOrEmpty()) {
                        "$day: Closed"
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
                    title = "Opening Hours",
                    subtitle = formattedHours,
                    iconColor = Color(0xFFFBBC04),
                    position = pos,
                    onClick = { }
                )
            }
        }
    }

    if (chat?.isAdmin == true && (chat.type == ChatType.SUPERGROUP || chat.type == ChatType.BASIC_GROUP)) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.History,
                title = "Recent Actions",
                subtitle = "View chat event log",
                iconColor = MaterialTheme.colorScheme.secondary,
                position = pos,
                onClick = onShowLogs
            )
        }
    }

    if (fullInfo?.canGetStatistics == true) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.BarChart,
                title = "Statistics",
                subtitle = "View detailed chat statistics",
                iconColor = Color(0xFF00BCD4),
                position = pos,
                onClick = { onShowStatistics() }
            )
        }
    }

    if (fullInfo?.canGetRevenueStatistics == true) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Payments,
                title = "Revenue",
                subtitle = "View chat revenue statistics",
                iconColor = Color(0xFFFF9800),
                position = pos,
                onClick = { onShowRevenueStatistics() }
            )
        }
    }

    if (fullInfo != null && (chat?.isGroup == true || chat?.isChannel == true)) {
        val stats = listOfNotNull(
            if (fullInfo.memberCount > 0) "${fullInfo.memberCount} members" else null,
            if (fullInfo.administratorCount > 0) "${fullInfo.administratorCount} admins" else null,
            if (fullInfo.restrictedCount > 0) "${fullInfo.restrictedCount} restricted" else null,
            if (fullInfo.bannedCount > 0) "${fullInfo.bannedCount} banned" else null
        ).joinToString(", ")

        if (stats.isNotEmpty()) {
            items.add { pos ->
                SettingsTile(
                    icon = Icons.Rounded.Groups,
                    title = stats,
                    subtitle = "Chat Stats",
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = pos,
                    onClick = { }
                )
            }
        }
    }

    val id = user?.id ?: chat?.id
    if (id != null) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Numbers,
                title = id.toString(),
                subtitle = "ID",
                iconColor = MaterialTheme.colorScheme.outline,
                position = pos,
                onClick = { clipboardManager.setText(AnnotatedString(id.toString())) }
            )
        }
    }

    // Render Info Items
    if (items.isNotEmpty()) {
        SectionHeader(text = "Info", onEditClick = if (chat?.isAdmin == true) onEdit else null)
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
                .aspectRatio(1.5f)
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
fun ProfileSettingsSection(
    state: ProfileComponent.State,
    onToggleMute: () -> Unit = {},
    onEdit: () -> Unit = {}
) {
    val chat = state.chat

    val items = mutableListOf<@Composable (ItemPosition) -> Unit>()

    items.add { pos ->
        SettingsSwitchTile(
            icon = Icons.Rounded.Notifications,
            title = "Notifications",
            checked = chat?.isMuted == true,
            iconColor = Color(0xFFFF6D66),
            position = pos,
            onCheckedChange = { onToggleMute() }
        )
    }

    if (chat != null && chat.messageAutoDeleteTime > 0) {
        items.add { pos ->
            SettingsTile(
                icon = Icons.Rounded.Timer,
                title = "${chat.messageAutoDeleteTime}s",
                subtitle = "Auto-delete messages",
                iconColor = Color(0xFF009688),
                position = pos,
                onClick = { /* */ }
            )
        }
    }

    if (items.isNotEmpty()) {
        SectionHeader(text = "Settings", onEditClick = if (chat?.isAdmin == true) onEdit else null)
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
                    contentDescription = "Edit",
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
                val username = state.user?.username ?: state.chat?.title ?: "user"
                val qrDarkGreen = Color(0xFF3E4D36)
                val qrSurfaceShapeColor = Color(0xFFE3E6D8)

                Text(
                    text = "QR Code",
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
                            text = if (state.user?.username != null) "@$username" else username,
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
                    ) { Text("Save", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

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
                    ) { Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
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
            "Spam" to "spam",
            "Violence" to "violence",
            "Pornography" to "pornography",
            "Child Abuse" to "child_abuse",
            "Copyright" to "copyright",
            "Other" to "other"
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
                    text = "Report",
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
                    Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    text = "Bot Permissions",
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
                    Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    text = "Terms of Service",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "By launching this Mini App, you agree to the Terms of Service and Privacy Policy. The bot will be able to access your basic profile information.",
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
                            Text("Accept and Launch", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !state.isAcceptingTOS
                ) {
                    Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    text = "Username",
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