package org.monogram.data.mtproto

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository

class MtProtoChatSearchRepositoryTest {
    @Test
    fun `searches projected dialogs by title and username case insensitively`() = runBlocking {
        val repository = MtProtoChatSearchRepository(FakeDialogRepository())

        val results = repository.searchChats("ALICE")

        assertEquals(1, results.size)
        assertEquals("Alice", results.single().title)
        assertEquals(4, results.single().unreadCount)
    }

    @Test
    fun `unsupported search operations fail closed`(): Unit = runBlocking {
        val repository = MtProtoChatSearchRepository(FakeDialogRepository())

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.searchPublicChats("alice") }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { repository.searchMessages("hello") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.searchMessages("hello", offset = "not-a-number") }
        }
    }

    private class FakeDialogRepository : DialogSnapshotRepository {
        override suspend fun getDialogs(accountId: String) = listOf(
            DialogSnapshotModel(
                peerId = 42,
                peerType = DialogPeerType.PRIVATE,
                title = "Alice",
                username = "alice_user",
                isPeerResolved = true,
                isPeerDeleted = false,
                isPeerForbidden = false,
                latestMessage = DialogMessagePreviewModel(7, 42, 100, "hello", false, false, false, false),
                unreadCount = 4,
            ),
            DialogSnapshotModel(
                peerId = 43,
                peerType = DialogPeerType.PRIVATE,
                title = "Bob",
                username = "bob_user",
                isPeerResolved = true,
                isPeerDeleted = false,
                isPeerForbidden = false,
                latestMessage = DialogMessagePreviewModel(8, 43, 101, "world", false, false, false, false),
            ),
        )
    }
}
