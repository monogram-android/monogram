package org.monogram.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.UserProfileSnapshotRepository

class TelegramBackendReadRouterTest {
    @Test
    fun `routes dialogs for each account selection`() = runBlocking {
        val fixture = Fixture(
            mapOf(
                "legacy-account" to TelegramBackendKind.LEGACY,
                "mtproto-account" to TelegramBackendKind.KOTLIN_MTPROTO,
            )
        )

        assertSame(fixture.legacyDialogs.result, fixture.router.getDialogs("legacy-account"))
        assertSame(fixture.mtProtoDialogs.result, fixture.router.getDialogs("mtproto-account"))
        assertEquals(listOf("legacy-account"), fixture.legacyDialogs.accounts)
        assertEquals(listOf("mtproto-account"), fixture.mtProtoDialogs.accounts)
        assertEquals(listOf("legacy-account", "mtproto-account"), fixture.selectionStore.requestedAccounts)
    }

    @Test
    fun `routes history for each request account selection`() = runBlocking {
        val fixture = Fixture(
            mapOf(
                "legacy-account" to TelegramBackendKind.LEGACY,
                "mtproto-account" to TelegramBackendKind.KOTLIN_MTPROTO,
            )
        )
        val legacyRequest = request("legacy-account", peerId = 11)
        val mtProtoRequest = request("mtproto-account", peerId = 22)

        assertSame(fixture.legacyHistory.result, fixture.router.getHistory(legacyRequest))
        assertSame(fixture.mtProtoHistory.result, fixture.router.getHistory(mtProtoRequest))
        assertEquals(listOf(legacyRequest), fixture.legacyHistory.requests)
        assertEquals(listOf(mtProtoRequest), fixture.mtProtoHistory.requests)
        assertEquals(listOf("legacy-account", "mtproto-account"), fixture.selectionStore.requestedAccounts)
    }

    @Test
    fun `routes both profile reads for each account selection`() = runBlocking {
        val fixture = Fixture(
            mapOf(
                "legacy-account" to TelegramBackendKind.LEGACY,
                "mtproto-account" to TelegramBackendKind.KOTLIN_MTPROTO,
            )
        )

        assertSame(fixture.legacyProfiles.currentResult, fixture.router.getCurrentUser("legacy-account"))
        assertSame(fixture.mtProtoProfiles.currentResult, fixture.router.getCurrentUser("mtproto-account"))
        assertSame(fixture.legacyProfiles.userResult, fixture.router.getUser("legacy-account", 11))
        assertSame(fixture.mtProtoProfiles.userResult, fixture.router.getUser("mtproto-account", 22))
        assertEquals(listOf("legacy-account"), fixture.legacyProfiles.currentAccounts)
        assertEquals(listOf("mtproto-account"), fixture.mtProtoProfiles.currentAccounts)
        assertEquals(listOf("legacy-account" to 11L), fixture.legacyProfiles.userRequests)
        assertEquals(listOf("mtproto-account" to 22L), fixture.mtProtoProfiles.userRequests)
        assertEquals(
            listOf("legacy-account", "mtproto-account", "legacy-account", "mtproto-account"),
            fixture.selectionStore.requestedAccounts,
        )
    }

    @Test
    fun `selected backend failure does not fall back`() {
        val fixture = Fixture(mapOf("account" to TelegramBackendKind.KOTLIN_MTPROTO))
        fixture.mtProtoDialogs.failure = IllegalStateException("mtproto unavailable")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.router.getDialogs("account") }
        }
        assertEquals(emptyList<String>(), fixture.legacyDialogs.accounts)
    }

    private fun request(accountId: String, peerId: Long) = MessageHistorySnapshotRequest(
        accountId = accountId,
        peerType = DialogPeerType.PRIVATE,
        peerId = peerId,
    )

    private class Fixture(selections: Map<String, TelegramBackendKind>) {
        val selectionStore = FakeSelectionStore(selections)
        val legacyDialogs = FakeDialogRepository(dialog(1))
        val mtProtoDialogs = FakeDialogRepository(dialog(2))
        val legacyHistory = FakeHistoryRepository(MessageHistorySnapshotPage(emptyList(), null))
        val mtProtoHistory = FakeHistoryRepository(MessageHistorySnapshotPage(emptyList(), null))
        val legacyProfiles = FakeUserProfileRepository(profile(1), profile(11))
        val mtProtoProfiles = FakeUserProfileRepository(profile(2), profile(22))
        val router = TelegramBackendReadRouter(
            selectionStore = selectionStore,
            legacyDialogs = legacyDialogs,
            mtProtoDialogs = mtProtoDialogs,
            legacyMessageHistory = legacyHistory,
            mtProtoMessageHistory = mtProtoHistory,
            legacyUserProfiles = legacyProfiles,
            mtProtoUserProfiles = mtProtoProfiles,
        )
    }

    private class FakeSelectionStore(
        private val selections: Map<String, TelegramBackendKind>,
    ) : TelegramBackendSelectionStore {
        val requestedAccounts = mutableListOf<String>()

        override suspend fun get(accountId: String): TelegramBackendKind {
            requestedAccounts += accountId
            return selections.getValue(accountId)
        }

        override fun observe(accountId: String): Flow<TelegramBackendKind> = flowOf(selections.getValue(accountId))
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeDialogRepository(
        dialog: DialogSnapshotModel,
    ) : DialogSnapshotRepository {
        val result = listOf(dialog)
        val accounts = mutableListOf<String>()
        var failure: RuntimeException? = null

        override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> {
            accounts += accountId
            failure?.let { throw it }
            return result
        }
    }

    private class FakeHistoryRepository(
        val result: MessageHistorySnapshotPage,
    ) : MessageHistorySnapshotRepository {
        val requests = mutableListOf<MessageHistorySnapshotRequest>()

        override suspend fun getHistory(request: MessageHistorySnapshotRequest): MessageHistorySnapshotPage {
            requests += request
            return result
        }
    }

    private class FakeUserProfileRepository(
        val currentResult: UserProfileSnapshotModel,
        val userResult: UserProfileSnapshotModel,
    ) : UserProfileSnapshotRepository {
        val currentAccounts = mutableListOf<String>()
        val userRequests = mutableListOf<Pair<String, Long>>()

        override suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel {
            currentAccounts += accountId
            return currentResult
        }

        override suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel {
            userRequests += accountId to userId
            return userResult
        }
    }

    private companion object {
        fun dialog(peerId: Long) = DialogSnapshotModel(
            peerId = peerId,
            peerType = DialogPeerType.PRIVATE,
            title = null,
            username = null,
            isPeerResolved = true,
            isPeerDeleted = false,
            isPeerForbidden = false,
            latestMessage = DialogMessagePreviewModel(
                messageId = peerId,
                senderId = peerId,
                date = 1,
                text = null,
                isService = false,
                isDeleted = false,
                isOutgoing = false,
                hasMedia = false,
            ),
        )

        fun profile(userId: Long) = UserProfileSnapshotModel(
            userId = userId,
            firstName = null,
            lastName = null,
            username = null,
            phoneNumber = null,
            isCurrentUser = false,
            isContact = false,
            isMutualContact = false,
            isDeleted = false,
            isBot = false,
            isVerified = false,
            isRestricted = false,
            isScam = false,
            isFake = false,
            isPremium = false,
            isPartial = false,
        )
    }
}
