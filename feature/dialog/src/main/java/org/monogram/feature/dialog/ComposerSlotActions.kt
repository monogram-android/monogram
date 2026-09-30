package org.monogram.feature.dialog

import org.monogram.core.ui.ComposerSlotAction

internal fun ComposerSlotAction.emojiPanelTab(): String? = when (this) {
    ComposerSlotAction.Emoji -> ComposerPanels.TAB_EMOJI
    ComposerSlotAction.Stickers -> ComposerPanels.TAB_STICKERS
    ComposerSlotAction.Gifs -> ComposerPanels.TAB_GIFS
    else -> null
}

internal fun performComposerSlot(
    action: ComposerSlotAction,
    panel: String?,
    tab: String,
    closeEmoji: () -> Unit,
    closeAttach: () -> Unit,
    openEmojiTab: (String) -> Unit,
    openAttach: () -> Unit,
    openPhotos: () -> Unit,
    openFile: () -> Unit,
    openLocation: () -> Unit,
) {
    val emojiTab = action.emojiPanelTab()
    when {
        emojiTab != null && panel == ComposerPanels.EMOJI && tab == emojiTab -> closeEmoji()
        emojiTab != null -> openEmojiTab(emojiTab)
        action == ComposerSlotAction.Attach && panel == ComposerPanels.ATTACH -> closeAttach()
        action == ComposerSlotAction.Attach -> openAttach()
        action == ComposerSlotAction.Photos ||
            action == ComposerSlotAction.File ||
            action == ComposerSlotAction.Location -> {
            if (panel == ComposerPanels.ATTACH) closeAttach()
            if (panel == ComposerPanels.EMOJI) closeEmoji()
            when (action) {
                ComposerSlotAction.Photos -> openPhotos()
                ComposerSlotAction.File -> openFile()
                ComposerSlotAction.Location -> openLocation()
            }
        }
    }
}