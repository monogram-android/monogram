package org.monogram.data.mtproto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

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
) : AutoCloseable {
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
        ): StoredMtProtoAuthKey {
            require(material.size == MATERIAL_BYTES) { "MTProto auth key must contain 256 bytes" }
            require(authKeyId(material) == id) { "MTProto auth key ID mismatch" }
            return StoredMtProtoAuthKey(material, id, serverSalt, authKeyCreatedAt, serverTimeAnchorSeconds)
        }

        fun calculateId(material: ByteArray): Long {
            require(material.size == MATERIAL_BYTES) { "MTProto auth key must contain 256 bytes" }
            return authKeyId(material)
        }

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
    private const val VERSION = 2
    private const val VERSION_1_ENCODED_BYTES = 4 + 4 + 8 + 8 + 4 + 4 + StoredMtProtoAuthKey.MATERIAL_BYTES
    private const val ENCODED_BYTES = 4 + 4 + 8 + 8 + 4 + 4 + 4 + StoredMtProtoAuthKey.MATERIAL_BYTES

    fun encode(authKey: StoredMtProtoAuthKey): ByteArray {
        val material = authKey.copyMaterial()
        return try {
            ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(MAGIC)
                putInt(VERSION)
                putLong(authKey.id)
                putLong(authKey.serverSalt)
                putInt(authKey.authKeyCreatedAt)
                putInt(authKey.serverTimeAnchorSeconds)
                putInt(material.size)
                put(material)
            }.array()
        } finally {
            material.fill(0)
        }
    }

    fun decode(bytes: ByteArray): StoredMtProtoAuthKey {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Invalid MTProto auth key record magic" }
        val version = buffer.int
        require(version == VERSION_1 || version == VERSION) { "Unsupported MTProto auth key record version" }
        require(bytes.size == if (version == VERSION_1) VERSION_1_ENCODED_BYTES else ENCODED_BYTES) {
            "Invalid MTProto auth key record length"
        }
        val id = buffer.long
        val serverSalt = buffer.long
        val authKeyCreatedAt = buffer.int
        val serverTimeAnchorSeconds = if (version == VERSION_1) authKeyCreatedAt else buffer.int
        require(buffer.int == StoredMtProtoAuthKey.MATERIAL_BYTES) { "Invalid MTProto auth key material length" }
        val material = ByteArray(StoredMtProtoAuthKey.MATERIAL_BYTES)
        buffer.get(material)
        return try {
            StoredMtProtoAuthKey.create(material, id, serverSalt, authKeyCreatedAt, serverTimeAnchorSeconds)
        } finally {
            material.fill(0)
        }
    }
}
