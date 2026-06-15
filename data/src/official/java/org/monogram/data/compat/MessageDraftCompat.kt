package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildDraftMessageTextContent(
    text: TdApi.FormattedText,
    linkPreviewOptions: TdApi.LinkPreviewOptions?
): TdApi.DraftMessageContent = TdApi.DraftMessageContentText(text, linkPreviewOptions)

internal fun TdApi.DraftMessage.extractTextDraft(): String? {
    val draftContent = content as? TdApi.DraftMessageContentText ?: return null
    return draftContent.text.text
}
