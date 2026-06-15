package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildDraftMessageTextContent(
    text: TdApi.FormattedText,
    linkPreviewOptions: TdApi.LinkPreviewOptions?
): TdApi.InputMessageContent = TdApi.InputMessageText().apply {
    this.text = text
    this.linkPreviewOptions = linkPreviewOptions
}

internal fun TdApi.DraftMessage.extractTextDraft(): String? {
    val draftContent = inputMessageText as? TdApi.InputMessageText ?: return null
    return draftContent.text.text
}
