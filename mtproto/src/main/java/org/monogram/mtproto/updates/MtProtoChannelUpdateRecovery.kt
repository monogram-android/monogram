package org.monogram.mtproto.updates

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelMessagesFilterEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_1b7807fadc
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4
import org.monogram.mtproto.tl.generated.cloud.layer223.Update
import org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifferenceEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifferenceTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.ChannelDifference_0e9ef6e10a
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetChannelDifference
import org.monogram.mtproto.tl.runtime.TlObject

data class MtProtoChannelDifferenceBatch(
    val channelId: Long,
    val pts: Int,
    val newMessages: List<Message_73e57f95e4>,
    val otherUpdates: List<Update>,
    val chats: List<Chat_7fdd7beb6e>,
    val users: List<User_655b5dfc57>,
)

sealed interface MtProtoChannelUpdateRecoveryResult {
    data class Completed(
        val channelId: Long,
        val pts: Int,
    ) : MtProtoChannelUpdateRecoveryResult

    /** The channel gap exceeded the retrievable difference; a full resync must repair it. */
    data object ResyncRequired : MtProtoChannelUpdateRecoveryResult
}

/**
 * Serialized `updates.getChannelDifference` recovery for one channel pts gap.
 *
 * Bounded slice loops fail closed with [MtProtoChannelUpdateRecoveryResult.ResyncRequired]
 * instead of looping indefinitely; persistence belongs to the apply callback.
 */
class MtProtoChannelUpdateRecovery(
    private val executor: MtProtoUpdateRecoveryExecutor,
    private val resolveChannel: suspend (Long) -> InputChannel_1b7807fadc?,
    private val applyBatch: suspend (MtProtoChannelDifferenceBatch) -> Unit,
    private val maxDifferenceBatches: Int = DEFAULT_MAX_DIFFERENCE_BATCHES,
) {
    init {
        require(maxDifferenceBatches in 1..MAX_ALLOWED_DIFFERENCE_BATCHES) {
            "maxDifferenceBatches must be within 1..$MAX_ALLOWED_DIFFERENCE_BATCHES"
        }
    }

    private val mutex = Mutex()

    suspend fun recover(channelId: Long, currentPts: Int): MtProtoChannelUpdateRecoveryResult =
        mutex.withLock {
            require(channelId > 0) { "channelId must be positive" }
            require(currentPts >= 0) { "currentPts must not be negative" }
            var pts = currentPts
            var batchCount = 0
            while (true) {
                if (batchCount++ >= maxDifferenceBatches) {
                    return@withLock MtProtoChannelUpdateRecoveryResult.ResyncRequired
                }
                val channel = resolveChannel(channelId)
                    ?: return@withLock MtProtoChannelUpdateRecoveryResult.ResyncRequired
                when (val result = executor.execute(GetChannelDifference(
                    force = false,
                    channel = channel,
                    filter = ChannelMessagesFilterEmpty,
                    pts = pts,
                    limit = DEFAULT_LIMIT,
                ))) {
                    is ChannelDifference_0e9ef6e10a -> {
                        pts = result.pts
                        applyBatch(result.toBatch(channelId, pts))
                        if (result.final_) {
                            return@withLock MtProtoChannelUpdateRecoveryResult.Completed(channelId, pts)
                        }
                    }
                    is ChannelDifferenceEmpty -> {
                        applyBatch(emptyBatch(channelId, result.pts))
                        return@withLock MtProtoChannelUpdateRecoveryResult.Completed(channelId, result.pts)
                    }
                    is ChannelDifferenceTooLong ->
                        return@withLock MtProtoChannelUpdateRecoveryResult.ResyncRequired
                }
            }
            error("MTProto channel update recovery did not produce a result")
        }

    private fun ChannelDifference_0e9ef6e10a.toBatch(channelId: Long, pts: Int) =
        MtProtoChannelDifferenceBatch(channelId, pts, newMessages, otherUpdates, chats, users)

    private fun emptyBatch(channelId: Long, pts: Int) = MtProtoChannelDifferenceBatch(
        channelId, pts, emptyList(), emptyList(), emptyList(), emptyList(),
    )

    private companion object {
        const val DEFAULT_MAX_DIFFERENCE_BATCHES = 64
        const val MAX_ALLOWED_DIFFERENCE_BATCHES = 512
        const val DEFAULT_LIMIT = 100
    }
}
