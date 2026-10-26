package org.monogram.root

import org.monogram.core.common.Outcome
import org.monogram.core.database.StoredPayloadException
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.entity.OutgoingMediaSend
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.models.MessageSendOptions
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntities
import org.monogram.core.models.UploadItem
import org.monogram.network.bridge.message.MessageOps

private val supportedCaptionEntities = setOf(
    "bold",
    "heading",
    "italic",
    "underline",
    "strike",
    "code",
    "pre",
    "spoiler",
    "blockquote",
    "url",
    "mention",
    "hashtag",
    "bot_command",
    "email",
    "phone",
    "cashtag",
    "bank_card",
    "text_url",
    "custom_emoji",
    "mention_name"
)

internal suspend fun sendRetainedMedia(
    cache: OfflineWarmup, client: MessageOps, row: OutgoingMediaSend
): Outcome<Unit> {
    val epoch = cache.outgoingAccountEpoch()
    val items = prepareOutgoing {
        row.copy(batch = row.batch.copy(phase = OutgoingSendPhase.READY)).requireValid()
        check(cache.outgoingAccountIsCurrent(epoch))
        val files = checkNotNull(cache.outgoingMediaFiles)
        row.items.sortedBy { it.position }.map { item ->
            val message = item.message
            val entities = TextEntities.parse(message.entitiesJson)
            if (!message.entitiesJson.isNullOrBlank() && message.entitiesJson != "[]" &&
                TextEntities.serialize(entities) != message.entitiesJson
            ) throw StoredPayloadException()
            if (entities.any {
                    it.kind !in supportedCaptionEntities || it.offset < 0 || it.length <= 0 ||
                            it.offset.toLong() + it.length > message.text.orEmpty().length
                }) throw StoredPayloadException()
            UploadItem(
                path = files.verifiedFile(item.source).absolutePath,
                kind = message.mediaKind ?: throw StoredPayloadException(),
                mimeType = item.mimeType,
                fileName = message.fileName.orEmpty(),
                caption = message.text.orEmpty(),
                duration = message.mediaDuration ?: 0,
                width = message.mediaWidth ?: 0,
                height = message.mediaHeight ?: 0,
                randomId = item.randomId,
                captionEntities = entities
            )
        }
    }
    prepareOutgoing { check(cache.outgoingAccountIsCurrent(epoch)) }
    val first = row.items.minBy { it.position }.message
    val options =
        MessageSendOptions(silent = row.batch.silent, scheduleDate = row.batch.scheduleDate)
    return if (row.batch.album) {
        when (val result = client.sendUploadedAlbumWithOptions(
            PeerId(row.batch.chatId),
            items,
            options,
            replyToMsgId = first.replyToMsgId ?: 0,
            topMsgId = first.replyToTopId ?: 0
        )) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> result
        }
    } else {
        when (val result = client.sendUploadedMediaWithOptions(
            PeerId(row.batch.chatId),
            items.single(),
            options,
            replyToMsgId = first.replyToMsgId ?: 0,
            topMsgId = first.replyToTopId ?: 0,
            entitiesJson = first.entitiesJson
        )) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> result
        }
    }
}
