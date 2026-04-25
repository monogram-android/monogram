package org.monogram.presentation.features.chats.conversation.ui.content

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.AvatarForChat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ChatContentSearchOverlay(
    context: Context,
    query: String,
    results: List<MessageModel>,
    totalCount: Int,
    selectedIndex: Int,
    isSearching: Boolean,
    canLoadMore: Boolean,
    showAllResults: Boolean,
    showSearchFilters: Boolean,
    showSearchSenderPicker: Boolean,
    hasFiltersApplied: Boolean,
    selectedSender: UserModel?,
    searchSenderCandidates: List<UserModel>,
    fromEpochSeconds: Int?,
    toEpochSeconds: Int?,
    onLoadMore: () -> Unit,
    onResultClick: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShowAll: () -> Unit,
    onToggleFilters: () -> Unit,
    onToggleSenderPicker: () -> Unit,
    onSelectSender: (UserModel?) -> Unit,
    onApplyDateRange: (Int?, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(220)) +
                slideInVertically(
                    animationSpec = tween(280),
                    initialOffsetY = { it / 3 }
                ) +
                scaleIn(
                    animationSpec = tween(220),
                    initialScale = 0.96f
                ),
        exit = fadeOut(animationSpec = tween(160)) +
                slideOutVertically(
                    animationSpec = tween(180),
                    targetOffsetY = { it / 4 }
                ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnimatedVisibility(
                visible = showAllResults && results.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(
                            animationSpec = tween(240),
                            initialOffsetY = { it / 8 }
                        ),
                exit = fadeOut(animationSpec = tween(140)) +
                        slideOutVertically(
                            animationSpec = tween(180),
                            targetOffsetY = { it / 10 }
                        )
            ) {
                SearchResultsListOverlay(
                    query = query,
                    results = results,
                    selectedIndex = selectedIndex,
                    isSearching = isSearching,
                    canLoadMore = canLoadMore,
                    onLoadMore = onLoadMore,
                    onResultClick = onResultClick
                )
            }

            AnimatedVisibility(
                visible = showSearchFilters && showSearchSenderPicker,
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(
                            animationSpec = tween(220),
                            initialOffsetY = { it / 6 }
                        ) +
                        scaleIn(
                            animationSpec = tween(200),
                            initialScale = 0.98f
                        ),
                exit = fadeOut(animationSpec = tween(140)) +
                        slideOutVertically(
                            animationSpec = tween(160),
                            targetOffsetY = { it / 8 }
                        )
            ) {
                SearchSenderPickerOverlay(
                    selectedSenderId = selectedSender?.id,
                    senders = searchSenderCandidates,
                    onSelectSender = onSelectSender
                )
            }

            AnimatedVisibility(
                visible = showSearchFilters,
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(
                            animationSpec = tween(220),
                            initialOffsetY = { it / 6 }
                        ) +
                        scaleIn(
                            animationSpec = tween(200),
                            initialScale = 0.98f
                        ),
                exit = fadeOut(animationSpec = tween(140)) +
                        slideOutVertically(
                            animationSpec = tween(160),
                            targetOffsetY = { it / 8 }
                        )
            ) {
                SearchFilterTray(
                    selectedSender = selectedSender,
                    fromEpochSeconds = fromEpochSeconds,
                    toEpochSeconds = toEpochSeconds,
                    onToggleSenderPicker = onToggleSenderPicker,
                    onApplyToday = {
                        val now = LocalDate.now()
                        onApplyDateRange(
                            toStartOfDayEpochSeconds(now),
                            toEndOfDayEpochSeconds(now)
                        )
                    },
                    onApplyLastDays = { days ->
                        val now = LocalDate.now()
                        val from = now.minusDays((days - 1).toLong())
                        onApplyDateRange(
                            toStartOfDayEpochSeconds(from),
                            toEndOfDayEpochSeconds(now)
                        )
                    },
                    onResetDateRange = { onApplyDateRange(null, null) },
                    onPickFromDate = {
                        showSearchDatePicker(
                            context = context,
                            initialEpochSeconds = fromEpochSeconds,
                            onDateSelected = { date ->
                                val nextFrom = toStartOfDayEpochSeconds(date)
                                val nextTo = toEpochSeconds
                                    ?.let(::epochSecondsToLocalDate)
                                    ?.let { currentTo ->
                                        if (currentTo.isBefore(date)) {
                                            toEndOfDayEpochSeconds(date)
                                        } else {
                                            toEndOfDayEpochSeconds(currentTo)
                                        }
                                    }
                                onApplyDateRange(nextFrom, nextTo)
                            }
                        )
                    },
                    onPickToDate = {
                        showSearchDatePicker(
                            context = context,
                            initialEpochSeconds = toEpochSeconds,
                            onDateSelected = { date ->
                                val nextTo = toEndOfDayEpochSeconds(date)
                                val nextFrom = fromEpochSeconds
                                    ?.let(::epochSecondsToLocalDate)
                                    ?.let { currentFrom ->
                                        if (currentFrom.isAfter(date)) {
                                            toStartOfDayEpochSeconds(date)
                                        } else {
                                            toStartOfDayEpochSeconds(currentFrom)
                                        }
                                    }
                                onApplyDateRange(nextFrom, nextTo)
                            }
                        )
                    }
                )
            }

            SearchNavigationPanel(
                query = query,
                results = results,
                totalCount = totalCount,
                selectedIndex = selectedIndex,
                isSearching = isSearching,
                showAllResults = showAllResults,
                filtersExpanded = showSearchFilters,
                hasFiltersApplied = hasFiltersApplied,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShowAll = onToggleShowAll,
                onToggleFilters = onToggleFilters
            )
        }
    }
}

