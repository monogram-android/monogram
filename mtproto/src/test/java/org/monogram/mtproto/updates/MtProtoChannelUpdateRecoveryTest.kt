package org.monogram.mtproto.updates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelMessagesFilterEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_1b7807fadc
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifferenceEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifferenceTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifference_0e9ef6e10a
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetChannelDifference
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoChannelUpdateRecoveryTest {
    @Test
    fun `applies final channel difference and completes with new pts`() = runBlocking {
        val calls = mutableListOf<TlMethod<*>>()
        val resolvedChannels = mutableListOf<Long>()
        val applied = mutableListOf<MtProtoChannelDifferenceBatch>()
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { method ->
                calls += method
                ChannelDifference_0e9ef6e10a(
                    final_ = true,
                    pts = 20,
                    timeout = null,
                    newMessages = emptyList(),
                    otherUpdates = emptyList(),
                    chats = emptyList(),
                    users = emptyList(),
                )
            },
            resolveChannel = { channelId ->
                resolvedChannels += channelId
                InputChannel_d22292516d(channelId, 77L)
            },
            applyBatch = { applied += it },
        )

        assertEquals(
            MtProtoChannelUpdateRecoveryResult.Completed(42L, 20),
            recovery.recover(42L, currentPts = 10),
        )
        assertEquals(listOf(42L), resolvedChannels)
        val request = calls.single() as GetChannelDifference
        assertEquals(false, request.force)
        assertEquals(InputChannel_d22292516d(42L, 77L), request.channel)
        assertEquals(ChannelMessagesFilterEmpty, request.filter)
        assertEquals(10, request.pts)
        assertEquals(
            listOf(MtProtoChannelDifferenceBatch(42L, 20, emptyList(), emptyList(), emptyList(), emptyList())),
            applied,
        )
    }

    @Test
    fun `advances through non-final slices until final`() = runBlocking {
        val results = ArrayDeque<TlObject>().apply {
            add(ChannelDifference_0e9ef6e10a(true, 15, null, emptyList(), emptyList(), emptyList(), emptyList()).copy(final_ = false))
            add(ChannelDifference_0e9ef6e10a(final_ = true, pts = 18, timeout = null, newMessages = emptyList(), otherUpdates = emptyList(), chats = emptyList(), users = emptyList()))
        }
        val requests = mutableListOf<GetChannelDifference>()
        val applied = mutableListOf<MtProtoChannelDifferenceBatch>()
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { method ->
                requests += method as GetChannelDifference
                results.removeFirst()
            },
            resolveChannel = { InputChannel_d22292516d(it, 1L) },
            applyBatch = { applied += it },
        )

        assertEquals(
            MtProtoChannelUpdateRecoveryResult.Completed(7L, 18),
            recovery.recover(7L, currentPts = 12),
        )
        assertEquals(listOf(12, 15), requests.map { it.pts })
        assertEquals(listOf(15, 18), applied.map { it.pts })
    }

    @Test
    fun `completes through empty difference with server pts`() = runBlocking {
        val applied = mutableListOf<MtProtoChannelDifferenceBatch>()
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor {
                ChannelDifferenceEmpty(final_ = true, pts = 30, timeout = null)
            },
            resolveChannel = { InputChannel_d22292516d(it, 1L) },
            applyBatch = { applied += it },
        )

        assertEquals(
            MtProtoChannelUpdateRecoveryResult.Completed(9L, 30),
            recovery.recover(9L, currentPts = 25),
        )
        assertEquals(
            listOf(MtProtoChannelDifferenceBatch(9L, 30, emptyList(), emptyList(), emptyList(), emptyList())),
            applied,
        )
    }

    @Test
    fun `surfaces too-long as resync without applying`() = runBlocking {
        var appliedCount = 0
        val unusedDialog = org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd(
            pinned = false,
            unreadMark = false,
            viewForumAsMessages = false,
            peer = org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel(5L),
            topMessage = 0,
            readInboxMaxId = 0,
            readOutboxMaxId = 0,
            unreadCount = 0,
            unreadMentionsCount = 0,
            unreadReactionsCount = 0,
            notifySettings = org.monogram.mtproto.tl.generated.cloud.layer223.PeerNotifySettings_474d6bbc59(
                null, null, null, null, null, null, null, null, null, null, null,
            ),
            pts = null,
            draft = null,
            folderId = null,
            ttlPeriod = null,
        )
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { ChannelDifferenceTooLong(true, null, unusedDialog, emptyList(), emptyList(), emptyList()) },
            resolveChannel = { InputChannel_d22292516d(it, 1L) },
            applyBatch = { appliedCount++ },
        )

        assertEquals(MtProtoChannelUpdateRecoveryResult.ResyncRequired, recovery.recover(5L, currentPts = 4))
        assertEquals(0, appliedCount)
    }

    @Test
    fun `bounds a server that never finishes slices`() = runBlocking {
        var differenceCalls = 0
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor {
                differenceCalls++
                ChannelDifference_0e9ef6e10a(
                    final_ = false,
                    pts = 100 + differenceCalls,
                    timeout = null,
                    newMessages = emptyList(),
                    otherUpdates = emptyList(),
                    chats = emptyList(),
                    users = emptyList(),
                )
            },
            resolveChannel = { InputChannel_d22292516d(it, 1L) },
            applyBatch = {},
            maxDifferenceBatches = 2,
        )

        assertEquals(MtProtoChannelUpdateRecoveryResult.ResyncRequired, recovery.recover(3L, currentPts = 99))
        assertEquals(2, differenceCalls)
    }

    @Test
    fun `fails closed when the channel cannot be resolved`() = runBlocking {
        var appliedCount = 0
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { error("unexpected request") },
            resolveChannel = { null },
            applyBatch = { appliedCount++ },
        )

        assertEquals(MtProtoChannelUpdateRecoveryResult.ResyncRequired, recovery.recover(8L, currentPts = 2))
        assertEquals(0, appliedCount)
    }

    @Test
    fun `rejects invalid channel ids and pts`() {
        val recovery = MtProtoChannelUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { error("unexpected request") },
            resolveChannel = { error("unexpected resolution") },
            applyBatch = {},
        )

        assertThrows(IllegalArgumentException::class.java) { runBlocking { recovery.recover(0L, currentPts = 1) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { recovery.recover(1L, currentPts = -1) } }
    }
}
