package org.monogram.core.database

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class MessageReadOwnershipTest {
    private val message = Message(MessageId(PeerId(7), 37), null, null, 100, outgoing = false)

    @Test
    fun accountDeactivationJoinsReadPersistenceAndRejectsOldOwnerAfterReactivation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val warmup = object : OfflineWarmup() {
            override suspend fun upsertMessages(messages: List<Message>) {
                entered.complete(Unit)
                release.await()
                writes++
            }
        }
        val epoch = warmup.outgoingAccountEpoch()
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            warmup.applyMessageRead(
                epoch,
                PeerId(7),
                37,
                message
            )
        }
        entered.await()
        val deactivate =
            async(start = CoroutineStart.UNDISPATCHED) { warmup.deactivateOutgoingAccount() }
        assertFalse(deactivate.isCompleted)
        release.complete(Unit)
        assertTrue(read.await())
        deactivate.await()
        warmup.activateOutgoingAccount()
        assertFalse(warmup.applyMessageRead(epoch, PeerId(7), 37, message))
        assertEquals(1, writes)
    }

    @Test
    fun deletedReadPersistsOnlyTheRequestedIdentity() = runBlocking {
        val deletions = mutableListOf<Pair<PeerId?, List<Int>>>()
        val warmup = object : OfflineWarmup() {
            override suspend fun deleteMessages(chatId: PeerId?, messageIds: Collection<Int>) {
                deletions += chatId to messageIds.toList()
            }
        }
        assertTrue(warmup.applyMessageRead(warmup.outgoingAccountEpoch(), PeerId(7), 37, null))
        assertEquals(listOf(PeerId(7) to listOf(37)), deletions)
        assertTrue(runCatching {
            warmup.applyMessageRead(
                warmup.outgoingAccountEpoch(),
                PeerId(8),
                37,
                message
            )
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, deletions.size)
    }
}
