package org.monogram.data.backend

import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.UserProfileSnapshotRepository

internal class TelegramBackendReadRouter(
    private val selectionStore: TelegramBackendSelectionStore,
    private val legacyDialogs: DialogSnapshotRepository,
    private val mtProtoDialogs: DialogSnapshotRepository,
    private val legacyMessageHistory: MessageHistorySnapshotRepository,
    private val mtProtoMessageHistory: MessageHistorySnapshotRepository,
    private val legacyUserProfiles: UserProfileSnapshotRepository,
    private val mtProtoUserProfiles: UserProfileSnapshotRepository,
) : DialogSnapshotRepository, MessageHistorySnapshotRepository, UserProfileSnapshotRepository {
    override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> =
        when (selectionStore.get(accountId)) {
            TelegramBackendKind.LEGACY -> legacyDialogs.getDialogs(accountId)
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoDialogs.getDialogs(accountId)
        }

    override suspend fun getHistory(request: MessageHistorySnapshotRequest): MessageHistorySnapshotPage =
        when (selectionStore.get(request.accountId)) {
            TelegramBackendKind.LEGACY -> legacyMessageHistory.getHistory(request)
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoMessageHistory.getHistory(request)
        }

    override suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel? =
        when (selectionStore.get(accountId)) {
            TelegramBackendKind.LEGACY -> legacyUserProfiles.getCurrentUser(accountId)
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoUserProfiles.getCurrentUser(accountId)
        }

    override suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel? =
        when (selectionStore.get(accountId)) {
            TelegramBackendKind.LEGACY -> legacyUserProfiles.getUser(accountId, userId)
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoUserProfiles.getUser(accountId, userId)
        }
}
