package org.monogram.presentation.settings.adblock

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ChatModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockContent(component: AdBlockComponent) {
    val state by component.state.subscribeAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.adblock_channels_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
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
        floatingActionButton = {
            AnimatedVisibility(
                visible = state.isEnabled,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(onClick = component::onAddKeywordClicked) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.cd_add_keyword)
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
        ) {
            item {
                SectionHeader(stringResource(R.string.section_general))
                SettingsSwitchTile(
                    icon = Icons.Rounded.Block,
                    title = stringResource(R.string.adblock_enable),
                    subtitle = stringResource(R.string.adblock_channels_subtitle),
                    checked = state.isEnabled,
                    iconColor = Color.Red,
                    position = if (state.isEnabled) ItemPosition.TOP else ItemPosition.STANDALONE,
                    onCheckedChange = component::onAdBlockEnabledChanged
                )
                if (state.isEnabled) {
                    SettingsTile(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAddCheck,
                        title = stringResource(R.string.adblock_whitelisted_channels),
                        subtitle = stringResource(
                            R.string.adblock_channels_count_format,
                            state.whitelistedChannels.size
                        ),
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.MIDDLE,
                        onClick = component::onWhitelistedChannelsClicked
                    )
                    SettingsTile(
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.adblock_load_base),
                        subtitle = stringResource(R.string.adblock_load_base_subtitle),
                        iconColor = MaterialTheme.colorScheme.secondary,
                        position = if (state.keywords.isNotEmpty()) ItemPosition.MIDDLE else ItemPosition.BOTTOM,
                        onClick = component::onLoadFromAssets
                    )
                    if (state.keywords.isNotEmpty()) {
                        SettingsTile(
                            icon = Icons.Rounded.ContentCopy,
                            title = stringResource(R.string.adblock_copy_all),
                            subtitle = stringResource(R.string.adblock_copy_all_subtitle),
                            iconColor = MaterialTheme.colorScheme.tertiary,
                            position = ItemPosition.MIDDLE,
                            onClick = component::onCopyKeywords
                        )
                        SettingsTile(
                            icon = Icons.Rounded.DeleteSweep,
                            title = stringResource(R.string.adblock_clear_all),
                            subtitle = stringResource(R.string.adblock_clear_all_subtitle),
                            iconColor = MaterialTheme.colorScheme.error,
                            position = ItemPosition.BOTTOM,
                            onClick = component::onClearKeywords
                        )
                    }
                }
            }

            if (state.isEnabled) {
                item {
                    SectionHeader(stringResource(R.string.adblock_keywords_section))
                }
                val keywordsList = state.keywords.toList()
                if (keywordsList.isEmpty()) {
                    item {
                        EmptyKeywordsPlaceholder()
                    }
                } else {
                    itemsIndexed(keywordsList, key = { index, keyword -> "keyword_${index}_$keyword" }) { index, keyword ->
                        val position = when {
                            keywordsList.size == 1 -> ItemPosition.STANDALONE
                            index == 0 -> ItemPosition.TOP
                            index == keywordsList.size - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }
                        KeywordItem(
                            keyword = keyword,
                            position = position,
                            onRemove = { component.onRemoveKeyword(keyword) }
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(padding.calculateBottomPadding())) }
        }
    }

    if (state.showAddKeywordSheet) {
        AddKeywordBottomSheet(
            onDismiss = component::onDismissBottomSheet,
            onConfirm = component::onAddKeywords
        )
    }

    if (state.showWhitelistedSheet) {
        WhitelistedChannelsBottomSheet(
            channels = state.whitelistedChannelModels,
            onDismiss = component::onDismissBottomSheet,
            onRemove = component::onRemoveWhitelistedChannel,
            onClear = component::onClearWhitelistedChannels,
            videoPlayerPool = component.videoPlayerPool
        )
    }
}

@Composable
private fun KeywordItem(keyword: String, position: ItemPosition, onRemove: () -> Unit) {
    SettingsTile(
        icon = Icons.AutoMirrored.Rounded.Label,
        title = keyword,
        iconColor = MaterialTheme.colorScheme.primary,
        position = position,
        onClick = {},
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
private fun EmptyKeywordsPlaceholder() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.adblock_no_keywords),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.adblock_no_keywords_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeywordBottomSheet(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.adblock_add_keywords_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.adblock_add_keywords_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            SettingsTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.adblock_add_keywords_placeholder),
                icon = Icons.Rounded.Edit,
                position = ItemPosition.STANDALONE,
                minLines = 4
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onConfirm(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = text.isNotBlank(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.adblock_add_to_list),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhitelistedChannelsBottomSheet(
    channels: List<ChatModel>,
    videoPlayerPool: VideoPlayerPool,
    onDismiss: () -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.adblock_whitelisted_channels),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.adblock_whitelisted_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (channels.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.action_clear_all),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (channels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistAddCheck,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.adblock_no_whitelisted),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = channel.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "ID: ${channel.id}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = {
                                Avatar(
                                    path = channel.avatarPath,
                                    fallbackPath = channel.personalAvatarPath,
                                    name = channel.title,
                                    videoPlayerPool = videoPlayerPool,
                                    size = 48.dp
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { onRemove(channel.id) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.action_remove)
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
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