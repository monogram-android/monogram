package org.monogram.data.mtproto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.monogram.mtproto.transport.MtProtoFutureSalt

internal enum class MtProtoEnvironment(val storageName: String) {
    PRODUCTION("prod"),
    TEST("test"),
}

internal data class MtProtoAuthKeyScope(
    val accountSlot: String,
    val environment: MtProtoEnvironment,
    val dcId: Int,
) {
    init {
        require(ACCOUNT_SLOT.matches(accountSlot)) { "Invalid MTProto account slot" }
        require(dcId in 1..1_000) { "dcId must be within 1..1000" }
    }

    val storageKey: String = "v1_${environment.storageName}_${accountSlot}_dc$dcId"

    private companion object {
        val ACCOUNT_SLOT = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

internal class StoredMtProtoAuthKey private constructor(
    material: ByteArray,
    val id: Long,
    val serverSalt: Long,
    val authKeyCreatedAt: Int,
    val serverTimeAnchorSeconds: Int,
    futureSalts: List<MtProtoFutureSalt> = emptyList(),
) : AutoCloseable {
    val futureSalts: List<MtProtoFutureSalt> = futureSalts.toList()
    /** Legacy name retained only for callers that need the immutable key-establishment time. */
    val createdAt: Int get() = authKeyCreatedAt
    private val key = material.copyOf()
    private var closed = false

    fun copyMaterial(): ByteArray {
        check(!closed) { "Stored MTProto auth key has been closed" }
        return key.copyOf()
    }

    override fun close() {
        key.fill(0)
        closed = true
    }

    companion object {
        const val MATERIAL_BYTES = 256

        fun create(
            material: ByteArray,
            id: Long,
            serverSalt: Long,
            authKeyCreatedAt: Int,
            serverTimeAnchorSeconds: Int = authKeyCreatedAt,
            futureSalts: List<MtProtoFutureSalt> = emptyList(),
        ): StoredMtProtoAuthKey {
            require(material.size == MATERIAL_BYTES) { "MTProto auth key must contain 256 bytes" }
            require(authKeyId(material) == id) { "MTProto auth key ID mismatch" }
            require(futureSalts.size <= MAX_FUTURE_SALTS) { "Too many MTProto future salts" }
            return StoredMtProtoAuthKey(material, id, serverSalt, authKeyCreatedAt, serverTimeAnchorSeconds, futureSalts)
        }

        fun calculateId(material: ByteArray): Long {
            require(material.size == MATERIAL_BYTES) { "MTProto auth key must contain 256 bytes" }
            return authKeyId(material)
        }

        private const val MAX_FUTURE_SALTS = 64

        private fun authKeyId(material: ByteArray): Long {
            val digest = MessageDigest.getInstance("SHA-1").digest(material)
            return try {
                ByteBuffer.wrap(digest, digest.size - Long.SIZE_BYTES, Long.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .long
            } finally {
                digest.fill(0)
            }
        }
    }
}

internal sealed interface MtProtoAuthKeyLoadResult {
    data object Missing : MtProtoAuthKeyLoadResult
    data object Corrupt : MtProtoAuthKeyLoadResult
    data class Found(val authKey: StoredMtProtoAuthKey) : MtProtoAuthKeyLoadResult
}

internal interface MtProtoAuthKeyStore {
    suspend fun load(scope: MtProtoAuthKeyScope): MtProtoAuthKeyLoadResult
    suspend fun save(scope: MtProtoAuthKeyScope, authKey: StoredMtProtoAuthKey)
    suspend fun delete(scope: MtProtoAuthKeyScope)
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object MtProtoAuthKeyRecordCodec {
    private const val MAGIC = 0x4d54414b
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2
    private const val VERSION = 3
    private const val VERSION_1_ENCODED_BYTES = 4 + 4 + 8 + 8 + 4 + 4 + StoredMtProtoAuthKey.MATERIAL_BYTES
    private const val VERSION_2_ENCODED_BYTES = 4 + 4 + 8 + 8 + 4 + 4 + 4 + StoredMtProtoAuthKey.MATERIAL_BYTES
    private const val HEADER_BYTES = 4 + 4 + 8 + 8 + 4 + 4 + 4 + 4
    private const val MAX_FUTURE_SALTS = 64

    fun encode(authKey: StoredMtProtoAuthKey): ByteArray {
        val material = authKey.copyMaterial()
        return try {
            ByteBuffer.allocate(HEADER_BYTES + material.size + authKey.futureSalts.size * (4 + 4 + 8))
                .order(ByteOrder.BIG_ENDIAN)
                .apply {
                    putInt(MAGIC)
                    putInt(VERSION)
                    putLong(authKey.id)
                    putLong(authKey.serverSalt)
                    putInt(authKey.authKeyCreatedAt)
                    putInt(authKey.serverTimeAnchorSeconds)
                    putInt(material.size)
                    putInt(authKey.futureSalts.size)
                    put(material)
                    authKey.futureSalts.forEach { salt ->
                        putInt(salt.validSince)
                        putInt(salt.validUntil)
                        putLong(salt.value)
                    }
                }
                .array()
        } finally {
            material.fill(0)
        }
    }

    fun decode(bytes: ByteArray): StoredMtProtoAuthKey {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Invalid MTProto auth key record magic" }
        val version = buffer.int
        require(version == VERSION_1 || version == VERSION_2 || version == VERSION) { "Unsupported MTProto auth key record version" }
        if (version != VERSION) {
            require(bytes.size == if (version == VERSION_1) VERSION_1_ENCODED_BYTES else VERSION_2_ENCODED_BYTES) {
                "Invalid MTProto auth key record length"
            }
        } else {
            require(bytes.size >= HEADER_BYTES + StoredMtProtoAuthKey.MATERIAL_BYTES) {
                "Invalid MTProto auth key record length"
            }
        }
        val id = buffer.long
        val serverSalt = buffer.long
        val authKeyCreatedAt = buffer.int
        val serverTimeAnchorSeconds = if (version == VERSION_1) authKeyCreatedAt else buffer.int
        val materialLength = buffer.int
        require(materialLength == StoredMtProtoAuthKey.MATERIAL_BYTES) { "Invalid MTProto auth key material length" }
        val futureSaltCount = if (version == VERSION) buffer.int else 0
        require(futureSaltCount in 0..MAX_FUTURE_SALTS) { "Invalid MTProto future salt count" }
        require(version != VERSION || buffer.remaining() == materialLength + futureSaltCount * (4 + 4 + 8)) {
            "Invalid MTProto auth key record length"
        }
        val material = ByteArray(StoredMtProtoAuthKey.MATERIAL_BYTES)
        buffer.get(material)
        val futureSalts = buildList(futureSaltCount) {
            repeat(futureSaltCount) { add(MtProtoFutureSalt(buffer.int, buffer.int, buffer.long)) }
        }
        return try {
            StoredMtProtoAuthKey.create(material, id, serverSalt, authKeyCreatedAt, serverTimeAnchorSeconds, futureSalts)
        } finally {
            material.fill(0)
        }
    }
}
