package org.monogram.core.models

/** Non-deletable General topic. https://core.telegram.org/api/forum#forum-topics */
const val GENERAL_FORUM_TOPIC_ID = 1

data class ForumTopic(
    val id: Int,
    val title: String,
    val iconColor: Int = 0,
    val iconEmojiId: Long? = null,
    val topMessageId: Int = 0,
    val date: Int = 0,
    val unreadCount: Int = 0,
    val unreadMentionsCount: Int = 0,
    val unreadReactionsCount: Int = 0,
    val readInboxMaxId: Int = 0,
    val pinned: Boolean = false,
    val closed: Boolean = false,
    val hidden: Boolean = false,
    val short: Boolean = false,
    val deleted: Boolean = false,
    val lastMessagePreview: String? = null,
) {
    val isGeneral: Boolean get() = id == GENERAL_FORUM_TOPIC_ID
}

data class ForumTopicsPage(
    val count: Int,
    val topics: List<ForumTopic>,
)

/**
 * Send/reply/read helpers for group forums.
 * https://core.telegram.org/api/forum#interacting-within-topics
 */
object ForumIo {
    const val CHANNEL_PEER_OFFSET: Long = 1_000_000_000_000L

    fun isChannelPeer(peerId: PeerId): Boolean = peerId.value <= -CHANNEL_PEER_OFFSET

    fun showTopicList(isForum: Boolean, threadTopMsgId: Int): Boolean =
        isForum && threadTopMsgId <= 0

    /** `messages.getReplies` msg_id; 0 means General / ordinary history. */
    fun historyThreadId(threadTopMsgId: Int): Int =
        if (threadTopMsgId > GENERAL_FORUM_TOPIC_ID) threadTopMsgId else 0

    fun usesDiscussionRead(threadTopMsgId: Int): Boolean =
        threadTopMsgId > GENERAL_FORUM_TOPIC_ID

    /**
     * Pair of `reply_to_msg_id` and `top_msg_id` for `inputReplyToMessage`.
     * Sending into a topic uses the topic id as `reply_to_msg_id`.
     * Replying inside a topic also sets `top_msg_id` when the reply is not the topic root.
     */
    fun sendReplyIds(threadTopMsgId: Int, replyToMsgId: Int?): Pair<Int, Int> {
        val topic = historyThreadId(threadTopMsgId)
        val reply = replyToMsgId?.takeIf { it > 0 }
        return when {
            reply != null && topic > 0 && reply != topic -> reply to topic
            reply != null -> reply to 0
            topic > 0 -> topic to 0
            else -> 0 to 0
        }
    }

    /** Navigation id: General keeps `1` so the topic list (threadTop=0) stays distinct. */
    fun dialogThreadId(topicId: Int): Int = topicId.coerceAtLeast(0)
}
