package org.monogram.data.mtproto

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.data.db.dao.MtProtoPollDao
import org.monogram.data.db.model.MtProtoPollEntity

/** Full poll payload staged for conversation rendering. */
internal data class MtProtoPollPayload(
    val pollId: Long,
    val question: String,
    val options: List<String>,
    /** Voter counts parallel to [options]; zeros when results are unavailable. */
    val voterCounts: List<Int>,
    /** Chosen flags parallel to [options]. */
    val chosenFlags: List<Boolean>,
    val totalVoters: Int,
    val isClosed: Boolean,
    val isAnonymous: Boolean,
)

@Serializable
private data class PollOptionDto(val text: String, val voterCount: Int = 0, val isChosen: Boolean = false)

/** Poll payload storage used by message staging and conversation rendering. */
internal data class MtProtoPollVoterInfo(val voterCount: Int, val isChosen: Boolean)

internal interface MtProtoPollPayloadStore {
    suspend fun upsert(
        pollId: Long,
        question: String,
        optionLabels: List<String>,
        totalVoters: Int,
        isClosed: Boolean,
        isAnonymous: Boolean,
        /** Per-option voter counts/chosen flags keyed by the raw option bytes. */
        voterCountsByOption: Map<List<Byte>, MtProtoPollVoterInfo> = emptyMap(),
    )

    suspend fun get(pollId: Long): MtProtoPollPayload?

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

/** Room-backed poll payload store; options are persisted as a JSON array of labels. */
internal class MtProtoRoomPollStore(
    private val dao: MtProtoPollDao,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
    private val environment: MtProtoEnvironment = MtProtoEnvironment.PRODUCTION,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoPollPayloadStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upsert(
        pollId: Long,
        question: String,
        optionLabels: List<String>,
        totalVoters: Int,
        isClosed: Boolean,
        isAnonymous: Boolean,
        voterCountsByOption: Map<List<Byte>, MtProtoPollVoterInfo>,
    ) = withContext(Dispatchers.IO) {
        dao.upsert(
            MtProtoPollEntity(
                accountSlot = accountSlot,
                environment = environment.storageName,
                pollId = pollId,
                question = question,
                optionsJson = json.encodeToString(
                    ListSerializer(PollOptionDto.serializer()),
                    optionLabels.map { PollOptionDto(it) },
                ),
                totalVoters = totalVoters,
                isClosed = isClosed,
                isAnonymous = isAnonymous,
                updatedAt = nowMillis(),
            ),
        )
        Unit
    }

    override suspend fun get(pollId: Long): MtProtoPollPayload? =
        dao.get(accountSlot, environment.storageName, pollId)?.let { entity ->
            val optionDtos = runCatching {
                json.decodeFromString(ListSerializer(PollOptionDto.serializer()), entity.optionsJson)
            }.getOrDefault(emptyList())
            val options = optionDtos.map { dto -> dto.text }
            MtProtoPollPayload(
                pollId = entity.pollId,
                question = entity.question,
                options = options,
                voterCounts = optionDtos.map { it.voterCount },
                chosenFlags = optionDtos.map { it.isChosen },
                totalVoters = entity.totalVoters,
                isClosed = entity.isClosed,
                isAnonymous = entity.isAnonymous,
            )
        }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        withContext(Dispatchers.IO) { dao.deleteAccount(accountSlot, environment.storageName) }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
