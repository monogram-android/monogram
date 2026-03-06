package org.monogram.presentation.settingsScreens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.settingsScreens.sessions.SectionHeader
import org.monogram.presentation.settingsScreens.settings.components.ItemPosition
import org.monogram.presentation.settingsScreens.settings.components.SettingsItem
import org.monogram.presentation.settingsScreens.settings.components.UserProfileHeader
import org.monogram.presentation.settingsScreens.settings.components.UsernamesTile
import org.monogram.presentation.stickers.ui.view.StickerImage
import org.monogram.presentation.util.*

val QrBackgroundColor = Color(0xFFEFF1E6)
val QrDarkGreen = Color(0xFF3E4D36)
val QrSurfaceShapeColor = Color(0xFFE3E6D8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(component: SettingsComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val blueColor = Color(0xFF4285F4)
    val greenColor = Color(0xFF34A853)
    val orangeColor = Color(0xFFF9AB00)
    val pinkColor = Color(0xFFFF6D66)
    val tealColor = Color(0xFF00BFA5)
    val purpleColor = Color(0xFFAF52DE)
    val indigoColor = Color(0xFF536DFE)
    val phoneColor = Color(0xFF007AFF)
    val usernameColor = Color(0xFF34C759)
    val idColor = Color(0xFFAF52DE)

    val collapsingToolbarState = rememberCollapsingToolbarScaffoldState()
    var isPhoneVisible by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val collapsedColor = MaterialTheme.colorScheme.surface
    val expandedColor = MaterialTheme.colorScheme.background
    val dynamicContainerColor = lerp(
        start = collapsedColor,
        stop = expandedColor,
        fraction = collapsingToolbarState.toolbarState.progress
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                component.checkLinkStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (state.isQrVisible) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = component::onQrCodeDismissed,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            val username = state.currentUser?.username ?: "user"
            val qrContent = state.qrContent.ifEmpty { "https://t.me/$username" }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "QR Code",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .background(QrSurfaceShapeColor, RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledQRCode(
                            content = qrContent,
                            modifier = Modifier.size(200.dp),
                            primaryColor = QrDarkGreen,
                            backgroundColor = QrSurfaceShapeColor
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "@$username",
                            color = QrDarkGreen,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
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
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QrDarkGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Save") }

                    Button(
                        onClick = {
                            val bitmap = generatePureBitmap(qrContent, 1024)
                            shareBitmap(context, bitmap)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QrDarkGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Share") }
                }
            }
        }
    }

    val iconTint = lerp(
        start = MaterialTheme.colorScheme.onSurface,
        stop = MaterialTheme.colorScheme.onSurface,
        fraction = collapsingToolbarState.toolbarState.progress
    )

    val buttonBackground = MaterialTheme.colorScheme.background.copy(
        alpha = 0.75f * collapsingToolbarState.toolbarState.progress
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    state.currentUser?.let { userModel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(1f - collapsingToolbarState.toolbarState.progress),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${userModel.firstName} ${userModel.lastName ?: ""}".trim(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (userModel.isVerified) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = "Verified",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (!userModel.statusEmojiPath.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                StickerImage(
                                    path = userModel.statusEmojiPath,
                                    modifier = Modifier.size(22.dp),
                                    animate = false
                                )
                            } else if (userModel.isPremium) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = Color(0xFF31A6FD)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    Card(
                        modifier = Modifier.padding(8.dp),
                        shape = RoundedCornerShape(50),
                        colors = CardDefaults.cardColors(containerColor = buttonBackground),
                    ) {
                        IconButton(onClick = component::onBackClicked) {
                            Icon(
                                Icons.Rounded.ArrowBack,
                                contentDescription = null,
                                tint = iconTint
                            )
                        }
                    }
                },
                actions = {
                    Card(
                        modifier = Modifier.padding(8.dp),
                        shape = RoundedCornerShape(50),
                        colors = CardDefaults.cardColors(containerColor = buttonBackground),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            IconButton(onClick = component::onQrCodeClicked) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = null,
                                    tint = iconTint
                                )
                            }
                            IconButton(onClick = component::onEditProfileClicked) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "Edit Profile",
                                    tint = iconTint
                                )
                            }
                            IconButton(onClick = { }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = null,
                                    tint = iconTint
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        CollapsingToolbarScaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(dynamicContainerColor),
            state = collapsingToolbarState,
            scrollStrategy = ScrollStrategy.ExitUntilCollapsed,
            toolbar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(padding.calculateTopPadding())
                        .pin()
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .road(Alignment.Center, Alignment.BottomCenter)
                ) {
                    val progress = collapsingToolbarState.toolbarState.progress
                    val avatarSize = lerp(140.dp, maxWidth, progress)
                    val cornerPercent = (100 * (1f - progress)).toInt()
                    val sidePadding = lerp(24.dp, 0.dp, progress)
                    val topPadding = 0.dp

                    val configuration = LocalConfiguration.current
                    val screenHeight = configuration.screenHeightDp.dp
                    val headerHeight = maxWidth.coerceAtMost(screenHeight * 0.6f)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerHeight)
                    ) {
                        state.currentUser?.let { user ->
                            UserProfileHeader(
                                userModel = user,
                                avatarSize = avatarSize,
                                headerHeight = headerHeight,
                                avatarCornerPercent = cornerPercent,
                                currentRadius = 30.dp * progress,
                                alpha = progress,
                                contentPadding = PaddingValues(
                                    top = topPadding,
                                    start = sidePadding,
                                    end = sidePadding
                                ),
                                videoPlayerPool = component.videoPlayerPool
                            )
                        }
                    }
                }
            },
            body = {
                val navBarInsets = WindowInsets.navigationBars.asPaddingValues()
                val bottomPadding = navBarInsets.calculateBottomPadding()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 0.dp,

                        bottom = bottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        state.currentUser?.let { user ->
                            val rawPhone = user.phoneNumber ?: ""

                            SettingsItem(
                                icon = Icons.Default.PhoneIphone,
                                title = if (isPhoneVisible) CountryManager.formatPhone(
                                    context,
                                    rawPhone
                                ) else formatMaskedGlobal(
                                    rawPhone
                                ),
                                subtitle = if (isPhoneVisible) "Long press to mask" else "Hold to show, click to copy",
                                iconBackgroundColor = phoneColor,
                                position = ItemPosition.TOP,
                                onLongClick = {
                                    isPhoneVisible = !isPhoneVisible
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    clipboardManager.setText(
                                        AnnotatedString(
                                            CountryManager.formatPhone(
                                                context,
                                                rawPhone
                                            )
                                        )
                                    )
                                }
                            )

                            val usernames = user.usernames
                            if (usernames != null && (usernames.activeUsernames.isNotEmpty() || usernames.collectibleUsernames.isNotEmpty() || usernames.disabledUsernames.isNotEmpty())) {
                                UsernamesTile(
                                    activeUsernames = usernames.activeUsernames,
                                    collectibleUsernames = usernames.collectibleUsernames,
                                    disabledUsernames = usernames.disabledUsernames,
                                    clipboardManager = clipboardManager,
                                    position = ItemPosition.MIDDLE
                                )
                            } else {
                                SettingsItem(
                                    icon = Icons.Rounded.Person,
                                    title = "@${user.username}",
                                    subtitle = "Username",
                                    iconBackgroundColor = usernameColor,
                                    position = ItemPosition.MIDDLE,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        clipboardManager.setText(AnnotatedString("@${user.username}"))
                                    }
                                )
                            }

                            SettingsItem(
                                icon = Icons.Rounded.Info,
                                title = user.id.toString(),
                                subtitle = "Your ID",
                                iconBackgroundColor = idColor,
                                position = ItemPosition.MIDDLE,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    clipboardManager.setText(AnnotatedString(user.id.toString()))
                                }
                            )

                            SettingsItem(
                                icon = Icons.Rounded.Edit,
                                title = "Edit Profile",
                                subtitle = "Change your name, bio, and profile photo",
                                iconBackgroundColor = blueColor,
                                position = ItemPosition.BOTTOM,
                                onClick = component::onEditProfileClicked
                            )
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !state.isTMeLinkEnabled) {
                        item {
                            SettingsItem(
                                icon = Icons.Rounded.Link,
                                title = "Enable t.me links",
                                subtitle = "Open Telegram links to open them in-app",
                                iconBackgroundColor = blueColor,
                                position = ItemPosition.STANDALONE,
                                onClick = component::onLinkSettingsClicked
                            )
                        }
                    }

                    item {
                        SectionHeader("General")
                        SettingsItem(
                            icon = Icons.AutoMirrored.Rounded.Chat,
                            title = "Chat Settings",
                            subtitle = "Themes, text size, video player",
                            iconBackgroundColor = blueColor,
                            position = ItemPosition.TOP,
                            onClick = component::onChatSettingsClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Lock,
                            title = "Privacy and Security",
                            subtitle = "Passcode, active sessions, privacy",
                            iconBackgroundColor = greenColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onPrivacyClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Notifications,
                            title = "Notifications and Sounds",
                            subtitle = "Messages, groups, calls",
                            iconBackgroundColor = pinkColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onNotificationsClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.DataUsage,
                            title = "Data and Storage",
                            subtitle = "Network usage, auto-download",
                            iconBackgroundColor = tealColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onDataStorageClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.PowerSettingsNew,
                            title = "Power Saving",
                            subtitle = "Battery usage settings",
                            iconBackgroundColor = orangeColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onPowerSavingClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Folder,
                            title = "Chat Folders",
                            subtitle = "Organize your chats",
                            iconBackgroundColor = indigoColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onFoldersClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.SentimentSatisfiedAlt,
                            title = "Stickers and Emoji",
                            subtitle = "Manage sticker sets and emoji packs",
                            iconBackgroundColor = orangeColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onStickersClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Person,
                            title = "Devices",
                            subtitle = "Linked devices",
                            iconBackgroundColor = tealColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onDevicesClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Language,
                            title = "Language",
                            subtitle = "English",
                            iconBackgroundColor = blueColor,
                            position = ItemPosition.MIDDLE,
                            onClick = {
                                val intent =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                                            data =
                                                Uri.fromParts("package", context.packageName, null)
                                        }
                                    } else {
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data =
                                                Uri.fromParts("package", context.packageName, null)
                                        }
                                    }
                                context.startActivity(intent)
                            }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.SettingsEthernet,
                            title = "Proxy Settings",
                            subtitle = "MTProto, SOCKS5, HTTP",
                            iconBackgroundColor = purpleColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onProxySettingsClicked
                        )
                    }

                    item {
                        SettingsItem(
                            icon = Icons.Rounded.Star,
                            title = "Telegram Premium",
                            subtitle = "Unlock exclusive features",
                            iconBackgroundColor = purpleColor,
                            position = ItemPosition.STANDALONE,
                            onClick = component::onPremiumClicked
                        )
                    }

                    item {
                        SettingsItem(
                            icon = Icons.Rounded.Info,
                            title = "About",
                            subtitle = "MonoGram version and info",
                            iconBackgroundColor = blueColor,
                            position = ItemPosition.TOP,
                            onClick = component::onAboutClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.BugReport,
                            title = "Debug",
                            subtitle = "Debug options",
                            iconBackgroundColor = Color.Gray,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onDebugClicked
                        )
                        SettingsItem(
                            icon = Icons.AutoMirrored.Rounded.ExitToApp,
                            title = "Log Out",
                            subtitle = "Disconnect from account",
                            iconBackgroundColor = MaterialTheme.colorScheme.error,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onLogoutClicked
                        )
                    }
                }
            }
        )
    }


}