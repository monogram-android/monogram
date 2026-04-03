package org.monogram.presentation.features.chats.chatList.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
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
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.util.AppUtils
import org.monogram.presentation.core.util.CountryManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountMenu(
    user: UserModel?,
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
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val addAccountNotImplemented = stringResource(R.string.not_implemented)

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
                        .padding(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                        )
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.4f
                                        ),
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
                                onPhoneToggle = { isPhoneVisible = !isPhoneVisible }
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
                                        title = stringResource(R.string.action_add_account),
                                        subtitle = stringResource(R.string.add_account_subtitle),
                                        position = ItemPosition.STANDALONE,
                                        onClick = {
                                            Toast.makeText(
                                                context,
                                                addAccountNotImplemented,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onAddAccountClick()
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            val sideMenuBots =
                                attachMenuBots.filter { it.showInSideMenu && it.name.isNotBlank() && it.icon?.icon != null }

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
                                title = stringResource(R.string.menu_my_profile),
                                subtitle = stringResource(R.string.menu_my_profile_subtitle),
                                position = if (sideMenuBots.isEmpty()) ItemPosition.TOP else ItemPosition.MIDDLE,
                                onClick = {
                                    onProfileClick()
                                    animateDismiss()
                                }
                            )

                            SettingsItem(
                                icon = Icons.Outlined.BookmarkBorder,
                                iconBackgroundColor = MaterialTheme.colorScheme.primary,
                                title = stringResource(R.string.menu_saved_messages),
                                subtitle = stringResource(R.string.menu_saved_messages_subtitle),
                                position = ItemPosition.MIDDLE,
                                onClick = {
                                    onSavedMessagesClick()
                                    animateDismiss()
                                }
                            )

                            SettingsItem(
                                icon = Icons.Rounded.Settings,
                                iconBackgroundColor = MaterialTheme.colorScheme.secondary,
                                title = stringResource(R.string.menu_settings),
                                subtitle = stringResource(R.string.menu_settings_subtitle),
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
                                    title = stringResource(R.string.menu_update_available),
                                    subtitle = when (updateState) {
                                        is UpdateState.UpdateAvailable -> stringResource(
                                            R.string.update_available_subtitle_format,
                                            updateState.info.version
                                        )

                                        is UpdateState.Downloading -> stringResource(
                                            R.string.update_downloading_subtitle_format,
                                            (updateState.progress * 100).toInt()
                                        )

                                        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_subtitle)
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
                                    LinearWavyProgressIndicator(
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
                                title = stringResource(R.string.menu_help_feedback),
                                subtitle = stringResource(R.string.menu_help_feedback_subtitle),
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.4f
                                        ),
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
            text = stringResource(R.string.app_name_dev),
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
                contentDescription = stringResource(R.string.cancel_button),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveAccountCard(
    user: UserModel?,
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
                path = user?.avatarPath,
                fallbackPath = user?.personalAvatarPath,
                name = user?.firstName ?: "",
                size = 56.dp,
                fontSize = 20
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${user?.firstName ?: ""} ${user?.lastName ?: ""}".trim()
                        .ifEmpty { stringResource(R.string.unknown_user) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = user?.phoneNumber?.let { phone ->
                        val formatted = remember(phone) {
                            CountryManager.formatPhoneNumber(phone)
                        }
                        if (isPhoneVisible) formatted
                        else {
                            CountryManager.maskPhoneNumber(formatted)
                        }
                    } ?: user?.username ?: stringResource(R.string.no_info),
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
                    contentDescription = stringResource(R.string.cd_show_accounts),
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
            FooterLink(text = stringResource(R.string.privacy_policy), onClick = onPrivacyClick)
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FooterLink(
                text = stringResource(R.string.terms_of_service_title),
                onClick = onTermsClick
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.app_version_footer_format, version),
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
