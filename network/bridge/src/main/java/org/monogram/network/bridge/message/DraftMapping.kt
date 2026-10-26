package org.monogram.network.bridge.message

import org.monogram.core.models.CloudDraft
import org.monogram.core.models.PeerId
import uniffi.monogram_mtproto.CloudDraft as NativeCloudDraft

internal fun NativeCloudDraft.toModel() = CloudDraft(
    chatId = PeerId(chatId), threadId = threadId, text = text,
    entitiesJson = entitiesJson, replyToMessageId = replyToMessageId,
    date = date, noWebpage = noWebpage, hasUnsupportedContent = hasUnsupportedContent,
)

internal fun CloudDraft.toNative() = NativeCloudDraft(
    chatId = chatId.value, threadId = threadId, text = text,
    entitiesJson = entitiesJson, replyToMessageId = replyToMessageId,
    date = date, noWebpage = noWebpage, hasUnsupportedContent = hasUnsupportedContent,
)
