package org.monogram.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.perf.RecompositionProbe

/**
 * A dialog filter: an id (`null` is "All chats"), a label and unread counters.
 *
 * [unread] and [mutedUnread] count unread *chats*, never total chats, and are hidden for the
 * all-chats and unread-only filters whose label already carries that meaning.
 */
data class FolderChipItem(
    val id: Int?,
    val label: String,
    val unread: Int = 0,
    val mutedUnread: Int = 0,
    val isUnreadFilter: Boolean = false,
    val isAll: Boolean = false,
) {
    val showsBadge: Boolean get() = !isAll && !isUnreadFilter
}

/** The chip list as one immutable value to support structural equality skipping. */
@Immutable
data class FolderChips(val items: List<FolderChipItem>)

/**
 * Horizontally scrolling folder filters. Folders are filters, not destinations: the selected
 * chip is a filled chip with a check mark, never an underline tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderChipRow(
    chips: FolderChips,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
    manageContentDescription: String? = null,
    onLongPress: ((FolderChipItem) -> Unit)? = null,
    edgeFadeColor: Color = Color.Unspecified,
) {
    RecompositionProbe("FolderChipRow")
    val folders = chips.items
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, folders.size) {
        val index = folders.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            val layoutInfo = listState.layoutInfo
            val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val itemWidth = visibleItem?.size ?: 0
            val viewportWidth = layoutInfo.viewportSize.width
            val centeredOffset = if (viewportWidth > 0 && itemWidth > 0) {
                -((viewportWidth - itemWidth) / 2)
            } else {
                0
            }
            listState.animateScrollToItem(index, centeredOffset)
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            contentPadding = PaddingValues(start = 16.dp, end = 32.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(folders, key = { it.id ?: ALL_CHATS_KEY }) { folder ->
                FolderChip(
                    folder = folder,
                    selected = selectedId == folder.id,
                    onSelect = onSelect,
                    onLongPress = onLongPress,
                )
            }
            if (onManage != null) {
                item(key = MANAGE_CHIP_KEY) {
                    FilterChip(
                        selected = false,
                        onClick = onManage,
                        label = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        modifier = Modifier.semantics {
                            manageContentDescription?.let { contentDescription = it }
                        },
                    )
                }
            }
        }
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(20.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                if (edgeFadeColor == Color.Unspecified) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    edgeFadeColor
                                },
                            ),
                        ),
                    ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderChip(
    folder: FolderChipItem,
    selected: Boolean,
    onSelect: (Int?) -> Unit,
    onLongPress: ((FolderChipItem) -> Unit)?,
) {
    RecompositionProbe("FolderChip", folder.id)
    val latestFolder = rememberUpdatedState(folder)
    val latestOnLongPress = rememberUpdatedState(onLongPress)
    val longPress = remember(onLongPress == null) {
        if (onLongPress == null) {
            Modifier
        } else {
            Modifier.onLongPressOnly { latestOnLongPress.value?.invoke(latestFolder.value) }
        }
    }
    FilterChip(
        selected = selected,
        onClick = { onSelect(folder.id) },
        label = {
            Text(
                text = folder.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = longPress,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        trailingIcon = if (folder.showsBadge && (folder.unread > 0 || folder.mutedUnread > 0)) {
            {
                UnreadCountRow(
                    unmuted = folder.unread,
                    muted = folder.mutedUnread,
                    compact = true,
                )
            }
        } else {
            null
        },
    )
}

internal const val ALL_CHATS_KEY = Int.MIN_VALUE
private const val MANAGE_CHIP_KEY = "folder-manage"

/**
 * Long press without giving up the child's own tap handling. The down event is observed in the
 * initial pass so the chip keeps its click, ripple and accessibility semantics, and the gesture
 * is dropped as soon as the pointer moves past the touch slop (that movement is a scroll).
 */
private fun Modifier.onLongPressOnly(onLongPress: () -> Unit): Modifier =
    this.pointerInput(onLongPress) {
        val slop = viewConfiguration.touchSlop * viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val pressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    if (isScrollPastSlop(delta.x, delta.y, slop)) break
                }
                false
            } ?: true
            if (pressed) onLongPress()
        }
    }

/**
 * A gesture stops being a long press once the pointer travels past the touch slop; the
 * movement is a scroll. [touchSlopSquared] is already squared, so this compares in squared
 * units and stays a pure function the tests can pin.
 */
internal fun isScrollPastSlop(dx: Float, dy: Float, touchSlopSquared: Float): Boolean =
    dx * dx + dy * dy > touchSlopSquared
