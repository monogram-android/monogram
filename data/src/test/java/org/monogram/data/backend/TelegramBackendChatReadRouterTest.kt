package org.monogram.data.backend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.mtproto.MtProtoDialogChatListRepository
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBackendChatReadRouterTest {
    @Test
    fun `MTProto selection publishes projected dialogs without constructing legacy chat contracts`() = runTest {
        var legacyFactoryCalls = 0
        val router = TelegramBackendChatReadRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = {
                legacyFactoryCalls++
                error("Legacy TDLib chat contracts must not be constructed")
            },
            mtProtoFactory = {
                MtProtoDialogChatListRepository(
                    dialogRepository = FakeDialogRepository(),
                    scope = backgroundScope,
                )
            },
            scope = backgroundScope,
        )

        runCurrent()

        assertEquals(0, legacyFactoryCalls)
        assertEquals(listOf(42L), router.chatListFlow.value.map { it.id })
        assertEquals(listOf(-1), router.foldersFlow.value.map { it.id })
        assertTrue(router.isArchivePinned.value.not())
        assertTrue(router.isArchiveAlwaysVisible.value.not())
    }

    private class FakeSelectionStore(
        private val backend: TelegramBackendKind,
    ) : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String): TelegramBackendKind = backend
        override fun observe(accountId: String): Flow<TelegramBackendKind> = flowOf(backend)
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeDialogRepository : DialogSnapshotRepository {
        override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> = listOf(
            DialogSnapshotModel(
                peerId = 42L,
                peerType = DialogPeerType.PRIVATE,
                title = "Peer",
                username = null,
                isPeerResolved = true,
                isPeerDeleted = false,
                isPeerForbidden = false,
                latestMessage = DialogMessagePreviewModel(
                    messageId = 1L,
                    senderId = null,
                    date = 1,
                    text = null,
                    isService = false,
                    isDeleted = false,
                    isOutgoing = false,
                    hasMedia = false,
                ),
            ),
        )
    }
}
