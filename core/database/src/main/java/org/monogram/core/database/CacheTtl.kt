package org.monogram.core.database

import org.monogram.core.database.entity.MessageHoleEntity

const val CACHE_MESSAGE_TTL_SECONDS = 30L * 24 * 60 * 60
const val CACHE_PROFILE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
const val CACHE_PRUNE_CHATS = 4
const val CACHE_PRUNE_ROWS = 40

data class CachedMessage(
    val id: Int,
    val dateSeconds: Long,
    val pending: Boolean = false,
    val failed: Boolean = false,
)

/** Confirmed rows older than the TTL and outside the newest page. Pending and failed rows stay. */
fun expiredMessageIds(
    messages: List<CachedMessage>,
    nowSeconds: Long,
    keepNewest: Int = HISTORY_CACHE_WINDOW,
    ttlSeconds: Long = CACHE_MESSAGE_TTL_SECONDS,
): List<Int> {
    val confirmed = messages.filter { !it.pending && !it.failed }
    val newest = confirmed.sortedByDescending { it.id }.take(keepNewest).map { it.id }.toSet()
    val cutoff = nowSeconds - ttlSeconds
    return confirmed.mapNotNull { row ->
        when {
            row.id in newest -> null
            row.dateSeconds >= cutoff -> null
            else -> row.id
        }
    }
}

fun chatsToPrune(chatIds: List<Long>, openChatId: Long?): List<Long> =
    if (openChatId == null) chatIds else chatIds.filter { it != openChatId }

fun profileCacheStale(
    updatedAtMillis: Long,
    nowMillis: Long,
    ttlMillis: Long = CACHE_PROFILE_TTL_MILLIS,
): Boolean = updatedAtMillis < nowMillis - ttlMillis

fun idRanges(ids: List<Int>): List<Pair<Int, Int>> {
    if (ids.isEmpty()) return emptyList()
    val sorted = ids.distinct().sorted()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = sorted.first()
    var end = start
    for (id in sorted.drop(1)) {
        if (id.toLong() == end.toLong() + 1L) {
            end = id
        } else {
            ranges += start to end
            start = id
            end = id
        }
    }
    ranges += start to end
    return ranges
}

/**
 * Adds holes for the deleted tail, then punches the kept ids back out so the
 * newest page is not reported as missing.
 */
fun holesAfterCacheTtl(
    chatId: Long,
    existing: List<MessageHoleEntity>,
    deletedIds: List<Int>,
    keptIds: List<Int>,
): List<MessageHoleEntity> {
    if (deletedIds.isEmpty()) return existing
    val source = existing.firstOrNull()?.source ?: MessageHoleEntity.SOURCE_HISTORY
    val added = idRanges(deletedIds).map { (start, end) ->
        MessageHoleEntity(chatId, start, end, source)
    }
    var holes = mergeHistoryHoles(existing + added)
    for ((start, end) in idRanges(keptIds)) {
        holes = holes.flatMap { subtractHistoryRange(it, start, end) }
    }
    return holes
}
