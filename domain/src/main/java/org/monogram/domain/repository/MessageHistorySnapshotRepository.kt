package org.monogram.domain.repository

import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.MessageHistorySnapshotRequest

/** Read-only message history projection used by backend candidates before full MessageRepository parity. */
interface MessageHistorySnapshotRepository {
    suspend fun getHistory(request: MessageHistorySnapshotRequest): MessageHistorySnapshotPage
}
