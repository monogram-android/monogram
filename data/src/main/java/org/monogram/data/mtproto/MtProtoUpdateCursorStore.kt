package org.monogram.data.mtproto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.monogram.mtproto.updates.MtProtoUpdateCursor

internal sealed interface MtProtoUpdateCursorLoadResult {
    data object Missing : MtProtoUpdateCursorLoadResult
    data object Corrupt : MtProtoUpdateCursorLoadResult
    data class Found(val cursor: MtProtoUpdateCursor) : MtProtoUpdateCursorLoadResult
}

internal interface MtProtoUpdateCursorStore {
    suspend fun load(scope: MtProtoAuthKeyScope): MtProtoUpdateCursorLoadResult
    suspend fun save(scope: MtProtoAuthKeyScope, cursor: MtProtoUpdateCursor)
    suspend fun delete(scope: MtProtoAuthKeyScope)
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object MtProtoUpdateCursorCodec {
    private const val MAGIC = 0x4d545543
    private const val VERSION = 1
    private const val ENCODED_BYTES = 4 + 4 + (4 * Int.SIZE_BYTES)

    fun encode(cursor: MtProtoUpdateCursor): ByteArray = ByteBuffer
        .allocate(ENCODED_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            putInt(MAGIC)
            putInt(VERSION)
            putInt(cursor.pts)
            putInt(cursor.qts)
            putInt(cursor.date)
            putInt(cursor.seq)
        }
        .array()

    fun decode(bytes: ByteArray): MtProtoUpdateCursor {
        require(bytes.size == ENCODED_BYTES) { "Invalid MTProto update cursor length" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Invalid MTProto update cursor magic" }
        require(buffer.int == VERSION) { "Unsupported MTProto update cursor version" }
        return MtProtoUpdateCursor(
            pts = buffer.int,
            qts = buffer.int,
            date = buffer.int,
            seq = buffer.int,
        )
    }
}
