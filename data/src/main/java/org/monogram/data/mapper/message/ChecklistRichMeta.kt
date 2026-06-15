package org.monogram.data.mapper.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.domain.models.ChecklistTask
import org.monogram.domain.models.MessageEntity

private val checklistRichMetaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun ChecklistPayload.encode(): String =
    checklistRichMetaJson.encodeToString(ChecklistPayload.serializer(), this)

internal fun decodeChecklistPayload(raw: String?): ChecklistPayload? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        checklistRichMetaJson.decodeFromString(ChecklistPayload.serializer(), raw)
    }.getOrNull()
}

@Serializable
internal data class ChecklistPayload(
    val titleEntities: List<MessageEntity> = emptyList(),
    val tasks: List<ChecklistTaskPayload> = emptyList(),
    val othersCanAddTasks: Boolean = false,
    val canAddTasks: Boolean = false,
    val othersCanMarkTasksAsDone: Boolean = false,
    val canMarkTasksAsDone: Boolean = false
)

@Serializable
internal data class ChecklistTaskPayload(
    val id: Int,
    val text: String,
    val entities: List<MessageEntity> = emptyList(),
    val completedById: Long? = null,
    val completedByName: String? = null,
    val completionDate: Int = 0
)

internal fun ChecklistTask.toPayload(): ChecklistTaskPayload = ChecklistTaskPayload(
    id = id,
    text = text,
    entities = entities,
    completedById = completedById,
    completedByName = completedByName,
    completionDate = completionDate
)

internal fun ChecklistTaskPayload.toDomain(): ChecklistTask = ChecklistTask(
    id = id,
    text = text,
    entities = entities,
    completedById = completedById,
    completedByName = completedByName,
    completionDate = completionDate
)
