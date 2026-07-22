package org.monogram.presentation.features.chats.conversation.logic

private const val SINGLE_DAY_RANGE_MAX_SECONDS = 86_400

internal data class SearchDateJumpRequest(
    val fromEpochSeconds: Int,
    val toEpochSeconds: Int
) {
    val targetEpochSeconds: Int
        get() = toEpochSeconds
}

internal fun resolveSearchDateJumpRequest(
    query: String,
    senderId: Long?,
    fromEpochSeconds: Int?,
    toEpochSeconds: Int?,
    isThreadScoped: Boolean
): SearchDateJumpRequest? {
    if (isThreadScoped) return null
    if (query.isNotBlank()) return null
    if (senderId != null) return null
    if (fromEpochSeconds == null || toEpochSeconds == null) return null

    val rangeSeconds = toEpochSeconds - fromEpochSeconds
    if (rangeSeconds < 0 || rangeSeconds >= SINGLE_DAY_RANGE_MAX_SECONDS) return null

    return SearchDateJumpRequest(
        fromEpochSeconds = fromEpochSeconds,
        toEpochSeconds = toEpochSeconds
    )
}
