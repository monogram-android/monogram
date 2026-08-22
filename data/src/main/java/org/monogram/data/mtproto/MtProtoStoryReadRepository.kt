package org.monogram.data.mtproto

import org.monogram.domain.models.stories.StoryMediaModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMedia
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageMediaPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.Photo_97e0ed8316
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyRule
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyValueAllowAll
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyValueAllowCloseFriends
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyValueAllowContacts
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyValueAllowUsers
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyValueDisallowUsers
import org.monogram.mtproto.tl.generated.cloud.layer223.Reaction
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionCustomEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionPaid
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_025493d1a8
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryViews_5a0ef3ace9

/** Reads only durable MTProto story projections and requests owned local media transfers. */
internal fun interface MtProtoStoryReadRepository {
    suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean): StoryModel?
}

internal class MtProtoStoryReadRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val stories: MtProtoStoryProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val files: MtProtoFileRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryReadRepository {
    override suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean): StoryModel? {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val scope = scope()
        val key = storyKey(scope, chatId, storyId) ?: return null
        val stored = stories.get(scope, key) ?: return null
        if (stored.isDeleted) return null
        val story = runCatching { CloudTlObjectCodec.decode(stored.payload) as? StoryItem_025493d1a8 }.getOrNull()
            ?: return null
        val media = story.media.toMedia(onlyLocal) ?: return null
        val views = story.views as? StoryViews_5a0ef3ace9
        val isRead = (stories.activeList(scope, "MAIN") + stories.activeList(scope, "ARCHIVE"))
            .firstOrNull { it.key == key }
            ?.let { story.id <= it.maxReadStoryId }
            ?: false
        return StoryModel(
            id = story.id,
            posterChatId = chatId,
            date = story.date,
            caption = story.caption.orEmpty(),
            media = media,
            chosenReaction = story.sentReaction.toReaction(),
            privacy = story.privacy?.toPrivacy(),
            albumIds = story.albums.orEmpty(),
            isEdited = story.edited,
            isPostedToChatPage = story.pinned,
            isRead = isRead,
            viewCount = views?.viewsCount ?: 0,
            forwardCount = views?.forwardsCount ?: 0,
            reactionCount = views?.reactionsCount ?: 0,
        )
    }

    private suspend fun scope(): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountSlot)
        return MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }

    private suspend fun storyKey(scope: MtProtoAuthKeyScope, chatId: Long, storyId: Int): MtProtoStoryKey? = when {
        chatId > 0L -> MtProtoStoryKey("USER", chatId, storyId)
        chatId > -CHANNEL_OFFSET -> MtProtoStoryKey("GROUP", -chatId, storyId)
        else -> chats.get(scope, -(chatId + CHANNEL_OFFSET))?.let { MtProtoStoryKey("CHANNEL", it.chatId, storyId) }
    }

    private suspend fun MessageMedia.toMedia(onlyLocal: Boolean): StoryMediaModel? = when (this) {
        is MessageMediaPhoto -> (photo as? Photo_97e0ed8316)?.let { photo ->
            val file = files.registerPhoto(photo.id) ?: return null
            val path = files.getPath(file.fileId)
            if (path == null && !onlyLocal) files.download(file.fileId, 0L, 0L)
            StoryMediaModel(StoryMediaType.PHOTO, path, path)
        }
        is MessageMediaDocument -> (document as? Document_be725c3b31)?.takeIf { video }?.let { document ->
            val file = files.registerDocument(document.id) ?: return null
            if (file.mediaKind != MtProtoDocumentMediaKind.VIDEO) return null
            val path = files.getPath(file.fileId)
            if (path == null && !onlyLocal) files.download(file.fileId, 0L, 0L)
            StoryMediaModel(StoryMediaType.VIDEO, path, null, durationSeconds = file.duration?.toDouble())
        }
        else -> null
    }

    private fun List<PrivacyRule>.toPrivacy(): StoryPrivacySettingsModel? {
        val allowUsers = filterIsInstance<PrivacyValueAllowUsers>().flatMap { it.users }
        val disallowUsers = filterIsInstance<PrivacyValueDisallowUsers>().flatMap { it.users }
        val base = filterNot { it is PrivacyValueAllowUsers || it is PrivacyValueDisallowUsers }
        return when (base.singleOrNull()) {
            PrivacyValueAllowAll -> StoryPrivacySettingsModel(StoryPrivacyMode.EVERYONE, exceptUserIds = disallowUsers)
            PrivacyValueAllowContacts -> StoryPrivacySettingsModel(StoryPrivacyMode.CONTACTS, exceptUserIds = disallowUsers)
            PrivacyValueAllowCloseFriends -> if (allowUsers.isEmpty() && disallowUsers.isEmpty()) StoryPrivacySettingsModel(StoryPrivacyMode.CLOSE_FRIENDS) else null
            null -> if (base.isEmpty() && allowUsers.isNotEmpty() && disallowUsers.isEmpty()) StoryPrivacySettingsModel(StoryPrivacyMode.SELECTED_USERS, selectedUserIds = allowUsers) else null
            else -> null
        }
    }

    private fun Reaction?.toReaction(): StoryReactionModel? = when (this) {
        is ReactionEmoji -> StoryReactionModel(emoji = emoticon)
        is ReactionCustomEmoji -> StoryReactionModel(customEmojiId = documentId)
        ReactionPaid -> StoryReactionModel(isPaid = true)
        else -> null
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
