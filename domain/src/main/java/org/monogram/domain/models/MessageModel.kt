package org.monogram.domain.models

import kotlinx.serialization.Serializable

data class MessageModel(
    val id: Long,
    val date: Int,
    val isOutgoing: Boolean,
    val senderName: String,
    val chatId: Long,
    val content: MessageContent,
    val senderId: Long = 0,
    val senderAvatar: String? = null,
    val senderPersonalAvatar: String? = null,
    val senderCustomTitle: String? = null,
    val isRead: Boolean = false,
    val replyToMsgId: Long? = null,
    val replyToMsg: MessageModel? = null,
    val forwardInfo: ForwardInfo? = null,
    val views: Int? = null,
    val viewCount: Int? = null,
    val mediaAlbumId: Long = 0L,
    val editDate: Int = 0,
    val sendingState: MessageSendingState? = null,
    val isChosen: Boolean = false,
    val readDate: Int = 0,
    val reactions: List<MessageReactionModel> = emptyList(),
    val isSenderVerified: Boolean = false,
    val threadId: Long? = null,
    val canBeEdited: Boolean = false,
    val canBeForwarded: Boolean = true,
    val canBeDeletedOnlyForSelf: Boolean = true,
    val canBeDeletedForAllUsers: Boolean = false,
    val canBeSaved: Boolean = true,
    val canGetMessageThread: Boolean = false,
    val canGetStatistics: Boolean = false,
    val canGetRevenueStatistics: Boolean = false,
    val canGetMediaStatistics: Boolean = false,
    val canGetReadReceipts: Boolean = false,
    val canGetViewers: Boolean = false,
    val replyCount: Int = 0,
    val replyMarkup: ReplyMarkupModel? = null,
    val viaBotUserId: Long = 0L,
    val viaBotName: String? = null,
    val isSenderPremium: Boolean = false,
    val senderStatusEmojiId: Long = 0L,
    val senderStatusEmojiPath: String? = null
)

data class ForwardInfo(
    val date: Int,
    val fromId: Long,
    val fromName: String,
    val originChatId: Long? = null,
    val originMessageId: Long? = null
)

sealed interface MessageSendingState {
    object Pending : MessageSendingState
    data class Failed(val errorCode: Int, val errorMessage: String) : MessageSendingState
}

sealed interface MessageContent {
    data class Text(
        val text: String,
        val entities: List<MessageEntity> = emptyList(),
        val webPage: WebPage? = null
    ) : MessageContent

    data class Service(
        val text: String
    ) : MessageContent

