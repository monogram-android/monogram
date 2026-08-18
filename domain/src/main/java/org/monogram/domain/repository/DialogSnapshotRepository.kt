package org.monogram.domain.repository

import org.monogram.domain.models.DialogSnapshotModel

/** Read-only dialog projection used by backend candidates before full ChatListRepository parity. */
interface DialogSnapshotRepository {
    suspend fun getDialogs(accountId: String): List<DialogSnapshotModel>
}
