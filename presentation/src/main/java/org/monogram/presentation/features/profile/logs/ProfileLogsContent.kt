package org.monogram.presentation.features.profile.logs

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.MessageSenderModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.profile.logs.components.DateHeader
import org.monogram.presentation.features.profile.logs.components.FilterChipCompact
import org.monogram.presentation.features.profile.logs.components.LogBubble
import org.monogram.presentation.features.viewers.ImageViewer
import org.monogram.presentation.features.viewers.VideoViewer
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileLogsContent(component: ProfileLogsComponent) {
    val state by component.state.subscribeAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(state.logs.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { visibleItems ->
                val lastVisibleItem = visibleItems.lastOrNull()
                if (lastVisibleItem != null && lastVisibleItem.index >= state.logs.size - 5 &&
                    state.canLoadMore && !state.isLoadingMore && !state.isLoading
                ) {
                    component.onLoadMore()
                }
            }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleMedium)
                        if (state.logs.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.logs_events_count, state.logs.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = component::onShowFilters) {
                        Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.filters))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (state.logs.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.logs_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.logs_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        reverseLayout = true
                    ) {
                        itemsIndexed(
                            items = state.logs,
                            key = { _, event -> event.id }
                        ) { index, event ->
                            Column {
                                val showHeader = index == state.logs.lastIndex ||
                                        !isSameDay(event.date, state.logs[index + 1].date)

                                if (showHeader) {
                                    DateHeader(event.date)
                                }

                                val senderId = when (val s = event.memberId) {
                                    is MessageSenderModel.User -> s.userId
                                    is MessageSenderModel.Chat -> s.chatId
                                }
                                val senderInfo = state.senderInfo[senderId]

                                LogBubble(
                                    event = event,
                                    senderInfo = senderInfo,
                                    allSenderInfo = state.senderInfo,
                                    component = component,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        if (state.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.fullScreenPhotoPath != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            state.fullScreenPhotoPath?.let { path ->
                ImageViewer(
                    images = listOf(path),
                    startIndex = 0,
                    onDismiss = component::onDismissViewer,
                    autoDownload = true,
                    onPageChanged = {},
                    onForward = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    onDelete = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    onCopyLink = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    onCopyText = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    captions = listOfNotNull(state.fullScreenPhotoCaption),
                    downloadUtils = component.downloadUtils
                )
            }
        }

        AnimatedVisibility(
            visible = state.fullScreenVideoPath != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            state.fullScreenVideoPath?.let { path ->
                VideoViewer(
                    path = path,
                    onDismiss = component::onDismissViewer,
                    isGesturesEnabled = true,
                    isDoubleTapSeekEnabled = true,
                    seekDuration = 10,
                    isZoomEnabled = true,
                    onForward = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    onDelete = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    onCopyLink = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    onCopyText = { Toast.makeText(context, context.getString(R.string.logs_not_implemented), Toast.LENGTH_SHORT).show() },
                    caption = state.fullScreenVideoCaption,
                    fileId = state.fullScreenVideoFileId,
                    supportsStreaming = state.fullScreenVideoSupportsStreaming,
                    downloadUtils = component.downloadUtils
                )
            }
        }
    }

    if (state.isFiltersVisible) {
        ModalBottomSheet(
            onDismissRequest = component::onDismissFilters,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            var searchQuery by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.logs_filter_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        TextButton(onClick = {
                            component.onResetFilters()
                            searchQuery = ""
                        }) {
                            Text(stringResource(R.string.logs_filter_reset))
                        }
                        Button(onClick = component::onApplyFilters) {
                            Text(stringResource(R.string.logs_filter_apply))
                        }
                    }
                }

                val filterItems = remember {
                    listOf(
                        ProfileLogsComponent.FilterType.MESSAGE_EDITS to R.string.logs_action_edits,
                        ProfileLogsComponent.FilterType.MESSAGE_DELETIONS to R.string.logs_action_deletions,
                        ProfileLogsComponent.FilterType.MESSAGE_PINS to R.string.logs_action_pins,
                        ProfileLogsComponent.FilterType.MEMBER_JOINS to R.string.logs_action_joins,
                        ProfileLogsComponent.FilterType.MEMBER_LEAVES to R.string.logs_action_leaves,
                        ProfileLogsComponent.FilterType.MEMBER_INVITES to R.string.logs_action_invites,
                        ProfileLogsComponent.FilterType.MEMBER_PROMOTIONS to R.string.logs_action_promotions,
                        ProfileLogsComponent.FilterType.MEMBER_RESTRICTIONS to R.string.logs_action_restrictions,
                        ProfileLogsComponent.FilterType.INFO_CHANGES to R.string.logs_action_info,
                        ProfileLogsComponent.FilterType.SETTING_CHANGES to R.string.logs_action_settings,
                        ProfileLogsComponent.FilterType.INVITE_LINK_CHANGES to R.string.logs_action_links,
                        ProfileLogsComponent.FilterType.VIDEO_CHAT_CHANGES to R.string.logs_action_video
                    )
                }

                val filteredFilterItems = remember(searchQuery) {
                    filterItems
                }

                val filteredSenderInfo = remember(searchQuery, state.senderInfo) {
                    state.senderInfo.filter { it.value.name.contains(searchQuery, ignoreCase = true) }
                }

                if (filteredFilterItems.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.logs_filter_section_types))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filteredFilterItems) { (type, labelRes) ->
                                val isChecked = when (type) {
                                    ProfileLogsComponent.FilterType.MESSAGE_EDITS -> state.pendingFilters.messageEdits
                                    ProfileLogsComponent.FilterType.MESSAGE_DELETIONS -> state.pendingFilters.messageDeletions
                                    ProfileLogsComponent.FilterType.MESSAGE_PINS -> state.pendingFilters.messagePins
                                    ProfileLogsComponent.FilterType.MEMBER_JOINS -> state.pendingFilters.memberJoins
                                    ProfileLogsComponent.FilterType.MEMBER_LEAVES -> state.pendingFilters.memberLeaves
                                    ProfileLogsComponent.FilterType.MEMBER_INVITES -> state.pendingFilters.memberInvites
                                    ProfileLogsComponent.FilterType.MEMBER_PROMOTIONS -> state.pendingFilters.memberPromotions
                                    ProfileLogsComponent.FilterType.MEMBER_RESTRICTIONS -> state.pendingFilters.memberRestrictions
                                    ProfileLogsComponent.FilterType.INFO_CHANGES -> state.pendingFilters.infoChanges
                                    ProfileLogsComponent.FilterType.SETTING_CHANGES -> state.pendingFilters.settingChanges
                                    ProfileLogsComponent.FilterType.INVITE_LINK_CHANGES -> state.pendingFilters.inviteLinkChanges
                                    ProfileLogsComponent.FilterType.VIDEO_CHAT_CHANGES -> state.pendingFilters.videoChatChanges
                                    else -> false
                                }
                                FilterChipCompact(
                                    label = stringResource(labelRes),
                                    selected = isChecked,
                                    onClick = { component.onToggleFilter(type) }
                                )
                            }
                        }
                    }
                }

                SectionHeader(stringResource(R.string.logs_filter_section_users))

                SettingsTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.logs_filter_search_placeholder),
                    icon = if (searchQuery.isEmpty()) Icons.Rounded.Search else Icons.Rounded.Close,
                    position = ItemPosition.TOP,
                    singleLine = true,
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    } else null
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp, topStart = 4.dp, topEnd = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (filteredSenderInfo.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isEmpty()) stringResource(R.string.logs_filter_no_users) else stringResource(R.string.logs_filter_no_results, searchQuery),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(filteredSenderInfo.toList()) { (userId, info) ->
                                val isSelected = state.pendingFilters.userIds.contains(userId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { component.onToggleUserFilter(userId) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(path = info.avatarPath, videoPlayerPool = component.videoPlayerPool, name = info.name, size = 36.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = info.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.5f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isSameDay(date1: Int, date2: Int): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(Date(date1.toLong() * 1000)) == fmt.format(Date(date2.toLong() * 1000))
}
