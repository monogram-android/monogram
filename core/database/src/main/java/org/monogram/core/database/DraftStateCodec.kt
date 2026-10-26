package org.monogram.core.database

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.CloudDraftState
import org.monogram.core.models.PeerId

internal fun encodeDraftState(state: CloudDraftState): String =
    storedPayloadJson.encodeToString(
        StoredDraftState(
            chatId = state.draft.chatId.value,
            threadId = state.draft.threadId,
            text = state.draft.text,
            entities = state.draft.entitiesJson,
            replyTo = state.draft.replyToMessageId,
            date = state.draft.date,
            noWebpage = state.draft.noWebpage,
            unsupported = state.draft.hasUnsupportedContent,
            revision = state.revision,
            dirty = state.dirty,
            discardingUnsupported = state.discardingUnsupported,
            markdown = state.markdown,
            remoteDate = state.lastRemoteDate,
        )
    )

internal fun decodeDraftState(raw: String): CloudDraftState = readStoredPayload {
    val value = storedPayloadJson.decodeFromString<StoredDraftState>(raw)
    check(value.version == 1) { "Unsupported stored draft version" }
    CloudDraftState(
        draft = CloudDraft(
            chatId = PeerId(value.chatId),
            threadId = value.threadId,
            text = value.text,
            entitiesJson = value.entities,
            replyToMessageId = value.replyTo,
            date = value.date,
            noWebpage = value.noWebpage,
            hasUnsupportedContent = value.unsupported,
        ),
        revision = value.revision,
        dirty = value.dirty,
        discardingUnsupported = value.discardingUnsupported,
        markdown = value.markdown,
        lastRemoteDate = value.remoteDate,
    )
}

@Serializable
private data class StoredDraftState(
    @Required val version: Int = 1,
    val chatId: Long,
    val threadId: Int,
    val text: String,
    val entities: String? = null,
    val replyTo: Int,
    val date: Int,
    val noWebpage: Boolean,
    val unsupported: Boolean,
    val revision: Long,
    val dirty: Boolean,
    val discardingUnsupported: Boolean = false,
    val markdown: Boolean = false,
    val remoteDate: Int,
)

internal fun draftContextFromMetaKey(key: String): Pair<PeerId, Int>? {
    val parts = key.split(':')
    if (parts.firstOrNull() !in setOf("draft", "cloud_draft") || parts.size !in 2..3) return null
    val chatId = parts[1].toLongOrNull()?.takeIf { it != 0L } ?: return null
    val threadId =
        if (parts.size == 2) 0 else parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return PeerId(chatId) to threadId
}

internal fun cloudDraftMetaKey(chatId: Long, threadId: Int): String =
    "cloud_draft:$chatId:$threadId"
