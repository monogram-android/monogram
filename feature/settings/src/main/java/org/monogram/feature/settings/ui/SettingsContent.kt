package org.monogram.feature.settings.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimatable
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.backhandler.BackHandler
import org.monogram.core.common.Outcome
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.AccentPreset
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.AppearanceState
import org.monogram.core.ui.ComposerStyle
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.ui.ThemePreference
import org.monogram.core.ui.components.AppBarSyncTitle
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.ChatComposerLayout
import org.monogram.core.ui.components.ItemPosition
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsCardRow
import org.monogram.core.ui.components.SettingsChoice
import org.monogram.core.ui.components.SettingsChoiceGroup
import org.monogram.core.common.SponsorRegistry
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingInlineSize
import org.monogram.core.ui.media.mediaViewerMotionEnabled
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.ui.theme.accentSwatch
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsFolderHost
import org.monogram.feature.settings.SettingsComponent
import org.monogram.feature.settings.SettingsPage
import org.monogram.network.http.FileCache
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalDecomposeApi::class)
@Composable
fun SettingsContent(
    component: SettingsComponent,
    modifier: Modifier = Modifier,
    gestureDispatcher: BackDispatcher? = null,
    folders: SettingsFolderHost? = null,
) {
    val state by component.state.collectAsState()
    val updateState by component.updateState.collectAsState()
    val sponsorIds by SponsorRegistry.sponsorIds.collectAsState()
    val notifications by component.notifications.collectAsState()
    val appearance by AppearanceSettings.state.collectAsStateWithLifecycle()
    val download by DownloadSettings.state.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var debugStatsExpanded by rememberSaveable { mutableStateOf("") }
    val pages by component.pages.subscribeAsState()
    val page = pages.active.instance
    val layoutDirection = LocalLayoutDirection.current
    val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val motion = mediaViewerMotionEnabled()
    val pageBackHandler = remember(component, gestureDispatcher) {
        MultiBackHandler(listOfNotNull<BackHandler>(component.backHandler, gestureDispatcher))
    }
    SideEffect { component.setPageBackHandler(folders?.onBack) }
    DisposableEffect(Unit) { onDispose { component.setPageBackHandler(null) } }
    val pageAnimation = remember(pageBackHandler, direction, motion) {
        val spec = tween<Float>(durationMillis = if (motion) 280 else 0, easing = FastOutSlowInEasing)
        predictiveBackAnimation<SettingsPage, SettingsPage>(
            backHandler = pageBackHandler,
            fallbackAnimation = stackAnimation(fade(animationSpec = spec) + slide(animationSpec = spec)),
            selector = { event, _, _ ->
                predictiveBackAnimatable(
                    initialBackEvent = event,
                    exitModifier = { progress, _ ->
                        Modifier.graphicsLayer { translationX = size.width * progress * direction }
                    },
                    enterModifier = { progress, _ ->
                        Modifier.graphicsLayer {
                            translationX = -size.width * 0.25f * (1f - progress) * direction
                        }
                    },
                )
            },
            onBack = component::popPage,
        )
    }
    val pageStates = rememberSaveableStateHolder()
    val profile = state.profile
    val cacheGeneration = component.mediaRepository?.cacheGeneration?.collectAsState()?.value ?: 0L
    val avatarFile = rememberEnsuredFile(
        generation = cacheGeneration,
        identity = profile?.id?.value to profile?.avatarCacheKey,
        resolve = {
            profile?.avatarCacheKey?.let { component.mediaRepository?.cachedFile(it) }
                ?: profile?.id?.let { component.mediaRepository?.cachedAvatar(it) }
        },
        ensure = {
            val repo = component.mediaRepository ?: return@rememberEnsuredFile null
            val peer = profile?.id ?: return@rememberEnsuredFile null
            val key = peerAvatarCacheKey(peer, profile.avatarCacheKey)
            when (val result = repo.ensureLocalAvatar(peer, key)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )

    val accountTitle = profile?.title ?: stringResource(R.string.settings_account)
    val accountSubtitle = profileSubtitle(profile?.username)
    val title = when (page) {
        SettingsPage.Home -> stringResource(R.string.settings_title)
        SettingsPage.Data -> stringResource(R.string.settings_data)
        SettingsPage.DebugStats -> stringResource(R.string.settings_debug_stats)
        is SettingsPage.AutoDownload -> stringResource(
            when (page.network) {
                "mobile" -> R.string.settings_autodownload_mobile
                "roaming" -> R.string.settings_autodownload_roaming
                else -> R.string.settings_autodownload_wifi
            },
        )
        SettingsPage.Appearance -> stringResource(R.string.settings_chat)
        SettingsPage.Notifications -> stringResource(R.string.settings_notifications)
        is SettingsPage.NotificationCategory -> stringResource(
            when (page.kind) {
                "chats" -> R.string.settings_notifications_groups
                "broadcasts" -> R.string.settings_notifications_channels
                else -> R.string.settings_notifications_private
            },
        )
        SettingsPage.NotificationExceptions -> stringResource(R.string.settings_notifications_exceptions)
        SettingsPage.NotificationDebug -> stringResource(R.string.settings_notifications_debug)
        SettingsPage.Wallpaper -> stringResource(R.string.settings_wallpaper)
        SettingsPage.Folders -> folders?.title ?: stringResource(R.string.settings_folders)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    AnimatedContent(
                        targetState = title to (page == SettingsPage.Home),
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "settingsTitle",
                    ) { (barTitle, home) ->
                        if (home) {
                            AppBarSyncTitle(
                                idleTitle = barTitle,
                                sync = when {
                                    state.loading && profile == null -> AppSyncStatus.Connecting
                                    state.loading -> AppSyncStatus.Syncing
                                    else -> AppSyncStatus.Hidden
                                },
                            )
                        } else {
                            Text(
                                barTitle,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (page == SettingsPage.Home) {
                                component.onBack()
                            } else {
                                component.popPage()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                actions = {
                    if (page == SettingsPage.Folders && folders != null) {
                        AnimatedContent(
                            targetState = folders.isEditing,
                            transitionSpec = {
                                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                            },
                            label = "foldersActions",
                        ) { editing ->
                            Row {
                                if (editing) {
                                    TextButton(
                                        onClick = folders.onSave,
                                        enabled = folders.canSave,
                                    ) {
                                        Text(stringResource(R.string.settings_save))
                                    }
                                } else {
                                    IconButton(onClick = folders.onAdd) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = stringResource(R.string.settings_folders_add),
                                        )
                                    }
                                    IconButton(onClick = folders.onRefresh) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = stringResource(R.string.settings_folders_refresh),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Children(
            stack = component.pages,
            modifier = Modifier.fillMaxSize(),
            animation = pageAnimation,
        ) { child ->
            pageStates.SaveableStateProvider(settingsPageKey(child.instance)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    when (val visiblePage = child.instance) {
                    SettingsPage.Home -> SettingsPageList(innerPadding) {
                        if (state.error != null) {
                            item {
                                AppStatusBanner(
                                    sync = AppSyncStatus.Hidden,
                                    error = state.error,
                                    onRetry = component::onRefresh,
                                )
                            }
                        }
                        homeItems(
                            component = component,
                            stateTitle = accountTitle,
                            stateSubtitle = accountSubtitle,
                            avatarFile = avatarFile,
                            appVersion = state.appVersion,
                            buildStamp = state.buildStamp,
                            onOpen = component::openPage,
                            onLogout = { confirmLogout = true },
                            loading = state.loading || state.loggingOut,
                            sponsorIds = sponsorIds,
                            selfPeerId = state.profile?.id?.value,
                            updateState = updateState,
                            updatesEnabled = component.updatesEnabled,
                        )
                    }
                    SettingsPage.Data -> SettingsPageList(innerPadding) {
                        dataItems(
                            cacheBytes = state.cacheBytes,
                            cacheByKind = state.cacheByKind,
                            cacheChats = state.cacheChats,
                            cacheMessage = state.cacheMessage,
                            loading = state.loading || state.loggingOut,
                            download = download,
                            onSpeedUpUploads = DownloadSettings::setSpeedUpUploads,
                            onSpeedUpDownloads = DownloadSettings::setSpeedUpDownloads,
                            onOpenAutoDownload = { network ->
                                component.openPage(
                                    SettingsPage.AutoDownload(autoDownloadNetworkKey(network)),
                                )
                            },
                            onClear = { confirmClear = true },
                            onClearChat = component::onClearChatCache,
                            onClearKind = component::onClearKindCache,
                            onOpenDebugStats = if (org.monogram.core.common.DebugStats.enabled) {
                                { component.openPage(SettingsPage.DebugStats) }
                            } else {
                                null
                            },
                            mediaRepository = component.mediaRepository,
                        )
                    }
                    SettingsPage.DebugStats -> SettingsPageList(innerPadding) {
                        val expanded = debugStatsExpanded.split(',').filter { it.isNotEmpty() }.toSet()
                        debugStatsItems(
                            exportMessage = state.debugExportMessage,
                            expanded = expanded,
                            onToggleSection = { key ->
                                debugStatsExpanded = if (key in expanded) {
                                    expanded.minus(key).joinToString(",")
                                } else {
                                    expanded.plus(key).joinToString(",")
                                }
                            },
                            onExport = component::onExportDebugStats,
                            onClear = component::onClearDebugStats,
                        )
                    }
                    is SettingsPage.AutoDownload -> SettingsPageList(innerPadding) {
                        autoDownloadItems(
                            network = parseAutoDownloadNetwork(visiblePage.network),
                            preset = download.presetFor(parseAutoDownloadNetwork(visiblePage.network)),
                        )
                    }
                    SettingsPage.Appearance -> SettingsPageList(innerPadding) {
                        appearanceItems(
                            appearance = appearance,
                            onOpenWallpaper = { component.openPage(SettingsPage.Wallpaper) },
                        )
                    }
                    SettingsPage.Notifications -> SettingsPageList(innerPadding) {
                        notificationsItems(
                            component,
                            notifications,
                            debug = component.debugNotifications,
                            onOpenDebug = { component.openPage(SettingsPage.NotificationDebug) },
                            onOpenCategory = { kind ->
                                component.openPage(SettingsPage.NotificationCategory(kind))
                            },
                            onOpenExceptions = { component.openPage(SettingsPage.NotificationExceptions) },
                        )
                    }
                    is SettingsPage.NotificationCategory -> SettingsPageList(innerPadding) {
                        notificationCategoryItems(
                            component,
                            notifications,
                            visiblePage.kind,
                            onOpenExceptions = { component.openPage(SettingsPage.NotificationExceptions) },
                        )
                    }
                    SettingsPage.NotificationExceptions -> {
                        var editingChatId by remember { mutableStateOf<Long?>(null) }
                        SettingsPageList(innerPadding) {
                            notificationExceptionItems(
                                component,
                                notifications,
                                onEditChat = { editingChatId = it },
                            )
                        }
                        editingChatId?.let { chatId ->
                            NotificationChatModeDialog(
                                component = component,
                                state = notifications,
                                chatId = chatId,
                                onDismiss = { editingChatId = null },
                            )
                        }
                    }
                    SettingsPage.NotificationDebug -> SettingsPageList(innerPadding) {
                        notificationDebugItems(component, notifications)
                    }
                    SettingsPage.Wallpaper -> WallpaperSettings(component, Modifier.padding(innerPadding))
                    SettingsPage.Folders -> folders?.content(innerPadding)
                    }
                }
            }
        }
    }

    if (confirmLogout || state.loggingOut) {
        AlertDialog(
            onDismissRequest = {
                if (!state.loggingOut) confirmLogout = false
            },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = {
                Text(
                    stringResource(
                        if (state.loggingOut) {
                            R.string.settings_logout_working
                        } else {
                            R.string.settings_logout_confirm
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { component.onLogout() },
                    enabled = !state.loggingOut,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (state.loggingOut) {
                                R.string.settings_logout_working
                            } else {
                                R.string.settings_logout_action
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmLogout = false },
                    enabled = !state.loggingOut,
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_cache)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_clear_cache_confirm,
                        formatBytes(state.cacheBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        component.onClearCache()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.settings_clear_cache_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsPageList(
    innerPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}

private fun settingsPageKey(page: SettingsPage): String = when (page) {
    SettingsPage.Home -> "home"
    SettingsPage.Folders -> "folders"
    SettingsPage.Data -> "data"
    SettingsPage.DebugStats -> "debug-stats"
    is SettingsPage.AutoDownload -> "autodownload:${page.network}"
    SettingsPage.Appearance -> "appearance"
    SettingsPage.Wallpaper -> "wallpaper"
    SettingsPage.Notifications -> "notifications"
    is SettingsPage.NotificationCategory -> "notifications:${page.kind}"
    SettingsPage.NotificationExceptions -> "notifications:exceptions"
    SettingsPage.NotificationDebug -> "notifications:debug"
}

private class MultiBackHandler(private val handlers: List<BackHandler>) : BackHandler {
    override fun isRegistered(callback: BackCallback): Boolean = handlers.any { it.isRegistered(callback) }
    override fun register(callback: BackCallback) = handlers.forEach { it.register(callback) }
    override fun unregister(callback: BackCallback) = handlers.forEach { it.unregister(callback) }
}
