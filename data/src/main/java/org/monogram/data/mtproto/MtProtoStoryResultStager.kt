package org.monogram.data.mtproto

import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.Peer
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerStories_9de86f4fe6
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_025493d1a8
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_7c2143443e
import org.monogram.mtproto.tl.generated.cloud.layer223.Update
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateReadStories
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateStory
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.AllStories_75ae93d8cd
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.Stories_e08ba69811
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch

/** Converts authoritative story envelopes into restart-safe canonical projections. */
internal class MtProtoStoryResultStager(
    private val stories: MtProtoStoryProjectionStore,
    private val users: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val documents: MtProtoDocumentLocationStore = NoOpMtProtoDocumentLocationStore,
    private val photos: MtProtoPhotoLocationStore = NoOpMtProtoPhotoLocationStore,
) {
    suspend fun stageAllStories(
        scope: MtProtoAuthKeyScope,
        listType: String,
        result: AllStories_75ae93d8cd,
        append: Boolean = false,
    ) {
        users.upsert(scope, result.users)
        chats.upsert(scope, result.chats)
        val existing = if (append) stories.activeList(scope, listType) else emptyList()
        val pageOrderStart = if (append) existing.minOfOrNull { it.orderKey } ?: 0L else result.peerStories.size.toLong() + 1L
        val page = buildList {
            result.peerStories.forEachIndexed { peerIndex, peerStories ->
                val list = peerStories as? PeerStories_9de86f4fe6 ?: return@forEachIndexed
                val peer = list.peer.toKey()
                list.stories.forEach { story ->
                    stage(scope, peer, story)
                    add(
                        MtProtoStoryActiveListItem(
                            key = MtProtoStoryKey(peer.type, peer.id, story.id()),
                            orderKey = pageOrderStart - peerIndex - 1L,
                            canBeArchived = false,
                            maxReadStoryId = list.maxReadId ?: 0,
                        )
                    )
                }
            }
        }
        val pageKeys = page.mapTo(mutableSetOf()) { it.key }
        stories.replaceActiveList(
            scope = scope,
            listType = listType,
            stories = if (append) existing.filterNot { it.key in pageKeys } + page else page,
            cursor = MtProtoStoryListCursor(result.state, result.hasMore, result.count),
        )
    }

    suspend fun stagePeerStories(scope: MtProtoAuthKeyScope, result: PeerStories_9de86f4fe6) {
        val peer = result.peer.toKey()
        result.stories.forEach { stage(scope, peer, it) }
    }

    suspend fun stageStories(scope: MtProtoAuthKeyScope, peer: Peer, result: Stories_e08ba69811) {
        users.upsert(scope, result.users)
        chats.upsert(scope, result.chats)
        val key = peer.toKey()
        result.stories.forEach { stage(scope, key, it) }
    }

    suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch) {
        batch.otherUpdates.forEach { stageUpdate(scope, it) }
    }

    suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) {
        when (envelope) {
            is UpdatesCombined -> envelope.updates.forEach { stageUpdate(scope, it) }
            is Updates_02c952992b -> envelope.updates.forEach { stageUpdate(scope, it) }
            else -> Unit
        }
    }

    private suspend fun stageUpdate(scope: MtProtoAuthKeyScope, update: Update) {
        when (update) {
            is UpdateStory -> stage(scope, update.peer.toKey(), update.story)
            is UpdateReadStories -> stories.updateMaxReadStoryId(scope, update.peer.toKey().type, update.peer.toKey().id, update.maxId)
            else -> Unit
        }
    }

    private suspend fun stage(scope: MtProtoAuthKeyScope, peer: PeerKey, story: StoryItem_7c2143443e) {
        val payload = MtProtoStoryPayload(
            key = MtProtoStoryKey(peer.type, peer.id, story.id()),
            payload = CloudTlObjectCodec.encode(story),
            isDeleted = story is StoryItemDeleted,
        )
        if (story is StoryItem_025493d1a8) {
            (story.media as? MessageMediaDocument)?.document
                ?.let { it as? Document_be725c3b31 }
                ?.let { documents.upsert(scope, it) }
            (story.media as? MessageMediaPhoto)?.photo
                ?.let { it as? Photo_97e0ed8316 }
                ?.let { photos.upsert(scope, it) }
        }
        stories.upsert(scope, payload)
    }

    private fun StoryItem_7c2143443e.id(): Int = when (this) {
        is StoryItem_025493d1a8 -> id
        is StoryItemDeleted -> id
        is org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemSkipped -> id
    }

    private fun Peer.toKey() = when (this) {
        is PeerUser -> PeerKey("USER", userId)
        is PeerChat -> PeerKey("GROUP", chatId)
        is PeerChannel -> PeerKey("CHANNEL", channelId)
    }

    private data class PeerKey(val type: String, val id: Long)
}
