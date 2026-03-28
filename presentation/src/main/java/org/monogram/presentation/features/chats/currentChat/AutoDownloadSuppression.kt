package org.monogram.presentation.features.chats.currentChat

import java.util.concurrent.ConcurrentHashMap

object AutoDownloadSuppression {
    private val suppressedFileIds = ConcurrentHashMap.newKeySet<Int>()

    fun suppress(fileId: Int) {
        if (fileId != 0) suppressedFileIds.add(fileId)
    }

    fun clear(fileId: Int) {
        if (fileId != 0) suppressedFileIds.remove(fileId)
    }

    fun isSuppressed(fileId: Int): Boolean = fileId != 0 && suppressedFileIds.contains(fileId)
}
