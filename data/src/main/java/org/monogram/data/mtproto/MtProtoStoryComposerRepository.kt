package org.monogram.data.mtproto

import kotlin.random.Random
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMedia
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMediaUploadedPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMediaUploadedDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import java.io.File
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPrivacyRule
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPrivacyValueAllowAll
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPrivacyValueAllowCloseFriends
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPrivacyValueAllowContacts
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPrivacyValueAllowUsers
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPrivacyValueDisallowUsers
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.EditStory
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.SendStory

/** Posts photo stories through MTProto and reads the server-staged story projection back. */
internal interface MtProtoStoryComposerRepository {
    suspend fun post(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel
    suspend fun edit(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean
}

internal class MtProtoStoryComposerRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val uploader: MtProtoFileUploader,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val storyResultStager: MtProtoStoryResultStager,
    private val storyReader: MtProtoStoryReadRepository,
    private val videoMetadataReader: MtProtoStoryVideoMetadataReader = AndroidMtProtoStoryVideoMetadataReader,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryComposerRepository {
    override suspend fun post(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel = runCatching {
        require(draft.isValid) { "MTProto story draft has no media" }
        val media = draft.toInputMedia()
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId)
        val envelope = transportFactory.open(accountSlot).use { transport ->
            transport.execute(
                SendStory(
                    pinned = draft.keepOnProfile,
                    noforwards = draft.protectContent,
                    fwdModified = false,
                    peer = peer,
                    media = media,
                    mediaAreas = null,
                    caption = draft.caption.takeIf(String::isNotBlank),
                    entities = null,
                    privacyRules = draft.privacy.toInputPrivacyRules(scope),
                    randomId = Random.nextLong(),
                    period = draft.activePeriodSeconds,
                    fwdFromId = null,
                    fwdFromStory = null,
                    albums = null,
                ),
            )
        }
        storyResultStager.stageLive(scope, envelope)
        val story = requireNotNull(findPostedStory(chatId, envelope)) {
            "MTProto did not return a readable posted story"
        }
        StoryPostResultModel.Success(story)
    }.getOrElse { error ->
        StoryPostResultModel.Failure(null, error.message ?: "Failed to post story")
    }

    override suspend fun edit(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean = runCatching {
        require(storyId > 0) { "MTProto story ID must be positive" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val envelope = transportFactory.open(accountSlot).use { transport ->
            transport.execute(
                EditStory(
                    peer = resolvePeer(scope, chatId),
                    id = storyId,
                    media = null,
                    mediaAreas = null,
                    caption = draft.caption,
                    entities = null,
                    privacyRules = draft.privacy.toInputPrivacyRules(scope),
                ),
            )
        }
        storyResultStager.stageLive(scope, envelope)
        true
    }.getOrDefault(false)

    private suspend fun findPostedStory(
        chatId: Long,
        envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5,
    ): StoryModel? {
        val update = envelope.updates()
            .filterIsInstance<org.monogram.mtproto.tl.generated.cloud.layer223.UpdateStory>()
            .lastOrNull()
            ?: return null
        return storyReader.getStory(chatId, update.story.id(), onlyLocal = false)
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5.updates() = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b -> updates
        is org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined -> updates
        else -> emptyList()
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_7c2143443e.id(): Int = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.StoryItem_025493d1a8 -> id
        is org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted -> id
        is org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemSkipped -> id
    }

    private suspend fun StoryComposerDraftModel.toInputMedia(): InputMedia = when (mediaType) {
        StoryMediaType.PHOTO -> InputMediaUploadedPhoto(false, uploader.upload(sourcePath), null, null)
        StoryMediaType.VIDEO -> {
            val metadata = videoMetadataReader.read(sourcePath)
            InputMediaUploadedDocument(
                nosoundVideo = false,
                forceFile = false,
                spoiler = false,
                file_ = uploader.upload(sourcePath),
                thumb = null,
                mimeType = sourcePath.videoMimeType(),
                attributes = listOf(
                    DocumentAttributeVideo(
                        roundMessage = false,
                        supportsStreaming = true,
                        nosound = false,
                        duration = metadata.durationSeconds,
                        w = metadata.width,
                        h = metadata.height,
                        preloadPrefixSize = null,
                        videoStartTs = null,
                        videoCodec = null,
                    ),
                ),
                stickers = null,
                videoCover = null,
                videoTimestamp = null,
                ttlSeconds = null,
            )
        }
    }

    private fun String.videoMimeType(): String = when (File(this).extension.lowercase()) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    private suspend fun StoryPrivacySettingsModel.toInputPrivacyRules(
        scope: MtProtoAuthKeyScope,
    ): List<InputPrivacyRule> = buildList {
        when (mode) {
            StoryPrivacyMode.EVERYONE -> add(InputPrivacyValueAllowAll)
            StoryPrivacyMode.CONTACTS -> add(InputPrivacyValueAllowContacts)
            StoryPrivacyMode.CLOSE_FRIENDS -> add(InputPrivacyValueAllowCloseFriends)
            StoryPrivacyMode.SELECTED_USERS -> add(InputPrivacyValueAllowUsers(selectedUserIds.map { it.toInputUser(scope) }))
        }
        if (exceptUserIds.isNotEmpty()) {
            add(InputPrivacyValueDisallowUsers(exceptUserIds.map { it.toInputUser(scope) }))
        }
    }

    private suspend fun Long.toInputUser(scope: MtProtoAuthKeyScope): InputUser_4020eae812 {
        val user = requireNotNull(users.get(scope, this)) { "Missing MTProto user projection: $this" }
        return InputUser_4020eae812(this, requireNotNull(user.accessHash) {
            "Missing MTProto user access hash: $this"
        })
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = if (chatId > -CHANNEL_OFFSET) {
            TelegramPeerChatId.decode(chatId)
        } else {
            val channelId = -(chatId + CHANNEL_OFFSET)
            val chat = requireNotNull(chats.get(scope, channelId)) { "Missing MTProto chat projection: $channelId" }
            TelegramPeerChatId.decode(chatId, chat.type == MtProtoChatType.CHANNEL)
        }
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("MTProto cannot post a story for an unknown peer")
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
