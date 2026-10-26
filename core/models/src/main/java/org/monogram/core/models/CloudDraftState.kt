package org.monogram.core.models

data class CloudDraftState(
    val draft: CloudDraft,
    val revision: Long = 0,
    val dirty: Boolean = false,
    val lastRemoteDate: Int = draft.date,
    val discardingUnsupported: Boolean = false,
    val markdown: Boolean = false,
) {
    fun localEdit(
        value: CloudDraft, discardUnsupported: Boolean = false, markdown: Boolean? = null
    ): CloudDraftState {
        require(value.chatId == draft.chatId && value.threadId == draft.threadId)
        val guarded = value.copy(
            hasUnsupportedContent = value.hasUnsupportedContent || (!discardUnsupported && draft.hasUnsupportedContent)
        )
        return copy(
            draft = guarded,
            revision = revision + 1,
            dirty = true,
            discardingUnsupported = discardingUnsupported || discardUnsupported,
            markdown = if (discardUnsupported) false else markdown ?: this.markdown
        )
    }

    fun mergeRemote(value: CloudDraft): CloudDraftState {
        require(value.chatId == draft.chatId && value.threadId == draft.threadId)
        if (value.date != 0 && value.date < lastRemoteDate) return this
        val remoteDate = maxOf(lastRemoteDate, value.date)
        if (dirty) {
            val guarded =
                draft.copy(hasUnsupportedContent = draft.hasUnsupportedContent || (!discardingUnsupported && value.hasUnsupportedContent))
            return copy(
                draft = guarded,
                revision = if (guarded != draft) revision + 1 else revision,
                lastRemoteDate = remoteDate
            )
        }
        if (value == draft) return copy(lastRemoteDate = remoteDate)
        return copy(
            draft = value,
            revision = revision + 1,
            lastRemoteDate = remoteDate,
            markdown = markdown && value.text == draft.text && value.entitiesJson == draft.entitiesJson
        )
    }

    fun acknowledge(savedRevision: Long): CloudDraftState =
        if (savedRevision == revision) copy(dirty = false, discardingUnsupported = false) else this
}
