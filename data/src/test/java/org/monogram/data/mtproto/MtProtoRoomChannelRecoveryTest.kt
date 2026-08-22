package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.updates.MtProtoChannelUpdateRecoveryResult
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch
import org.monogram.mtproto.updates.MtProtoUpdateState
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifference_0e9ef6e10a
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetChannelDifference
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoRoomChannelRecoveryTest {
    @Test
    fun `repairs channel pts gap through channel difference and persists new pts`() = runBlocking {
        val requests = mutableListOf<GetChannelDifference>()
        val transport = object : org.monogram.mtproto.transport.MtProtoRpcTransport {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R = when (method) {
                is GetChannelDifference -> {
                    requests += method
                    ChannelDifference_0e9ef6e10a(
                        final_ = true,
                        pts = 20,
                        timeout = null,
                        newMessages = emptyList(),
                        otherUpdates = emptyList(),
                        chats = emptyList(),
                        users = emptyList(),
                    ) as R
                }
                else -> error("unexpected method ${method::class.java.simpleName}")
            }
            override fun close() = Unit
        }
        val stateStore = FakeStateStore(
            MtProtoUpdateState(MtProtoUpdateCursor(5, 6, 7, 8), channelPts = mapOf(42L to 10)),
        )
        val chats = FakeChatProjectionStore(accessHashes = mapOf(42L to 77L))
        val recovery = MtProtoRoomUpdateRecovery(stateStore, MtProtoRoomLiveUpdateApplier(stateStore, NoOpPendingStore), chatProjectionStore = chats)
        val session = when (val opened = recovery.open(SCOPE, transport) { }) {
            is MtProtoRoomRecoveryOpenResult.Opened -> opened.session
            MtProtoRoomRecoveryOpenResult.CorruptState -> error("state should be usable")
        }

        assertEquals(
            MtProtoChannelUpdateRecoveryResult.Completed(42L, 20),
            session.recoverChannel(42L),
        )
        assertEquals(listOf(InputChannel_d22292516d(42L, 77L)), requests.map { it.channel })
        assertEquals(listOf(10), requests.map { it.pts })
        assertEquals(20, stateStore.state?.channelPts?.get(42L))
    }

    @Test
    fun `fails closed when the gapped channel has no cached access hash`() = runBlocking {
        var requested = false
        val transport = object : org.monogram.mtproto.transport.MtProtoRpcTransport {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(method: TlMethod<R>): R {
                requested = true
                error("no request expected")
            }
            override fun close() = Unit
        }
        val stateStore = FakeStateStore(
            MtProtoUpdateState(MtProtoUpdateCursor(5, 6, 7, 8), channelPts = mapOf(42L to 10)),
        )
        val recovery = MtProtoRoomUpdateRecovery(stateStore, MtProtoRoomLiveUpdateApplier(stateStore, NoOpPendingStore))
        val session = when (val opened = recovery.open(SCOPE, transport) { }) {
            is MtProtoRoomRecoveryOpenResult.Opened -> opened.session
            MtProtoRoomRecoveryOpenResult.CorruptState -> error("state should be usable")
        }

        assertEquals(
            MtProtoChannelUpdateRecoveryResult.ResyncRequired,
            session.recoverChannel(42L),
        )
        assertEquals(false, requested)
    }

    private companion object {
        val SCOPE = MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)
    }

    private class FakeStateStore(initial: MtProtoUpdateState?) : MtProtoTransactionalUpdateStateStore {
        var state: MtProtoUpdateState? = initial

        override suspend fun loadState(scope: MtProtoAuthKeyScope) = state
            ?.let(MtProtoUpdateStateLoadResult::Found)
            ?: MtProtoUpdateStateLoadResult.Missing

        override suspend fun applyState(
            scope: MtProtoAuthKeyScope,
            state: MtProtoUpdateState,
            applyEntities: suspend () -> Unit,
        ) {
            applyEntities()
            this.state = state
        }
    }

    private class FakeChatProjectionStore(
        private val accessHashes: Map<Long, Long>,
    ) : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long): MtProtoChatReadModel? =
            accessHashes[chatId]?.let { hash ->
                MtProtoChatReadModel(
                    chatId = chatId,
                    type = MtProtoChatType.CHANNEL,
                    accessHash = hash,
                    title = "channel",
                    username = null,
                    participantsCount = null,
                    isDeleted = false,
                    isForbidden = false,
                    isLeft = false,
                    isDeactivated = false,
                    isVerified = false,
                    isRestricted = false,
                    isScam = false,
                    isFake = false,
                    isForum = false,
                    signaturesEnabled = false,
                    signatureProfilesEnabled = false,
                    forumTabs = false,
                    isMin = false,
                )
            }
    }

    private object NoOpPendingStore : MtProtoPendingEnvelopeStore {
        override suspend fun enqueue(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5): MtProtoPendingEnvelope.Decoded =
            error("no live envelope expected")

        override suspend fun pending(scope: MtProtoAuthKeyScope) = emptyList<MtProtoPendingEnvelope>()
        override suspend fun delete(sequenceId: Long) = Unit
        override suspend fun deleteScope(scope: MtProtoAuthKeyScope) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }
}
