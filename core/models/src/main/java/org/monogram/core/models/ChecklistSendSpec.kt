package org.monogram.core.models

data class ChecklistSendSpec(
    val title: String,
    val items: List<String>,
    val othersCanAppend: Boolean = false,
    val othersCanComplete: Boolean = false,
) {
    fun pendingChecklist(): Checklist {
        requireValid()
        return Checklist(
            title,
            items = items.mapIndexed { index, text -> ChecklistItem(index + 1, text) },
            othersCanAppend = othersCanAppend,
            othersCanComplete = othersCanComplete
        )
    }

    companion object {
        fun fromPending(checklist: Checklist): ChecklistSendSpec {
            require(checklist.entities.isEmpty())
            require(checklist.items.withIndex().all { (index, item) ->
                item.entities.isEmpty() && item.id == index + 1 && !item.done && item.completedBy == null && item.completedAt == null
            })
            return ChecklistSendSpec(
                checklist.title,
                checklist.items.map { it.text },
                checklist.othersCanAppend,
                checklist.othersCanComplete
            ).also { it.requireValid() }
        }
    }

    fun requireValid() {
        require(title.isNotBlank() && title.length <= 32)
        require(items.size in 1..30 && items.all { it.isNotBlank() && it.length <= 64 })
    }
}
