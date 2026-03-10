package org.monogram.presentation.features.chats.chatList.components

import android.R.attr.version
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.models.UpdateState
import org.monogram.domain.models.UserModel
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.CountryManager
import org.monogram.presentation.core.util.formatMaskedGlobal
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.util.AppUtils
import kotlin.math.roundToInt

@Composable
fun AccountMenu(
    user: UserModel?,
    videoPlayerPool: VideoPlayerPool,
    attachMenuBots: List<AttachMenuBotModel>,
    updateState: UpdateState = UpdateState.Idle,
    onDismiss: () -> Unit,
    onSavedMessagesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAddAccountClick: () -> Unit,
    onHelpClick: () -> Unit,
    onUpdateClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onBotClick: (AttachMenuBotModel) -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isVisible by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var isPhoneVisible by remember { mutableStateOf(false) }

    val offsetY = remember { Animatable(0f) }
    val scrollState = rememberScrollState()

    val animateDismiss = {
        scope.launch {
            isVisible = false
            delay(300)
            onDismiss()
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta > 0 && offsetY.value < 0) {
                    val newOffset = (offsetY.value + delta).coerceAtMost(0f)
                    val consumed = newOffset - offsetY.value
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, consumed)
                }
                if (delta < 0 && offsetY.value > 0) {
                    val newOffset = (offsetY.value + delta).coerceAtLeast(0f)
                    val consumed = newOffset - offsetY.value
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (source == NestedScrollSource.UserInput) {
                    if (delta < 0 && scrollState.value == scrollState.maxValue) {
                        scope.launch {
                            val current = offsetY.value
                            val resistanceDelta = delta / 2
                            offsetY.snapTo((current + resistanceDelta).coerceAtLeast(-600f))
                        }
                        return Offset(0f, delta)
                    } else if (delta > 0 && scrollState.value == 0) {
                        scope.launch {
                            val current = offsetY.value
                            val resistanceDelta = delta / 2
                            offsetY.snapTo((current + resistanceDelta).coerceAtMost(600f))
                        }
                        return Offset(0f, delta)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val dismissThreshold = with(density) { 100.dp.toPx() }
                if (offsetY.value < -dismissThreshold || available.y < -2000f) {
                    animateDismiss()
                    return available
                } else if (offsetY.value > dismissThreshold || available.y > 2000f) {
                    animateDismiss()
                    return available
                } else if (offsetY.value != 0f) {
                    offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Dialog(
        onDismissRequest = { animateDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { animateDismiss() },
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = {
                        if (offsetY.value > 0) it else -it
                    },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            ) {
                Surface(
                    modifier = Modifier
                        .padding(8.dp)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                        .widthIn(max = 600.dp)
                        .offset { IntOffset(0, offsetY.value.roundToInt()) }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    val dismissThreshold = with(density) { 100.dp.toPx() }
                                    if (offsetY.value < -dismissThreshold || offsetY.value > dismissThreshold) {
                                        animateDismiss()
                                    } else {
                                        scope.launch {
                                            offsetY.animateTo(
                                                0f,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                            )
                                        }
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        val current = offsetY.value
                                        val delta = dragAmount / 2
                                        offsetY.snapTo((current + delta).coerceIn(-600f, 600f))
                                    }
                                }
                            )
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .nestedScroll(nestedScrollConnection)
                            .verticalScroll(scrollState)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                            )
                        }

                        MenuHeader(onDismiss = { animateDismiss() })

                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            ActiveAccountCard(
                                user = user,
                                isExpanded = isExpanded,
                                onExpandToggle = {
                                    isExpanded = !isExpanded
                                    isPhoneVisible = isExpanded
                                },
                                isPhoneVisible = isPhoneVisible,
                                onPhoneToggle = { isPhoneVisible = !isPhoneVisible },
                                videoPlayerPool = videoPlayerPool
                            )

                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    SettingsItem(
                                        icon = Icons.Rounded.Add,
                                        iconBackgroundColor = MaterialTheme.colorScheme.primary,
                                        title = "Add Account",
                                        subtitle = "Login to another account",
                                        position = ItemPosition.STANDALONE,
                                        onClick = {
                                            onAddAccountClick()
                                            animateDismiss()
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            val sideMenuBots = attachMenuBots.filter { it.showInSideMenu && it.name.isNotBlank() && it.icon?.icon != null }

                            sideMenuBots.forEachIndexed { index, bot ->
                                SettingsItem(
                                    icon = bot.icon!!.icon!!,
                                    iconBackgroundColor = MaterialTheme.colorScheme.tertiary,
                                    title = bot.name,
                                    position = if (index == 0) ItemPosition.TOP else ItemPosition.MIDDLE,
                                    onClick = {
                                        onBotClick(bot)
                                        animateDismiss()
                                    }
                                )
                            }

                            SettingsItem(
                                icon = Icons.Rounded.Person,
                                iconBackgroundColor = MaterialTheme.colorScheme.primary,
                                title = "My Profile",
                                subtitle = "View your profile",
                                position = if (sideMenuBots.isEmpty()) ItemPosition.TOP else ItemPosition.MIDDLE,
                                onClick = {
                                    onProfileClick()
                                    animateDismiss()
                                }
                            )

                            SettingsItem(
                                icon = Icons.Outlined.BookmarkBorder,
                                iconBackgroundColor = MaterialTheme.colorScheme.primary,
                                title = "Saved Messages",
                                subtitle = "Cloud storage",
                                position = ItemPosition.MIDDLE,
                                onClick = {
                                    onSavedMessagesClick()
                                    animateDismiss()
                                }
                            )

                            SettingsItem(
                                icon = Icons.Rounded.Settings,
                                iconBackgroundColor = MaterialTheme.colorScheme.secondary,
                                title = "Settings",
                                subtitle = "App configuration",
                                position = ItemPosition.MIDDLE,
                                onClick = {
                                    onSettingsClick()
                                    animateDismiss()
                                }
                            )

                            if (updateState is UpdateState.UpdateAvailable || updateState is UpdateState.ReadyToInstall || updateState is UpdateState.Downloading) {
                                SettingsItem(
                                    icon = Icons.Rounded.SystemUpdate,
                                    iconBackgroundColor = MaterialTheme.colorScheme.error,
                                    title = "Update Available",
                                    subtitle = when (updateState) {
                                        is UpdateState.UpdateAvailable -> "New version ${updateState.info.version} is available"
                                        is UpdateState.Downloading -> "Downloading update... ${(updateState.progress * 100).toInt()}%"
                                        is UpdateState.ReadyToInstall -> "Update ready to install"
                                        else -> null
                                    },
                                    position = ItemPosition.MIDDLE,
                                    onClick = {
                                        onUpdateClick()
                                        if (updateState !is UpdateState.Downloading) {
                                            animateDismiss()
                                        }
                                    }
                                )
                                if (updateState is UpdateState.Downloading) {
                                    LinearProgressIndicator(
                                        progress = { updateState.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .height(4.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.error,
                                        trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            SettingsItem(
                                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                iconBackgroundColor = MaterialTheme.colorScheme.tertiary,
                                title = "Help & Feedback",
                                subtitle = "FAQ and support",
                                position = ItemPosition.BOTTOM,
                                onClick = {
                                    onHelpClick()
                                    animateDismiss()
                                }
                            )
                        }

                        MenuFooter(
                            onPrivacyClick = {
                                uriHandler.openUri("https://telegram.org/privacy")
                                animateDismiss()
                            },
                            onTermsClick = {
                                uriHandler.openUri("https://telegram.org/tos")
                                animateDismiss()
                            }
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MonoGram Dev",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                shape = CircleShape
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveAccountCard(
    user: UserModel?,
    videoPlayerPool: VideoPlayerPool,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    isPhoneVisible: Boolean,
    onPhoneToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "rotation",
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )

    val containerColor = if (isExpanded)
        MaterialTheme.colorScheme.surfaceContainerHighest
    else
        MaterialTheme.colorScheme.surfaceContainer

    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onExpandToggle,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPhoneToggle()
                }
            ),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = if (isExpanded) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                path = user?.personalAvatarPath ?: user?.avatarPath,
                name = user?.firstName ?: "",
                size = 56.dp,
                fontSize = 20,
                videoPlayerPool = videoPlayerPool
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${user?.firstName} ${user?.lastName ?: ""}".trim().ifEmpty { "Unknown User" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = user?.phoneNumber?.let {
                        if (isPhoneVisible) CountryManager.formatPhone(context, it) else formatMaskedGlobal(it)
                    } ?: user?.username ?: "No info",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onPhoneToggle()
                    }
                )
            }

            IconButton(
                onClick = onExpandToggle,
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = "Show accounts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation)
                )
            }
        }
    }
}

@Composable
private fun MenuFooter(
    onPrivacyClick: () -> Unit,
    onTermsClick: () -> Unit
) {
    val context = LocalContext.current
    val version = remember { AppUtils.getFullVersionString(context) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FooterLink(text = "Privacy Policy", onClick = onPrivacyClick)
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FooterLink(text = "Terms of Service", onClick = onTermsClick)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "MonoGram Dev for Android v${version}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    )
}