@Composable
private fun SearchNavigationPanel(
    query: String,
    results: List<MessageModel>,
    totalCount: Int,
    selectedIndex: Int,
    isSearching: Boolean,
    showAllResults: Boolean,
    filtersExpanded: Boolean,
    hasFiltersApplied: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShowAll: () -> Unit,
    onToggleFilters: () -> Unit
) {
    val hasResults = results.isNotEmpty()
    val selectedPosition = (selectedIndex + 1).takeIf { selectedIndex in results.indices } ?: 0
    val listIconRotation = animateFloatAsState(
        targetValue = if (showAllResults) 90f else 0f,
        animationSpec = tween(220),
        label = "SearchListRotation"
    )
    val statusText = when {
        isSearching -> stringResource(R.string.search_results_loading)
        query.isBlank() -> stringResource(R.string.no_results_found)
        else -> stringResource(R.string.no_search_results_format, query)
    }
    val counterText = stringResource(
        R.string.search_results_position_format,
        selectedPosition,
        totalCount.coerceAtLeast(results.size)
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 10.dp,
        shadowElevation = 14.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!hasResults) {
                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "SearchStatusText"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onToggleFilters,
                    shape = CircleShape,
                    color = if (hasFiltersApplied || filtersExpanded) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                    },
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = if (hasFiltersApplied || filtersExpanded) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Surface(
                    onClick = onToggleShowAll,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier.padding(9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = if (showAllResults) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.graphicsLayer {
                                rotationZ = listIconRotation.value
                            }
                        )
                    }
                }

                Surface(
                    onClick = onPrevious,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier.padding(9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                    tonalElevation = 2.dp
                ) {
                    AnimatedContent(
                        targetState = counterText,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
                        },
                        label = "SearchCounter"
                    ) { animatedCounter ->
                        Text(
                            text = animatedCounter,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    onClick = onNext,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    tonalElevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier.padding(9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterTray(
    selectedSender: UserModel?,
    fromEpochSeconds: Int?,
    toEpochSeconds: Int?,
    onToggleSenderPicker: () -> Unit,
    onApplyToday: () -> Unit,
    onApplyLastDays: (Int) -> Unit,
    onResetDateRange: () -> Unit,
    onPickFromDate: () -> Unit,
    onPickToDate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 10.dp,
        shadowElevation = 14.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchSenderChip(
                selectedSender = selectedSender,
                onClick = onToggleSenderPicker
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SearchMiniChip(
                    label = stringResource(R.string.search_date_all),
                    isActive = fromEpochSeconds == null && toEpochSeconds == null,
                    modifier = Modifier.weight(1f),
                    onClick = onResetDateRange
                )
                SearchMiniChip(
                    label = stringResource(R.string.preview_date_today),
                    isActive = isTodayRange(fromEpochSeconds, toEpochSeconds),
                    modifier = Modifier.weight(1f),
                    onClick = onApplyToday
                )
                SearchMiniChip(
                    label = stringResource(R.string.search_date_last_7_days),
                    isActive = matchesLastDaysRange(fromEpochSeconds, toEpochSeconds, 7),
                    modifier = Modifier.weight(1f),
                    onClick = { onApplyLastDays(7) }
                )
                SearchMiniChip(
                    label = stringResource(R.string.search_date_last_30_days),
                    isActive = matchesLastDaysRange(fromEpochSeconds, toEpochSeconds, 30),
                    modifier = Modifier.weight(1f),
                    onClick = { onApplyLastDays(30) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchRangeChip(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.search_date_from),
                    value = fromEpochSeconds?.let(::formatSearchDate),
                    onClick = onPickFromDate
                )
                SearchRangeChip(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.search_date_to),
                    value = toEpochSeconds?.let(::formatSearchDate),
                    onClick = onPickToDate
                )
            }
        }
    }
}

@Composable
private fun SearchSenderPickerOverlay(
    selectedSenderId: Long?,
    senders: List<UserModel>,
    onSelectSender: (UserModel?) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 10.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.985f)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item("all_senders") {
                SearchSenderRow(
                    title = stringResource(R.string.search_sender_all),
                    subtitle = stringResource(R.string.search_section_messages),
                    avatarPath = null,
                    isSelected = selectedSenderId == null,
                    onClick = { onSelectSender(null) }
                )
            }

            itemsIndexed(senders, key = { _, user -> user.id }) { _, user ->
                SearchSenderRow(
                    title = formatSearchSenderLabel(user),
                    subtitle = user.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
                    avatarPath = user.avatarPath,
                    isSelected = selectedSenderId == user.id,
                    onClick = { onSelectSender(user) }
                )
            }
        }
    }
}