    data class Photo(
        val path: String?,
        val thumbnailPath: String? = null,
        val width: Int,
        val height: Int,
        val caption: String = "",
        val entities: List<MessageEntity> = emptyList(),
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0,
        val minithumbnail: ByteArray? = null,
        val hasSpoiler: Boolean = false
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Photo

            if (width != other.width) return false
            if (height != other.height) return false
            if (isUploading != other.isUploading) return false
            if (uploadProgress != other.uploadProgress) return false
            if (isDownloading != other.isDownloading) return false
            if (downloadProgress != other.downloadProgress) return false
            if (downloadError != other.downloadError) return false
            if (fileId != other.fileId) return false
            if (hasSpoiler != other.hasSpoiler) return false
            if (path != other.path) return false
            if (thumbnailPath != other.thumbnailPath) return false
            if (caption != other.caption) return false
            if (entities != other.entities) return false
            if (!(minithumbnail contentEquals other.minithumbnail)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + isUploading.hashCode()
            result = 31 * result + uploadProgress.hashCode()
            result = 31 * result + isDownloading.hashCode()
            result = 31 * result + downloadProgress.hashCode()
            result = 31 * result + downloadError.hashCode()
            result = 31 * result + fileId
            result = 31 * result + hasSpoiler.hashCode()
            result = 31 * result + (path?.hashCode() ?: 0)
            result = 31 * result + (thumbnailPath?.hashCode() ?: 0)
            result = 31 * result + caption.hashCode()
            result = 31 * result + entities.hashCode()
            result = 31 * result + (minithumbnail?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Video(
        val path: String?,
        val thumbnailPath: String? = null,
        val width: Int,
        val height: Int,
        val duration: Int,
        val caption: String = "",
        val entities: List<MessageEntity> = emptyList(),
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0,
        val minithumbnail: ByteArray? = null,
        val supportsStreaming: Boolean = false,
        val hasSpoiler: Boolean = false
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Video

            if (width != other.width) return false
            if (height != other.height) return false
            if (duration != other.duration) return false
            if (isUploading != other.isUploading) return false
            if (uploadProgress != other.uploadProgress) return false
            if (isDownloading != other.isDownloading) return false
            if (downloadProgress != other.downloadProgress) return false
            if (downloadError != other.downloadError) return false
            if (fileId != other.fileId) return false
            if (supportsStreaming != other.supportsStreaming) return false
            if (hasSpoiler != other.hasSpoiler) return false
            if (path != other.path) return false
            if (thumbnailPath != other.thumbnailPath) return false
            if (caption != other.caption) return false
            if (entities != other.entities) return false
            if (!(minithumbnail contentEquals other.minithumbnail)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + duration
            result = 31 * result + isUploading.hashCode()
            result = 31 * result + uploadProgress.hashCode()
            result = 31 * result + isDownloading.hashCode()
            result = 31 * result + downloadProgress.hashCode()
            result = 31 * result + downloadError.hashCode()
            result = 31 * result + fileId
            result = 31 * result + supportsStreaming.hashCode()
            result = 31 * result + hasSpoiler.hashCode()
            result = 31 * result + (path?.hashCode() ?: 0)
            result = 31 * result + (thumbnailPath?.hashCode() ?: 0)
            result = 31 * result + caption.hashCode()
            result = 31 * result + entities.hashCode()
            result = 31 * result + (minithumbnail?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Voice(
        val path: String?,
        val duration: Int,
        val waveform: ByteArray? = null,
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Voice

            if (duration != other.duration) return false
            if (isUploading != other.isUploading) return false
            if (uploadProgress != other.uploadProgress) return false
            if (isDownloading != other.isDownloading) return false
            if (downloadProgress != other.downloadProgress) return false
            if (downloadError != other.downloadError) return false
            if (fileId != other.fileId) return false
            if (path != other.path) return false
            if (!(waveform contentEquals other.waveform)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = duration
            result = 31 * result + isUploading.hashCode()
            result = 31 * result + uploadProgress.hashCode()
            result = 31 * result + isDownloading.hashCode()
            result = 31 * result + downloadProgress.hashCode()
            result = 31 * result + downloadError.hashCode()
            result = 31 * result + fileId
            result = 31 * result + (path?.hashCode() ?: 0)
            result = 31 * result + (waveform?.contentHashCode() ?: 0)
            return result
        }
    }

    data class VideoNote(
        val path: String?,
        val thumbnail: String?,
        val duration: Int,
        val length: Int,
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0
    ) : MessageContent

    data class Document(
        val path: String?,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val caption: String = "",
        val entities: List<MessageEntity> = emptyList(),
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0
    ) : MessageContent

    data class Audio(
        val path: String?,
        val duration: Int,
        val title: String,
        val performer: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val caption: String = "",
        val entities: List<MessageEntity> = emptyList(),
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0
    ) : MessageContent

    data class Sticker(
        val id: Long,
        val setId: Long,
        val path: String?,
        val width: Int,
        val height: Int,
        val emoji: String = "",
        val format: StickerFormat = StickerFormat.UNKNOWN,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0
    ) : MessageContent

    data class Gif(
        val path: String?,
        val width: Int,
        val height: Int,
        val caption: String = "",
        val entities: List<MessageEntity> = emptyList(),
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadError: Boolean = false,
        val fileId: Int = 0,
        val minithumbnail: ByteArray? = null,
        val hasSpoiler: Boolean = false
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Gif

            if (width != other.width) return false
            if (height != other.height) return false
            if (isUploading != other.isUploading) return false
            if (uploadProgress != other.uploadProgress) return false
            if (isDownloading != other.isDownloading) return false
            if (downloadProgress != other.downloadProgress) return false
            if (downloadError != other.downloadError) return false
            if (fileId != other.fileId) return false
            if (hasSpoiler != other.hasSpoiler) return false
            if (path != other.path) return false
            if (caption != other.caption) return false
            if (entities != other.entities) return false
            if (!(minithumbnail contentEquals other.minithumbnail)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + isUploading.hashCode()
            result = 31 * result + uploadProgress.hashCode()
            result = 31 * result + isDownloading.hashCode()
            result = 31 * result + downloadProgress.hashCode()
            result = 31 * result + downloadError.hashCode()
            result = 31 * result + fileId
            result = 31 * result + hasSpoiler.hashCode()
            result = 31 * result + (path?.hashCode() ?: 0)
            result = 31 * result + caption.hashCode()
            result = 31 * result + entities.hashCode()
            result = 31 * result + (minithumbnail?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Contact(
        val phoneNumber: String,
        val firstName: String,
        val lastName: String,
        val vcard: String,
        val userId: Long,
        val avatarPath: String? = null
    ) : MessageContent

    data class Poll(
        val id: Long,
        val question: String,
        val description: String? = null,
        val options: List<PollOption>,
        val totalVoterCount: Int,
        val isClosed: Boolean,
        val isAnonymous: Boolean,
        val allowsRevoting: Boolean = true,
        val shuffleOptions: Boolean = false,
        val hideResultsUntilCloses: Boolean = false,
        val type: PollType,
        val openPeriod: Int,
        val closeDate: Int
    ) : MessageContent

    data class Location(
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracy: Double = 0.0,
        val livePeriod: Int = 0,
        val heading: Int = 0,
        val proximityAlertRadius: Int = 0
    ) : MessageContent

    data class Venue(
        val latitude: Double,
        val longitude: Double,
        val title: String,
        val address: String,
        val provider: String,
        val venueId: String,
        val venueType: String
    ) : MessageContent

    object Unsupported : MessageContent
}

data class WebPage(
    val url: String?,
    val displayUrl: String?,
    val type: LinkPreviewType,
    val siteName: String?,
    val title: String?,
    val description: String?,
    val photo: Photo?,
    val embedUrl: String?,
    val embedType: String?,
    val embedWidth: Int,
    val embedHeight: Int,
    val duration: Int,
    val author: String?,
    val video: Video?,
    val audio: Audio?,
    val document: Document?,
    val sticker: Sticker?,
    val animation: Animation?,
    val instantViewVersion: Int = 0
) {
    sealed interface LinkPreviewType {
        object Album : LinkPreviewType
        object Animation : LinkPreviewType
        object App : LinkPreviewType
        object Article : LinkPreviewType
        object Audio : LinkPreviewType
        object Background : LinkPreviewType
        object ChannelBoost : LinkPreviewType
        data class Chat(val chatId: Long) : LinkPreviewType
        object ChatFolder : LinkPreviewType
        object DirectMessagesChat : LinkPreviewType
        object Document : LinkPreviewType
        data class EmbeddedAnimation(val url: String) : LinkPreviewType
        data class EmbeddedAudio(val url: String) : LinkPreviewType
        data class EmbeddedVideo(val url: String) : LinkPreviewType
        data class ExternalAudio(val url: String) : LinkPreviewType
        data class ExternalVideo(val url: String) : LinkPreviewType
        object GiftCollection : LinkPreviewType
        object GroupCall : LinkPreviewType
        object Invoice : LinkPreviewType
        object Message : LinkPreviewType
        object Photo : LinkPreviewType
        object PremiumGiftCode : LinkPreviewType
        object Sticker : LinkPreviewType
        object StickerSet : LinkPreviewType
        data class Story(val chatId: Long, val storyId: Int) : LinkPreviewType
        object StoryAlbum : LinkPreviewType
        object SupergroupBoost : LinkPreviewType
        object Theme : LinkPreviewType
        object Unsupported : LinkPreviewType
        object UpgradedGift : LinkPreviewType
        data class User(val userId: Long) : LinkPreviewType
        object Video : LinkPreviewType
        object VideoChat : LinkPreviewType
        object VideoNote : LinkPreviewType
        object VoiceNote : LinkPreviewType
        data class WebApp(val url: String) : LinkPreviewType
        object Unknown : LinkPreviewType
        object InstantView : LinkPreviewType
    }

    data class Photo(
        val path: String?,
        val width: Int,
        val height: Int,
        val fileId: Int,
        val minithumbnail: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Photo

            if (width != other.width) return false
            if (height != other.height) return false
            if (fileId != other.fileId) return false
            if (path != other.path) return false
            if (!(minithumbnail contentEquals other.minithumbnail)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + fileId
            result = 31 * result + (path?.hashCode() ?: 0)
            result = 31 * result + (minithumbnail?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Video(
        val path: String?,
        val width: Int,
        val height: Int,
        val duration: Int,
        val fileId: Int,
        val supportsStreaming: Boolean = false
    )

    data class Audio(
        val path: String?,
        val duration: Int,
        val title: String?,
        val performer: String?,
        val fileId: Int
    )

    data class Document(
        val path: String?,
        val fileName: String?,
        val mimeType: String?,
        val size: Long,
        val fileId: Int
    )

    data class Sticker(
        val path: String?,
        val width: Int,
        val height: Int,
        val emoji: String?,
        val fileId: Int
    )

    data class Animation(
        val path: String?,
        val width: Int,
        val height: Int,
        val duration: Int,
        val fileId: Int
    )
}

data class PollOption(
    val text: String,
    val voterCount: Int,
    val votePercentage: Int,
    val isChosen: Boolean,
    val isBeingChosen: Boolean = false
)

sealed interface PollType {
    data class Regular(val allowMultipleAnswers: Boolean) : PollType
    data class Quiz(val correctOptionIds: List<Int>, val explanation: String?) : PollType
}

@Serializable
data class MessageEntity(
    val offset: Int,
    val length: Int,
    val type: MessageEntityType
)

@Serializable
sealed interface MessageEntityType {
    object Bold : MessageEntityType
    object Italic : MessageEntityType
    object Underline : MessageEntityType
    object Strikethrough : MessageEntityType
    object Spoiler : MessageEntityType
    object BlockQuote : MessageEntityType
    object BlockQuoteExpandable: MessageEntityType
    object Code : MessageEntityType
    data class Pre(val language: String = "") : MessageEntityType
    data class TextUrl(val url: String) : MessageEntityType
    object Mention : MessageEntityType
    data class TextMention(val userId: Long) : MessageEntityType
    object Hashtag : MessageEntityType
    object BotCommand : MessageEntityType
    object Url : MessageEntityType
    object Email : MessageEntityType
    object PhoneNumber : MessageEntityType
    object BankCardNumber : MessageEntityType
    data class CustomEmoji(val emojiId: Long, val path: String? = null) : MessageEntityType
    data class Other(val srcEntity: String) : MessageEntityType
}

data class MessageReactionModel(
    val emoji: String? = null,
    val customEmojiId: Long? = null,
    val customEmojiPath: String? = null,
    val count: Int,
    val isChosen: Boolean,
    val recentSenders: List<ReactionSender> = emptyList()
)

data class ReactionSender(
    val id: Long,
    val name: String = "",
    val avatar: String? = null
)