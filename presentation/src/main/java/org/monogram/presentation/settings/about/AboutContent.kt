@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.about

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.UpdateInfo
import org.monogram.domain.models.UpdateState
import org.monogram.presentation.R
import org.monogram.presentation.core.util.buildRichText
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem
import org.monogram.presentation.core.util.AppUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(component: AboutComponent) {
    val updateState by component.updateState.collectAsState()
    val tdLibVersion by component.tdLibVersion.collectAsState()
    val tdLibCommitHash by component.tdLibCommitHash.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember { AppUtils.getFullVersionString(context) }
    val loadingText = stringResource(R.string.loading_text)

    val versionWithHashFormat = stringResource(R.string.tdlib_version_with_hash)
    val displayTdLibVersion = remember(tdLibVersion, tdLibCommitHash, versionWithHashFormat, loadingText) {
        if (tdLibVersion == loadingText || tdLibVersion == "Loading...") {
            tdLibVersion
        } else if (tdLibCommitHash.isNotEmpty()) {
            String.format(versionWithHashFormat, tdLibVersion, tdLibCommitHash.take(7))
        } else {
            tdLibVersion
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
                Spacer(modifier = Modifier.height(32.dp))
            }

            item {
                SettingsItem(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.terms_of_service_title),
                    subtitle = stringResource(R.string.terms_of_service_subtitle),
                    iconBackgroundColor = Color(0xFF4285F4),
                    position = ItemPosition.TOP,
                    onClick = { uriHandler.openUri("https://telegram.org/tos") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.open_source_licenses_title),
                    subtitle = stringResource(R.string.open_source_licenses_subtitle),
                    iconBackgroundColor = Color(0xFF34A853),
                    position = ItemPosition.MIDDLE,
                    onClick = component::onOpenSourceLicensesClicked
                )

                SettingsItem(
                    icon = Icons.Rounded.Public,
                    title = stringResource(R.string.github_title),
                    subtitle = stringResource(R.string.github_subtitle),
                    iconBackgroundColor = Color(0xFF24292E),
                    position = ItemPosition.MIDDLE,
                    onClick = { uriHandler.openUri("https://github.com/monogram-android/monogram") }
                )

                SettingsItem(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.tdlib_version_title),
                    subtitle = displayTdLibVersion,
                    iconBackgroundColor = Color(0xFF673AB7),
                    position = ItemPosition.MIDDLE,
                    onClick = {
                        if (tdLibCommitHash.isNotEmpty()) {
                            uriHandler.openUri("https://github.com/tdlib/td/commit/$tdLibCommitHash")
                        }
                    }
                )

                UpdateSection(updateState, component)
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
                    onClick = { uriHandler.openUri("https://t.me/monogram_discuss") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Announcement,
                    title = stringResource(R.string.telegram_channel_title),
                    subtitle = stringResource(R.string.telegram_channel_subtitle),
                    iconBackgroundColor = Color(0xFF0088CC),
                    position = ItemPosition.MIDDLE,
                    onClick = { uriHandler.openUri("https://t.me/monogram_android") }
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
                    onClick = { uriHandler.openUri("https://t.me/gdlbo") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "Rozetka_img",
                    subtitle = stringResource(R.string.role_developer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { uriHandler.openUri("https://t.me/Rozetka_img") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "aliveoutside",
                    subtitle = stringResource(R.string.role_developer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { uriHandler.openUri("https://t.me/toxyxd") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "recodius",
                    subtitle = stringResource(R.string.role_developer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.MIDDLE,
                    onClick = { uriHandler.openUri("https://t.me/recodius") }
                )
                SettingsItem(
                    icon = Icons.Rounded.Brush,
                    title = "the8055u",
                    subtitle = stringResource(R.string.role_designer),
                    iconBackgroundColor = Color(0xFF607D8B),
                    position = ItemPosition.BOTTOM,
                    onClick = { uriHandler.openUri("https://t.me/the8055u") }
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
}

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