@Composable
private fun SearchSenderRow(
    title: String,
    subtitle: String?,
    avatarPath: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarForChat(
                path = avatarPath,
                name = title,
                size = 32.dp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatSearchSenderLabel(user: UserModel): String {
    return listOfNotNull(
        user.firstName.takeIf { it.isNotBlank() },
        user.lastName?.takeIf { it.isNotBlank() }
    ).joinToString(" ").ifBlank {
        user.username?.takeIf { it.isNotBlank() } ?: user.id.toString()
    }
}

@Composable
private fun SearchSenderChip(
    selectedSender: UserModel?,
    onClick: () -> Unit
) {
    val label = selectedSender?.let(::formatSearchSenderLabel)
        ?: stringResource(R.string.search_sender_all)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarForChat(
                path = selectedSender?.avatarPath,
                name = label,
                size = 30.dp
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchMiniChip(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        tonalElevation = if (isActive) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchRangeChip(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value ?: stringResource(R.string.cd_select_date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun isTodayRange(fromEpochSeconds: Int?, toEpochSeconds: Int?): Boolean {
    val today = LocalDate.now()
    return fromEpochSeconds?.let(::epochSecondsToLocalDate) == today &&
            toEpochSeconds?.let(::epochSecondsToLocalDate) == today
}

private fun matchesLastDaysRange(fromEpochSeconds: Int?, toEpochSeconds: Int?, days: Int): Boolean {
    val today = LocalDate.now()
    return fromEpochSeconds?.let(::epochSecondsToLocalDate) == today.minusDays((days - 1).toLong()) &&
            toEpochSeconds?.let(::epochSecondsToLocalDate) == today
}

private fun showSearchDatePicker(
    context: Context,
    initialEpochSeconds: Int?,
    onDateSelected: (LocalDate) -> Unit
) {
    val initialDate = initialEpochSeconds?.let(::epochSecondsToLocalDate) ?: LocalDate.now()
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
        },
        initialDate.year,
        initialDate.monthValue - 1,
        initialDate.dayOfMonth
    ).show()
}

private fun epochSecondsToLocalDate(epochSeconds: Int): LocalDate {
    return Instant.ofEpochSecond(epochSeconds.toLong())
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
}

private fun toStartOfDayEpochSeconds(date: LocalDate): Int {
    return date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toInt()
}

private fun toEndOfDayEpochSeconds(date: LocalDate): Int {
    return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toInt() - 1
}

private fun formatSearchDate(epochSeconds: Int): String {
    return epochSecondsToLocalDate(epochSeconds).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}

@Composable
private fun SearchResultsListOverlay(
    query: String,
    results: List<MessageModel>,
    selectedIndex: Int,
    isSearching: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onResultClick: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, results.size, canLoadMore, isSearching) {
        if (!canLoadMore || isSearching) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { lastVisibleIndex ->
                if (lastVisibleIndex >= results.lastIndex - 4) {
                    onLoadMore()
                }
            }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 10.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.985f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(results, key = { _, message -> message.id }) { index, message ->
                    val preview = message.extractTextContent()
                        ?.replace('\n', ' ')
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: message.senderName.ifBlank { query }
                    val sender = message.senderName.ifBlank {
                        stringResource(R.string.search_section_messages)
                    }
                    val isSelected = index == selectedIndex

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultClick(index) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                        },
                        tonalElevation = if (isSelected) 2.dp else 0.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = sender,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            if (canLoadMore || isSearching) {
                TextButton(
                    onClick = onLoadMore,
                    enabled = !isSearching,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = if (isSearching) {
                            stringResource(R.string.search_results_loading)
                        } else {
                            stringResource(R.string.action_show_more)
                        }
                    )
                }
            }
        }
    }
}

