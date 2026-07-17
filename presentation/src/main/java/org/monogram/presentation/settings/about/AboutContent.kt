@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.about

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Announcement
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.UpdateInfo
import org.monogram.domain.models.UpdateState
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.util.AppUtils
import org.monogram.presentation.core.util.buildRichText
import org.monogram.presentation.core.util.toBuildTimeString
import org.monogram.presentation.core.util.toGitHubCommitTimeString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(component: AboutComponent) {
    val isTelemtBuild = BuildConfig.ENABLE_TELEMT_DNS
    val updateState by component.updateState.collectAsState()
    val tdLibVersion by component.tdLibVersion.collectAsState()
    val tdLibCommitHash by component.tdLibCommitHash.collectAsState()
    val recentCommitsState by component.recentCommitsState.collectAsState()
    val hasOpenSourceLicenses = component.hasOpenSourceLicenses
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val telegramLinkRepository: TelegramLinkRepository = koinInject()
    val scope = rememberCoroutineScope()
    val version = remember {
        buildString {
            append(AppUtils.getFullVersionString(context))
            if (isTelemtBuild) append(" (Telemt)")
        }
    }
    val buildTimeText = remember(component.buildTimeMillis) {
        component.buildTimeMillis.toBuildTimeString()
    }
    val buildInfoText = stringResource(
        R.string.build_info_format,
        component.buildBranch,
        component.buildCommitHash.take(7),
        buildTimeText
    )
    val loadingText = stringResource(R.string.loading_text)
    val recentCommitsSubtitle =
        if (component.buildBranch != "unknown" && component.buildBranch != "detached") {
            stringResource(R.string.recent_commits_subtitle_format, component.buildBranch)
        } else {
            stringResource(R.string.recent_commits_subtitle)
        }
    val termsOfServiceTitle = stringResource(R.string.terms_of_service_title)
    val termsOfServiceSubtitle = stringResource(R.string.terms_of_service_subtitle)
    val openSourceLicensesTitle = stringResource(R.string.open_source_licenses_title)
    val openSourceLicensesSubtitle = stringResource(R.string.open_source_licenses_subtitle)
    val githubTitle = stringResource(R.string.github_title)
    val githubSubtitle = stringResource(R.string.github_subtitle)
    val currentCommitTitle = stringResource(R.string.current_commit_title)
    val currentCommitSubtitle = stringResource(
        R.string.current_commit_subtitle_format,
        component.buildBranch,
        component.buildCommitHash.take(7)
    )
    val recentCommitsTitle = stringResource(R.string.recent_commits_title)

    val versionWithHashFormat = stringResource(R.string.tdlib_version_with_hash)
    val displayTdLibVersion = remember(tdLibVersion, tdLibCommitHash, versionWithHashFormat, loadingText) {
        if (tdLibVersion == loadingText) {
            tdLibVersion
        } else if (tdLibCommitHash.isNotEmpty()) {
            String.format(versionWithHashFormat, tdLibVersion, tdLibCommitHash.take(7))
        } else {
            tdLibVersion
        }
    }
    val openTelegramPath: (String) -> Unit = { path ->
        scope.launch {
            uriHandler.openUri(telegramLinkRepository.buildUrl(path))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_name_monogram),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.version_format, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = buildInfoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
            }

            item {
                val infoItems = listOfNotNull(
                    AboutActionItem(
                        icon = Icons.Rounded.Description,
                        title = termsOfServiceTitle,
                        subtitle = termsOfServiceSubtitle,
                        iconBackgroundColor = Color(0xFF4285F4),
                        onClick = component::onTermsOfServiceClicked
                    ),
                    hasOpenSourceLicenses.takeIf { it }?.let {
                        AboutActionItem(
                            icon = Icons.Rounded.Code,
                            title = openSourceLicensesTitle,
                            subtitle = openSourceLicensesSubtitle,
                            iconBackgroundColor = Color(0xFF34A853),
                            onClick = component::onOpenSourceLicensesClicked
                        )
                    },
                    AboutActionItem(
                        icon = Icons.Rounded.Public,
                        title = githubTitle,
                        subtitle = githubSubtitle,
                        iconBackgroundColor = Color(0xFF24292E),
                        onClick = { uriHandler.openUri("https://github.com/monogram-android/monogram") }
                    ),
                    component.currentCommitUrl?.let { commitUrl ->
                        AboutActionItem(
                            icon = Icons.Rounded.Code,
                            title = currentCommitTitle,
                            subtitle = currentCommitSubtitle,
                            iconBackgroundColor = Color(0xFF6D4C41),
                            onClick = { uriHandler.openUri(commitUrl) }
                        )
                    },
                    AboutActionItem(
                        icon = Icons.Rounded.History,
                        title = recentCommitsTitle,
                        subtitle = recentCommitsSubtitle,
                        iconBackgroundColor = Color(0xFF1A73E8),
                        onClick = component::onRecentCommitsClicked
                    )
                )

                infoItems.forEachIndexed { index, item ->
                    SettingsItem(
                        icon = item.icon,
                        title = item.title,
                        subtitle = item.subtitle,
                        iconBackgroundColor = item.iconBackgroundColor,
                        position = if (index == 0) ItemPosition.TOP else ItemPosition.MIDDLE,
                        onClick = item.onClick
                    )
                }

                SettingsItem(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.tdlib_version_title),
                    subtitle = displayTdLibVersion,
                    iconBackgroundColor = Color(0xFF673AB7),
                    position = if (isTelemtBuild) ItemPosition.BOTTOM else ItemPosition.MIDDLE,
                    onClick = {
                        if (tdLibCommitHash.isNotEmpty()) {
                            val tdLibCommitUrl = if (isTelemtBuild) {
                                "https://github.com/telemt/tdlib-obf/commit/$tdLibCommitHash"
                            } else {
                                "https://github.com/tdlib/td/commit/$tdLibCommitHash"
                            }
                            uriHandler.openUri(tdLibCommitUrl)
                        }
                    }
                )

                if (!isTelemtBuild) {
                    UpdateSection(updateState, component)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.community_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingsItem(
                    icon = Icons.Rounded.Forum,
                    title = stringResource(R.string.telegram_chat_title),
                    subtitle = stringResource(R.string.telegram_chat_subtitle),
                    iconBackgroundColor = Color(0xFF0088CC),
                    position = ItemPosition.TOP,
                    onClick = { openTelegramPath("monogram_discuss") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Announcement,
                    title = stringResource(R.string.telegram_channel_title),
                    subtitle = stringResource(R.string.telegram_channel_subtitle),
                    iconBackgroundColor = Color(0xFF0088CC),
                    position = ItemPosition.MIDDLE,
                    onClick = { openTelegramPath("monogram_android") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Favorite,
                    title = stringResource(R.string.support_monogram_title),
                    subtitle = stringResource(R.string.support_monogram_subtitle),
                    iconBackgroundColor = Color(0xFFFF5F2C),
                    position = ItemPosition.BOTTOM,
                    onClick = { uriHandler.openUri("https://boosty.to/monogram") }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.maintainers_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "gdlbo",
                    subtitle = stringResource(R.string.role_developer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.TOP,
                    onClick = { openTelegramPath("gdlbo") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "recodius",
                    subtitle = stringResource(R.string.role_developer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { openTelegramPath("recodius") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "Rozetka_img",
                    subtitle = stringResource(R.string.about_exdev),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { openTelegramPath("Rozetka_img") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "aliveoutside",
                    subtitle = stringResource(R.string.about_exdev),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { openTelegramPath("toxyxd") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Brush,
                    title = "the8055u",
                    subtitle = stringResource(R.string.role_designer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { openTelegramPath("the8055u") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Groups,
                    title = stringResource(R.string.about_contributors),
                    subtitle = stringResource(R.string.about_contributors_summary),
                    iconBackgroundColor = Color(0xFF7D8590),
                    position = ItemPosition.BOTTOM,
                    onClick = { uriHandler.openUri("https://github.com/monogram-android/monogram/graphs/contributors") }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.monogram_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.copyright_text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
            }
        }
    }

    if (recentCommitsState.isVisible) {
        RecentCommitsSheet(
            state = recentCommitsState,
            branch = component.buildBranch,
            onDismiss = component::onRecentCommitsDismissed,
            onRetry = component::retryRecentCommits,
            onCommitClicked = { uriHandler.openUri(it) }
        )
    }
}

private data class AboutActionItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val iconBackgroundColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun UpdateSection(state: UpdateState, component: AboutComponent) {
    var showChangelog by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = state is UpdateState.Downloading,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "UpdateSectionAnimation"
    ) { isDownloading ->
        if (isDownloading && state is UpdateState.Downloading) {
            DownloadingUpdateItem(state)
        } else {
            val title = when (state) {
                is UpdateState.Idle -> stringResource(R.string.check_for_updates)
                is UpdateState.Checking -> stringResource(R.string.checking_updates)
                is UpdateState.UpdateAvailable -> stringResource(R.string.update_available_format, state.info.version)
                is UpdateState.UpToDate -> stringResource(R.string.up_to_date)
                is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready)
                is UpdateState.Error -> stringResource(R.string.update_error)
                else -> ""
            }

            val subtitle = when (state) {
                is UpdateState.Idle -> stringResource(R.string.check_update_subtitle)
                is UpdateState.Checking -> stringResource(R.string.connecting_server_subtitle)
                is UpdateState.UpdateAvailable -> stringResource(R.string.update_available_subtitle)
                is UpdateState.UpToDate -> stringResource(R.string.latest_version_subtitle)
                is UpdateState.ReadyToInstall -> stringResource(R.string.install_update_subtitle)
                is UpdateState.Error -> state.message
                else -> ""
            }

            val icon = when (state) {
                is UpdateState.ReadyToInstall -> Icons.Rounded.SystemUpdate
                is UpdateState.Error -> Icons.Rounded.Error
                else -> Icons.Rounded.Update
            }

            SettingsItem(
                icon = icon,
                title = title,
                subtitle = subtitle,
                iconBackgroundColor = Color(0xFFF9AB00),
                position = ItemPosition.BOTTOM,
                onClick = {
                    when (state) {
                        is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Error -> component.checkForUpdates()
                        is UpdateState.UpdateAvailable -> {
                            if (state.info.changelog.isNotEmpty()) {
                                showChangelog = true
                            } else {
                                component.downloadUpdate()
                            }
                        }

                        is UpdateState.ReadyToInstall -> component.installUpdate()
                        else -> {}
                    }
                }
            )
        }
    }

    if (showChangelog && state is UpdateState.UpdateAvailable) {
        ChangelogSheet(
            info = state.info,
            onDismiss = { showChangelog = false },
            onDownload = {
                showChangelog = false
                component.downloadUpdate()
            }
        )
    }
}

@Composable
private fun DownloadingUpdateItem(state: UpdateState.Downloading) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(
            bottomStart = 24.dp,
            bottomEnd = 24.dp,
            topStart = 4.dp,
            topEnd = 4.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color = Color(0xFFF9AB00).copy(0.15f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color(0xFFF9AB00)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.downloading_update),
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 18.sp
                    )
                    Text(
                        text = stringResource(R.string.update_progress_format, (state.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val animatedProgress by animateFloatAsState(targetValue = state.progress, label = "progress")
            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Color(0xFFF9AB00),
                trackColor = Color(0xFFF9AB00).copy(alpha = 0.2f)
            )
        }
    }
    Spacer(Modifier.size(2.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangelogSheet(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.whats_new_format, info.version),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    info.changelog.forEach { change ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = buildRichText(change, MaterialTheme.colorScheme.primary),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.cancel_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        stringResource(R.string.download_update_action),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentCommitsSheet(
    state: RecentCommitsState,
    branch: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCommitClicked: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.recent_commits_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.recent_commits_sheet_branch_format, branch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            when {
                state.isLoading -> {
                    item {
                        Text(
                            text = stringResource(R.string.loading_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                state.errorMessage != null -> {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = onRetry) {
                                    Text(stringResource(R.string.retry_action))
                                }
                            }
                        }
                    }
                }

                state.commits.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.recent_commits_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        state.commits,
                        key = { _, commit -> commit.sha }) { index, commit ->
                        val subtitle = stringResource(
                            R.string.recent_commit_item_subtitle_format,
                            commit.sha.take(7),
                            commit.authorName,
                            commit.committedAt.toGitHubCommitTimeString()
                        )
                        SettingsItem(
                            icon = Icons.Rounded.Code,
                            title = commit.message,
                            subtitle = subtitle,
                            iconBackgroundColor = Color(0xFF6D4C41),
                            position = when {
                                state.commits.size == 1 -> ItemPosition.STANDALONE
                                index == 0 -> ItemPosition.TOP
                                index == state.commits.lastIndex -> ItemPosition.BOTTOM
                                else -> ItemPosition.MIDDLE
                            },
                            onClick = { onCommitClicked(commit.htmlUrl) }
                        )
                    }
                }
            }
        }
    }
}
