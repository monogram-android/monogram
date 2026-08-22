package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoPendingEnvelopeDao
import org.monogram.data.db.model.MtProtoPendingEnvelopeEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.runtime.TlCodecException

internal sealed interface MtProtoPendingEnvelope {
    val sequenceId: Long

    data class Decoded(
        override val sequenceId: Long,
        val envelope: Updates_faf6aaa3d5,
    ) : MtProtoPendingEnvelope

    data class Corrupt(override val sequenceId: Long) : MtProtoPendingEnvelope
}

internal interface MtProtoPendingEnvelopeStore {
    suspend fun enqueue(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5): MtProtoPendingEnvelope.Decoded
    suspend fun pending(scope: MtProtoAuthKeyScope): List<MtProtoPendingEnvelope>
    suspend fun delete(sequenceId: Long)

    /** Drops every durable envelope for one account/DC scope; used by full-resync recovery. */
    suspend fun deleteScope(scope: MtProtoAuthKeyScope)

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal class MtProtoRoomPendingEnvelopeStore(
    private val dao: MtProtoPendingEnvelopeDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoPendingEnvelopeStore {
    override suspend fun enqueue(
        scope: MtProtoAuthKeyScope,
        envelope: Updates_faf6aaa3d5,
    ): MtProtoPendingEnvelope.Decoded {
        val payload = CloudTlObjectCodec.encode(envelope)
        val payloadHash = MtProtoPayloadHash.sha256(payload)
        val insertedId = dao.insert(
            MtProtoPendingEnvelopeEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                dcId = scope.dcId,
                payloadHash = payloadHash,
                payload = payload,
                createdAt = nowMillis(),
            )
        )
        val sequenceId = if (insertedId != INSERT_CONFLICT) {
            insertedId
        } else {
            val existing = checkNotNull(
                dao.getByHash(
                    scope.accountSlot,
                    scope.environment.storageName,
                    scope.dcId,
                    payloadHash,
                )
            ) { "Pending MTProto envelope conflict without an existing row" }
            check(existing.payload.contentEquals(payload)) { "Pending MTProto envelope hash collision" }
            existing.sequenceId
        }
        return MtProtoPendingEnvelope.Decoded(sequenceId, envelope)
    }

    override suspend fun pending(scope: MtProtoAuthKeyScope): List<MtProtoPendingEnvelope> =
        dao.getPending(scope.accountSlot, scope.environment.storageName, scope.dcId).map { entity ->
            val envelope = try {
                CloudTlObjectCodec.decode(entity.payload) as? Updates_faf6aaa3d5
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: TlCodecException) {
                null
            }
            envelope?.let { MtProtoPendingEnvelope.Decoded(entity.sequenceId, it) }
                ?: MtProtoPendingEnvelope.Corrupt(entity.sequenceId)
        }

    override suspend fun delete(sequenceId: Long) = dao.delete(sequenceId)

    override suspend fun deleteScope(scope: MtProtoAuthKeyScope) =
        dao.deleteScope(scope.accountSlot, scope.environment.storageName, scope.dcId)

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dao.deleteAccount(accountSlot, environment.storageName)

    private companion object {
        const val INSERT_CONFLICT = -1L
    }
}
