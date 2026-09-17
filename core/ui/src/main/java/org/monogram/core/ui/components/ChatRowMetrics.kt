package org.monogram.core.ui.components

import androidx.compose.ui.unit.dp

/**
 * Geometry of a dialog (chat list) row.
 *
 * The real row in `feature/chats` and [ChatListSkeleton] both build from these numbers, so the list
 * cannot jump when the first page arrives: same row height, same avatar, same line boxes and the
 * same insets.
 */
object ChatRowMetrics {
    /** Minimum height of one dialog row. */
    val Height = 72.dp

    val AvatarSize = 56.dp
    val AvatarGap = 12.dp
    val SideInset = 8.dp
    val Corner = 16.dp

    /** Padding between the row bounds and its content (outer + inner, as the row applies them). */
    val ContainerPaddingV = 2.dp
    val ContentPaddingH = 8.dp
    val ContentPaddingV = 6.dp

    /** Title line box, then the gap and the preview line box under it. */
    val TitleLineHeight = 24.dp
    val LineSpacing = 2.dp
    val PreviewLineHeight = 20.dp

    /** Gap before the trailing column, which holds the time line and the unread badge. */
    val RightGap = 8.dp
    val RightSpacing = 6.dp

    val ThumbSize = 32.dp
}
