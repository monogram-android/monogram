package org.monogram.core.models

data class ChecklistItem(
    val id: Int,
    val text: String,
    val done: Boolean = false,
    val completedBy: Long? = null,
    val completedAt: Long? = null,
)

data class Checklist(
    val title: String,
    val othersCanAppend: Boolean = false,
    val othersCanComplete: Boolean = false,
    val items: List<ChecklistItem> = emptyList(),
)

object Checklists {
    fun parse(raw: String?): Checklist? {
        if (raw.isNullOrBlank()) return null
        val root = CompactJson.parse(raw) as? Map<*, *> ?: return null
        val title = root["t"] as? String ?: return null
        val items = (root["i"] as? List<*>)?.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val id = (obj["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val text = obj["t"] as? String ?: return@mapNotNull null
            ChecklistItem(
                id = id,
                text = text,
                done = (obj["d"] as? Number)?.toInt() == 1 || obj["d"] == true,
                completedBy = (obj["by"] as? Number)?.toLong()?.takeIf { it != 0L },
                completedAt = (obj["at"] as? Number)?.toLong()?.takeIf { it != 0L },
            )
        }.orEmpty()
        return Checklist(
            title = title,
            othersCanAppend = root["a"] == true,
            othersCanComplete = root["c"] == true,
            items = items,
        )
    }

    fun serialize(checklist: Checklist?): String? {
        if (checklist == null) return null
        return buildString {
            append("{\"t\":\"").append(CompactJson.escape(checklist.title)).append('"')
            if (checklist.othersCanAppend) append(",\"a\":true")
            if (checklist.othersCanComplete) append(",\"c\":true")
            append(",\"i\":[")
            checklist.items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append("{\"id\":").append(item.id)
                append(",\"t\":\"").append(CompactJson.escape(item.text)).append('"')
                if (item.done) append(",\"d\":1")
                item.completedBy?.let { append(",\"by\":").append(it) }
                item.completedAt?.let { append(",\"at\":").append(it) }
                append('}')
            }
            append("]}")
        }
    }
}
