package org.monogram.domain.repository

import org.monogram.domain.models.DialogSnapshotModel

/** Read-only dialog projection used by backend candidates before full ChatListRepository parity. */
interface DialogSnapshotRepository {
    suspend fun getDialogs(accountId: String): List<DialogSnapshotModel>

    /** `null` is the main list; Telegram's archive list uses server folder ID 1. */
    suspend fun getDialogsForFolder(accountId: String, folderId: Int?): List<DialogSnapshotModel> =
        getDialogs(accountId)

    /**
     * Fetches the next server-side dialog pages after the previous snapshot and returns the
     * refreshed snapshot; an empty result means no further server content is expected.
     */
    suspend fun loadMore(accountId: String, limit: Int): List<DialogSnapshotModel> = emptyList()

    suspend fun loadMoreForFolder(accountId: String, limit: Int, folderId: Int?): List<DialogSnapshotModel> =
        loadMore(accountId, limit)
}
