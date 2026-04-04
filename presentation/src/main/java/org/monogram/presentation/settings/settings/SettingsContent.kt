package org.monogram.presentation.settings.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SentimentSatisfiedAlt
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.CollapsingToolbarScaffold
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.ui.StyledQRCode
import org.monogram.presentation.core.ui.UserProfileHeader
import org.monogram.presentation.core.ui.UsernamesTile
import org.monogram.presentation.core.ui.generatePureBitmap
import org.monogram.presentation.core.ui.rememberCollapsingToolbarScaffoldState
import org.monogram.presentation.core.ui.saveBitmapToGallery
import org.monogram.presentation.core.ui.shareBitmap
import org.monogram.presentation.core.util.CountryManager
import org.monogram.presentation.core.util.ScrollStrategy
import org.monogram.presentation.features.stickers.ui.menu.EmojisGrid
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.core.ui.SectionHeader
import java.util.Locale
import kotlin.math.roundToInt

val QrBackgroundColor = Color(0xFFEFF1E6)
val QrDarkGreen = Color(0xFF3E4D36)
val QrSurfaceShapeColor = Color(0xFFE3E6D8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(component: SettingsComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
    var showStatusMenu by remember { mutableStateOf(false) }
    var statusAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var topBarStatusAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var headerStatusAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val statusMenuTransitionState = remember { MutableTransitionState(false) }
    val density = LocalDensity.current

    LaunchedEffect(showStatusMenu) {
        statusMenuTransitionState.targetState = showStatusMenu
    }

    var cachedStatusEmojiPath by remember(state.currentUser?.id) {
        mutableStateOf(state.currentUser?.statusEmojiPath)
    }

    LaunchedEffect(state.currentUser?.id, state.currentUser?.statusEmojiPath) {
        val statusEmojiPath = state.currentUser?.statusEmojiPath
        if (!statusEmojiPath.isNullOrBlank()) {
            cachedStatusEmojiPath = statusEmojiPath
        }
    }

    val currentUser = remember(state.currentUser, cachedStatusEmojiPath) {
        state.currentUser?.let { user ->
            if (user.statusEmojiId != 0L && user.statusEmojiPath.isNullOrBlank() && !cachedStatusEmojiPath.isNullOrBlank()) {
                user.copy(statusEmojiPath = cachedStatusEmojiPath)
            } else {
                user
            }
        }
    }

    val localClipboard = LocalClipboard.current
    val nativeClipboard = localClipboard.nativeClipboard
    val collapsedColor = MaterialTheme.colorScheme.surface
    val expandedColor = MaterialTheme.colorScheme.background
    val dynamicContainerColor = lerp(
        start = collapsedColor,
        stop = expandedColor,
        fraction = collapsingToolbarState.toolbarState.progress
    )
    val dynamicContainerColorTopBar = lerp(
        start = collapsedColor,
        stop = Color.Transparent,
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

    BackHandler(enabled = showStatusMenu) {
        showStatusMenu = false
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
                    text = stringResource(R.string.qr_code_title),
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
                    ) { Text(stringResource(R.string.action_save)) }

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
                    ) { Text(stringResource(R.string.action_share)) }
                }
            }
        }
    }

    if (state.isSupportVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = component::onSupportDismissed,
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
                    tint = pinkColor
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
                    onClick = component::onSupportClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.action_support_boosty))
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = component::onSupportDismissed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_maybe_later))
                }
            }
        }
    }

    if (state.isMoreOptionsVisible && state.currentUser?.username != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = component::onMoreOptionsDismissed,
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
                SettingsItem(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.share_profile_title),
                    subtitle = stringResource(R.string.share_profile_subtitle),
                    iconBackgroundColor = blueColor,
                    position = ItemPosition.TOP,
                    onClick = {
                        state.currentUser?.username?.let { username ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://t.me/$username")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, null))
                        }
                        component.onMoreOptionsDismissed()
                    }
                )
                SettingsItem(
                    icon = Icons.Rounded.Link,
                    title = stringResource(R.string.copy_link_title),
                    subtitle = stringResource(R.string.copy_link_subtitle),
                    iconBackgroundColor = greenColor,
                    position = ItemPosition.BOTTOM,
                    onClick = {
                        state.currentUser?.username?.let { username ->
                            nativeClipboard.setPrimaryClip(
                                ClipData.newPlainText("", AnnotatedString("https://t.me/$username"))
                            )

                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        component.onMoreOptionsDismissed()
                    }
                )
            }
        }
    }

    val statusMenuScrimAlpha by animateFloatAsState(
        targetValue = if (statusMenuTransitionState.targetState) 0.18f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "StatusMenuScrimAlpha"
    )

    val iconTint = lerp(
        start = MaterialTheme.colorScheme.onSurface,
        stop = MaterialTheme.colorScheme.onSurface,
        fraction = collapsingToolbarState.toolbarState.progress
    )

    val buttonBackground = MaterialTheme.colorScheme.background.copy(
        alpha = 0.75f * collapsingToolbarState.toolbarState.progress
    )

    Scaffold(
        modifier = Modifier.semantics { contentDescription = "SettingsContent" },
        topBar = {
            TopAppBar(
                title = {
                    currentUser?.let { userModel ->
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
                                    contentDescription = stringResource(R.string.cd_verified),
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (userModel.isSponsor) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Favorite,
                                    contentDescription = stringResource(R.string.cd_sponsor),
                                    modifier = Modifier.size(22.dp),
                                    tint = Color(0xFFE53935)
                                )
                            }

                            if (!userModel.statusEmojiPath.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                StickerImage(
                                    path = userModel.statusEmojiPath,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .onGloballyPositioned {
                                            topBarStatusAnchorBounds = it.boundsInRoot()
                                        }
                                        .clickable(onClick = {
                                            statusAnchorBounds =
                                                topBarStatusAnchorBounds ?: statusAnchorBounds
                                            showStatusMenu = true
                                        }),
                                    animate = false
                                )
                            } else if (userModel.isPremium) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .onGloballyPositioned {
                                            topBarStatusAnchorBounds = it.boundsInRoot()
                                        }
                                        .clickable(onClick = {
                                            statusAnchorBounds =
                                                topBarStatusAnchorBounds ?: statusAnchorBounds
                                            showStatusMenu = true
                                        }),
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
                                contentDescription = stringResource(R.string.cd_back),
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
                                    contentDescription = stringResource(R.string.action_qr_code),
                                    tint = iconTint
                                )
                            }
                            IconButton(onClick = component::onEditProfileClicked) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.cd_edit_profile),
                                    tint = iconTint
                                )
                            }
                            if (!state.currentUser?.username.isNullOrEmpty()) {
                                IconButton(onClick = component::onMoreOptionsClicked) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = null,
                                        tint = iconTint
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = dynamicContainerColorTopBar,
                    scrolledContainerColor = Color.Transparent,

                    )
            )
        }
    ) { padding ->
        var safeTopPadding by remember { mutableStateOf(0.dp) }
        var safeBottomPadding by remember { mutableStateOf(0.dp) }
        val language = remember {
            Locale.getDefault().displayLanguage
                .replaceFirstChar { it.uppercase() }
        }
        val currentTop = padding.calculateTopPadding()
        val currentBottom = padding.calculateBottomPadding()

        if (currentTop > 0.dp) safeTopPadding = currentTop
        if (currentBottom > 0.dp) safeBottomPadding = currentBottom

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
                                onStatusClick = {
                                    statusAnchorBounds =
                                        headerStatusAnchorBounds ?: statusAnchorBounds
                                    showStatusMenu = true
                                },
                                onStatusBoundsChanged = { headerStatusAnchorBounds = it }
                            )
                        }
                    }
                }
            },
            body = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "SettingsList" },
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 0.dp,
                        bottom = safeBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        state.currentUser?.let { user ->
                            val rawPhone = user.phoneNumber ?: ""                          
                            val formattedPhone = remember(rawPhone) {
                                CountryManager.formatPhoneNumber(rawPhone)
                            }
                            val maskedPhone = remember(rawPhone) {
                                CountryManager.maskPhoneNumber(formattedPhone)
                            }
                            
                            val phoneClipLabel = stringResource(R.string.phone_number_label)
                            val yourIdSubTitle = stringResource(R.string.your_id_label)
                            

                            SettingsItem(
                                icon = Icons.Default.PhoneIphone,
                                title = if (isPhoneVisible) formattedPhone else maskedPhone,
                                subtitle = if (isPhoneVisible) stringResource(R.string.phone_subtitle_visible) else stringResource(
                                    R.string.phone_subtitle_hidden
                                ),
                                iconBackgroundColor = phoneColor,
                                position = ItemPosition.TOP,
                                onLongClick = {
                                    isPhoneVisible = !isPhoneVisible
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    nativeClipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            phoneClipLabel,
                                            AnnotatedString(formattedPhone)
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
                                    localClipboard = localClipboard,
                                    position = ItemPosition.MIDDLE
                                )
                            } else if (!user.username.isNullOrEmpty()) {
                                val usernameSubTitle = stringResource(R.string.username_label)

                                SettingsItem(
                                    icon = Icons.Rounded.Person,
                                    title = "@${user.username}",
                                    subtitle = usernameSubTitle,
                                    iconBackgroundColor = usernameColor,
                                    position = ItemPosition.MIDDLE,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        ClipData.newPlainText(usernameSubTitle, AnnotatedString("@${user.username}"))
                                    }
                                )
                            }

                            SettingsItem(
                                icon = Icons.Rounded.Info,
                                title = user.id.toString(),
                                subtitle = yourIdSubTitle,
                                iconBackgroundColor = idColor,
                                position = ItemPosition.MIDDLE,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    ClipData.newPlainText(yourIdSubTitle, AnnotatedString(user.id.toString()))
                                }
                            )

                            SettingsItem(
                                icon = Icons.Rounded.Edit,
                                title = stringResource(R.string.menu_edit),
                                subtitle = stringResource(R.string.edit_profile_subtitle),
                                iconBackgroundColor = blueColor,
                                position = ItemPosition.BOTTOM,
                                onClick = component::onEditProfileClicked,
                                modifier = Modifier.semantics {
                                    contentDescription = "SettingsEditProfile"
                                }
                            )
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !state.isTMeLinkEnabled) {
                        item {
                            SettingsItem(
                                icon = Icons.Rounded.Link,
                                title = stringResource(R.string.enable_tme_links),
                                subtitle = stringResource(R.string.enable_tme_links_subtitle),
                                iconBackgroundColor = blueColor,
                                position = ItemPosition.STANDALONE,
                                onClick = component::onLinkSettingsClicked
                            )
                        }
                    }

                    item {
                        SectionHeader(stringResource(R.string.section_general))
                        SettingsItem(
                            icon = Icons.AutoMirrored.Rounded.Chat,
                            title = stringResource(R.string.chat_settings_title),
                            subtitle = stringResource(R.string.chat_settings_subtitle),
                            iconBackgroundColor = blueColor,
                            position = ItemPosition.TOP,
                            onClick = component::onChatSettingsClicked,
                            modifier = Modifier.semantics {
                                contentDescription = "SettingsChatSettings"
                            }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Lock,
                            title = stringResource(R.string.privacy_security_title),
                            subtitle = stringResource(R.string.privacy_security_subtitle),
                            iconBackgroundColor = greenColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onPrivacyClicked,
                            modifier = Modifier.semantics { contentDescription = "SettingsPrivacy" }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Notifications,
                            title = stringResource(R.string.notifications_sounds_title),
                            subtitle = stringResource(R.string.notifications_sounds_subtitle),
                            iconBackgroundColor = pinkColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onNotificationsClicked,
                            modifier = Modifier.semantics {
                                contentDescription = "SettingsNotifications"
                            }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.DataUsage,
                            title = stringResource(R.string.data_storage_title),
                            subtitle = stringResource(R.string.data_storage_subtitle),
                            iconBackgroundColor = tealColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onDataStorageClicked,
                            modifier = Modifier.semantics {
                                contentDescription = "SettingsDataStorage"
                            }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.PowerSettingsNew,
                            title = stringResource(R.string.power_saving_title),
                            subtitle = stringResource(R.string.power_saving_subtitle),
                            iconBackgroundColor = orangeColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onPowerSavingClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Folder,
                            title = stringResource(R.string.chat_folders_title),
                            subtitle = stringResource(R.string.chat_folders_subtitle),
                            iconBackgroundColor = indigoColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onFoldersClicked,
                            modifier = Modifier.semantics { contentDescription = "SettingsFolders" }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.SentimentSatisfiedAlt,
                            title = stringResource(R.string.stickers_emoji_title),
                            subtitle = stringResource(R.string.stickers_emoji_subtitle),
                            iconBackgroundColor = orangeColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onStickersClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Person,
                            title = stringResource(R.string.devices_title),
                            subtitle = stringResource(R.string.devices_subtitle),
                            iconBackgroundColor = tealColor,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onDevicesClicked
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            SettingsItem(
                                icon = Icons.Rounded.Language,
                                title = stringResource(R.string.language_title),
                                subtitle = language,
                                iconBackgroundColor = blueColor,
                                position = ItemPosition.MIDDLE,
                                onClick = {
                                    val intent =
                                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                                            data =
                                                Uri.fromParts("package", context.packageName, null)
                                        }
                                    context.startActivity(intent)
                                }
                            )
                        }
                        SettingsItem(
                            icon = Icons.Rounded.SettingsEthernet,
                            title = stringResource(R.string.proxy_settings_title),
                            subtitle = stringResource(R.string.proxy_settings_subtitle),
                            iconBackgroundColor = purpleColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onProxySettingsClicked
                        )
                    }

                    item {
                        SettingsItem(
                            icon = Icons.Rounded.Star,
                            title = stringResource(R.string.telegram_premium_title),
                            subtitle = stringResource(R.string.telegram_premium_subtitle),
                            iconBackgroundColor = purpleColor,
                            position = ItemPosition.TOP,
                            onClick = component::onPremiumClicked,
                            modifier = Modifier.semantics { contentDescription = "SettingsPremium" }
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Favorite,
                            title = stringResource(R.string.support_monogram_title),
                            subtitle = stringResource(R.string.support_monogram_subtitle_settings),
                            iconBackgroundColor = pinkColor,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onShowSupportClicked
                        )
                    }

                    item {
                        SettingsItem(
                            icon = Icons.Rounded.Info,
                            title = stringResource(R.string.about_title),
                            subtitle = stringResource(R.string.about_subtitle_settings),
                            iconBackgroundColor = blueColor,
                            position = ItemPosition.TOP,
                            onClick = component::onAboutClicked
                        )
                        SettingsItem(
                            icon = Icons.Rounded.BugReport,
                            title = stringResource(R.string.debug_title),
                            subtitle = stringResource(R.string.debug_subtitle),
                            iconBackgroundColor = Color.Gray,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onDebugClicked
                        )
                        SettingsItem(
                            icon = Icons.AutoMirrored.Rounded.ExitToApp,
                            title = stringResource(R.string.log_out_title),
                            subtitle = stringResource(R.string.log_out_subtitle),
                            iconBackgroundColor = MaterialTheme.colorScheme.error,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onLogoutClicked
                        )
                    }
                }
            }
        )
    }

    if (statusMenuTransitionState.currentState || statusMenuTransitionState.targetState) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = statusMenuScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showStatusMenu = false }
        ) {
            val menuHorizontalPadding = 16.dp
            val menuAnchorOverlap = 12.dp
            val menuBottomMargin = 8.dp
            val menuWidth = (maxWidth - menuHorizontalPadding * 2).coerceAtLeast(280.dp)
            val menuHeightLimit = 520.dp

            val rootWidthPx = constraints.maxWidth.coerceAtLeast(1)
            val rootHeightPx = constraints.maxHeight.coerceAtLeast(1)
            val menuWidthPx = with(density) { menuWidth.roundToPx() }
            val horizontalPaddingPx = with(density) { menuHorizontalPadding.roundToPx() }
            val anchorOverlapPx = with(density) { menuAnchorOverlap.roundToPx() }
            val menuBottomMarginPx = with(density) { menuBottomMargin.roundToPx() }
            val minMenuVisibleHeightPx = with(density) { 220.dp.roundToPx() }

            val statusBarTopPx = WindowInsets.statusBars.getTop(density)
            val navigationBarBottomPx = WindowInsets.navigationBars.getBottom(density)
            val minTopPx = statusBarTopPx + with(density) { 8.dp.roundToPx() }
            val fallbackTopPx = statusBarTopPx + with(density) { 56.dp.roundToPx() }

            val desiredTopPx = statusAnchorBounds
                ?.let { it.bottom.roundToInt() - anchorOverlapPx }
                ?: fallbackTopPx
            val desiredLeftPx = statusAnchorBounds
                ?.let { ((it.left + it.right) / 2f - menuWidthPx / 2f).roundToInt() }
                ?: horizontalPaddingPx

            val maxLeftPx =
                (rootWidthPx - menuWidthPx - horizontalPaddingPx).coerceAtLeast(horizontalPaddingPx)
            val clampedLeftPx = desiredLeftPx.coerceIn(horizontalPaddingPx, maxLeftPx)

            val maxTopPx = (
                    rootHeightPx - navigationBarBottomPx - menuBottomMarginPx - minMenuVisibleHeightPx
                    ).coerceAtLeast(minTopPx)
            val clampedTopPx = desiredTopPx.coerceIn(minTopPx, maxTopPx)

            val maxMenuHeightPx = (
                    rootHeightPx - clampedTopPx - navigationBarBottomPx - menuBottomMarginPx
                    ).coerceAtLeast(minMenuVisibleHeightPx)
            val maxMenuHeightDp =
                with(density) { maxMenuHeightPx.toDp() }.coerceAtMost(menuHeightLimit)

            AnimatedVisibility(
                visibleState = statusMenuTransitionState,
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(animationSpec = tween(260)) { -it / 5 } +
                        scaleIn(
                            animationSpec = tween(260),
                            initialScale = 0.94f,
                            transformOrigin = TransformOrigin(0.85f, 0f)
                        ),
                exit = fadeOut(animationSpec = tween(130)) +
                        slideOutVertically(animationSpec = tween(170)) { -it / 6 } +
                        scaleOut(
                            animationSpec = tween(170),
                            targetScale = 0.97f,
                            transformOrigin = TransformOrigin(0.85f, 0f)
                        ),
                modifier = Modifier.offset { IntOffset(clampedLeftPx, clampedTopPx) }
            ) {
                Surface(
                    modifier = Modifier
                        .width(menuWidth)
                        .heightIn(max = maxMenuHeightDp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    EmojisGrid(
                        onEmojiSelected = { _, sticker ->
                            val customEmojiId = sticker?.customEmojiId
                            if (sticker != null && customEmojiId != null) {
                                if (!sticker.path.isNullOrBlank()) {
                                    cachedStatusEmojiPath = sticker.path
                                }
                                component.onSetEmojiStatus(customEmojiId, sticker.path)
                                showStatusMenu = false
                            }
                        },
                        emojiOnlyMode = true,
                        contentPadding = PaddingValues(bottom = 12.dp)
                    )
                }
            }
        }
    }
}
