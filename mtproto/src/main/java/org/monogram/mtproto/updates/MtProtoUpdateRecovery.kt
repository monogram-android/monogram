package org.monogram.mtproto.updates

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.Difference_2f53482c4e
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.Difference_b2cf6c3ff7
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetDifference
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetState
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.State_ddba9d7af9
import org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedMessage_a850d27596
import org.monogram.mtproto.tl.generated.cloud.layer223.Update
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e
import org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

data class MtProtoUpdateCursor(
    val pts: Int,
    val qts: Int,
    val date: Int,
    val seq: Int,
)

data class MtProtoUpdateDifferenceBatch(
    val newMessages: List<Message_73e57f95e4>,
    val newEncryptedMessages: List<EncryptedMessage_a850d27596>,
    val otherUpdates: List<Update>,
    val chats: List<Chat_7fdd7beb6e>,
    val users: List<User_655b5dfc57>,
    val cursor: MtProtoUpdateCursor,
)

sealed interface MtProtoUpdateRecoveryResult {
    data class Completed(val cursor: MtProtoUpdateCursor) : MtProtoUpdateRecoveryResult
    data object ResyncRequired : MtProtoUpdateRecoveryResult
}

fun interface MtProtoUpdateRecoveryExecutor {
    suspend fun execute(method: TlMethod<*>): TlObject
}

/** Serialized getState/getDifference coordinator; persistence belongs to the apply callback. */
class MtProtoUpdateRecovery(
    private val executor: MtProtoUpdateRecoveryExecutor,
    private val applyBatch: suspend (MtProtoUpdateDifferenceBatch) -> Unit,
    initialCursor: MtProtoUpdateCursor? = null,
    private val maxDifferenceBatches: Int = DEFAULT_MAX_DIFFERENCE_BATCHES,
) {
    init {
        require(maxDifferenceBatches in 1..MAX_ALLOWED_DIFFERENCE_BATCHES) {
            "maxDifferenceBatches must be within 1..$MAX_ALLOWED_DIFFERENCE_BATCHES"
        }
    }
    private val mutex = Mutex()
    @Volatile
    private var cursor: MtProtoUpdateCursor? = initialCursor

    fun currentCursor(): MtProtoUpdateCursor? = cursor

    suspend fun initialize(): MtProtoUpdateCursor = mutex.withLock {
        cursor?.let { return@withLock it }
        val state = executor.execute(GetState).asState()
        val initial = state.toCursor()
        applyBatch(emptyBatch(initial))
        initial.also { cursor = it }
    }

    suspend fun handle(update: Updates_faf6aaa3d5): MtProtoUpdateRecoveryResult? =
        if (update === UpdatesTooLong) recover() else null

    suspend fun recover(): MtProtoUpdateRecoveryResult = mutex.withLock {
        var current = cursor ?: error("MTProto update recovery is not initialized")
        var batchCount = 0
        while (true) {
            if (batchCount++ >= maxDifferenceBatches) {
                return@withLock MtProtoUpdateRecoveryResult.ResyncRequired
            }
            val result = executor.execute(
                GetDifference(
                    pts = current.pts,
                    ptsLimit = null,
                    ptsTotalLimit = null,
                    date = current.date,
                    qts = current.qts,
                    qtsLimit = null,
                ),
            ).asDifference()
            when (result) {
                is Difference_2f53482c4e -> {
                    val next = result.state.asState().toCursor()
                    applyBatch(result.toBatch(next))
                    current = next
                    cursor = current
                    return@withLock MtProtoUpdateRecoveryResult.Completed(current)
                }
                is DifferenceSlice -> {
                    val next = result.intermediateState.asState().toCursor()
                    applyBatch(result.toBatch(next))
                    current = next
                    cursor = current
                }
                is DifferenceEmpty -> {
                    val next = current.copy(date = result.date, seq = result.seq)
                    applyBatch(emptyBatch(next))
                    current = next
                    cursor = current
                    return@withLock MtProtoUpdateRecoveryResult.Completed(current)
                }
                is DifferenceTooLong -> return@withLock MtProtoUpdateRecoveryResult.ResyncRequired
            }
        }
        error("MTProto update recovery did not produce a result")
    }

    private fun TlObject.asState(): State_ddba9d7af9 = this as? State_ddba9d7af9
        ?: error("Unsupported MTProto updates state: ${constructorId}")

    private fun TlObject.asDifference(): Difference_b2cf6c3ff7 = this as? Difference_b2cf6c3ff7
        ?: error("Unsupported MTProto updates difference: ${constructorId}")

    private fun State_ddba9d7af9.toCursor() = MtProtoUpdateCursor(pts, qts, date, seq)

    private fun Difference_2f53482c4e.toBatch(cursor: MtProtoUpdateCursor) = MtProtoUpdateDifferenceBatch(
        newMessages, newEncryptedMessages, otherUpdates, chats, users, cursor,
    )

    private fun DifferenceSlice.toBatch(cursor: MtProtoUpdateCursor) = MtProtoUpdateDifferenceBatch(
        newMessages, newEncryptedMessages, otherUpdates, chats, users, cursor,
    )

    private fun emptyBatch(cursor: MtProtoUpdateCursor) =
        MtProtoUpdateDifferenceBatch(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), cursor)

    private companion object {
        const val DEFAULT_MAX_DIFFERENCE_BATCHES = 128
        const val MAX_ALLOWED_DIFFERENCE_BATCHES = 1_024
    }
}
