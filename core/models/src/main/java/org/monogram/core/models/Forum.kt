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

    fun openTopicId(threadTopMsgId: Int, isForum: Boolean): Int {
        if (!isForum) return historyThreadId(threadTopMsgId)
        return if (threadTopMsgId <= 0) 0 else threadTopMsgId
    }

    /**
     * Resolve the forum topic id for a message.
     * No `reply_to.forum_topic` -> General (`id=1`). Otherwise top, then reply.
     */
    fun topicId(message: Message, isForum: Boolean): Int {
        if (!isForum) return 0
        if (!message.forumTopic) {
            val top = message.replyToTopId?.takeIf { it > GENERAL_FORUM_TOPIC_ID }
            return top ?: GENERAL_FORUM_TOPIC_ID
        }
        val top = message.replyToTopId?.takeIf { it > 0 }
        val reply = message.replyToMsgId?.takeIf { it > 0 }
        return if (message.mediaKind == "service") {
            reply ?: top ?: GENERAL_FORUM_TOPIC_ID
        } else {
            top ?: reply ?: GENERAL_FORUM_TOPIC_ID
        }
    }

    fun belongsToOpenTopic(message: Message, threadTopMsgId: Int, isForum: Boolean): Boolean {
        val want = openTopicId(threadTopMsgId, isForum)
        if (want <= 0) return true
        if (!isForum) return false
        return topicId(message, true) == want
    }

    /** Hidden General sits above the list, then pinned, then the rest by date. */
    fun sortForumTopics(topics: List<ForumTopic>): List<ForumTopic> {
        if (topics.size <= 1) return topics
        return topics.sortedWith(
            compareByDescending<ForumTopic> { it.hidden }
                .thenByDescending { it.pinned }
                .thenByDescending { it.date },
        )
    }

    fun hiddenTopicCount(topics: List<ForumTopic>): Int = topics.count { it.hidden }

    /**
     * Hidden General stays out of the main list until the archive is revealed,
     * so a short viewport cannot keep it on screen.
     */
    fun displayedForumTopics(
        topics: List<ForumTopic>,
        archiveRevealed: Boolean,
    ): List<ForumTopic> {
        val sorted = sortForumTopics(topics)
        if (archiveRevealed) return sorted
        return sorted.filter { !it.hidden }
    }

    /** Hide/Show General right based on topic management permissions. */
    fun canHideGeneral(canManageTopics: Boolean): Boolean = canManageTopics

    fun archivePullShouldReveal(
        atTop: Boolean,
        hiddenCount: Int,
        revealed: Boolean,
        pulledPx: Float,
        snapPx: Float,
    ): Boolean {
        if (revealed || hiddenCount <= 0 || !atTop) return false
        return pulledPx >= snapPx && snapPx > 0f
    }

    /** One continuous overscroll. Release or reverse below snap discards the pull. */
    data class ArchivePullState(
        val pulledPx: Float = 0f,
        val revealed: Boolean = false,
    )

    fun archivePullOnScroll(
        state: ArchivePullState,
        atTop: Boolean,
        hiddenCount: Int,
        deltaY: Float,
        snapPx: Float,
    ): ArchivePullState {
        if (state.revealed || hiddenCount <= 0) return state.copy(pulledPx = 0f)
        if (!atTop) return state.copy(pulledPx = 0f)
        val nextPull = (state.pulledPx + deltaY).coerceAtLeast(0f)
        return if (archivePullShouldReveal(true, hiddenCount, false, nextPull, snapPx)) {
            ArchivePullState(pulledPx = 0f, revealed = true)
        } else {
            state.copy(pulledPx = nextPull)
        }
    }

    fun archivePullOnRelease(state: ArchivePullState): ArchivePullState =
        if (state.revealed) state else state.copy(pulledPx = 0f)

    /** Peek height for the hidden General slot: follow the finger, then rest at 0 or one row. */
    fun archivePeekPx(
        hiddenCount: Int,
        revealed: Boolean,
        pulledPx: Float,
        rowHeightPx: Float,
    ): Float {
        if (hiddenCount <= 0 || rowHeightPx <= 0f) return 0f
        if (revealed) return rowHeightPx * hiddenCount
        return pulledPx.coerceIn(0f, rowHeightPx * hiddenCount)
    }

    fun archivePullFollowsFinger(state: ArchivePullState): Boolean =
        !state.revealed && state.pulledPx > 0f
}
