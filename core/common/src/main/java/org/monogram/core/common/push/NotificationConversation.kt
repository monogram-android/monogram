package org.monogram.core.common.push

object NotificationConversation {
    const val SELF_PERSON_KEY: String = "self"

    fun peerPersonKey(chatId: Long): String = "peer:$chatId"

    fun isOutgoing(messagePersonKey: String?, userPersonKey: String?): Boolean {
        if (messagePersonKey == SELF_PERSON_KEY) return true
        return messagePersonKey.isNullOrEmpty() && userPersonKey == SELF_PERSON_KEY
    }
}
