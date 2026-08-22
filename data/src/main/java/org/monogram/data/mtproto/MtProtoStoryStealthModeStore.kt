package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.StoriesStealthMode_074c681db4
import org.monogram.mtproto.tl.generated.cloud.layer223.StoriesStealthMode_9a2f11feb7

@Serializable
internal data class MtProtoStoryStealthMode(
    val activeUntilDate: Int,
    val cooldownUntilDate: Int,
)

internal interface MtProtoStoryStealthModeStore {
    suspend fun save(scope: MtProtoAuthKeyScope, mode: StoriesStealthMode_074c681db4)
    suspend fun get(scope: MtProtoAuthKeyScope): MtProtoStoryStealthMode?
    fun observe(scope: MtProtoAuthKeyScope): Flow<MtProtoStoryStealthMode?>
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoStoryStealthModeStore : MtProtoStoryStealthModeStore {
    override suspend fun save(scope: MtProtoAuthKeyScope, mode: StoriesStealthMode_074c681db4) = Unit
    override suspend fun get(scope: MtProtoAuthKeyScope): MtProtoStoryStealthMode? = null
    override fun observe(scope: MtProtoAuthKeyScope): Flow<MtProtoStoryStealthMode?> = emptyFlow()
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class KeyValueMtProtoStoryStealthModeStore(
    private val keyValueDao: KeyValueDao,
) : MtProtoStoryStealthModeStore {
    override suspend fun save(scope: MtProtoAuthKeyScope, mode: StoriesStealthMode_074c681db4) {
        val supported = mode as? StoriesStealthMode_9a2f11feb7 ?: return
        val state = MtProtoStoryStealthMode(
            activeUntilDate = supported.activeUntilDate ?: 0,
            cooldownUntilDate = supported.cooldownUntilDate ?: 0,
        )
        keyValueDao.insertValue(KeyValueEntity(key(scope), Json.encodeToString(MtProtoStoryStealthMode.serializer(), state)))
    }

    override suspend fun get(scope: MtProtoAuthKeyScope): MtProtoStoryStealthMode? =
        keyValueDao.getValue(key(scope))?.value?.let { value ->
            runCatching { Json.decodeFromString(MtProtoStoryStealthMode.serializer(), value) }.getOrNull()
        }

    override fun observe(scope: MtProtoAuthKeyScope): Flow<MtProtoStoryStealthMode?> =
        keyValueDao.observeValue(key(scope)).map { entity ->
            entity?.value?.let { value ->
                runCatching { Json.decodeFromString(MtProtoStoryStealthMode.serializer(), value) }.getOrNull()
            }
        }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        require(ACCOUNT_SLOT.matches(accountSlot)) { "Invalid MTProto account slot" }
        keyValueDao.deleteValuesWithPrefix("$KEY_PREFIX${environment.storageName}_${accountSlot}_")
    }

    private fun key(scope: MtProtoAuthKeyScope): String {
        require(ACCOUNT_SLOT.matches(scope.accountSlot)) { "Invalid MTProto account slot" }
        return "$KEY_PREFIX${scope.environment.storageName}_${scope.accountSlot}_${scope.dcId}"
    }

    private companion object {
        const val KEY_PREFIX = "mtproto_story_stealth_v1_"
        val ACCOUNT_SLOT = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
