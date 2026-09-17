package org.monogram.feature.dialog.store

import org.monogram.core.models.ForumIo
import org.monogram.core.models.Message
import org.monogram.core.models.geoPlace
import org.monogram.feature.dialog.historyOrder
import kotlin.random.Random

internal fun pendingRandomId(): Long {
    var id = 0L
    while (id == 0L) {
        id = Random.nextLong()
    }
    return id
}

internal fun pendingMessageId(): Int {
    var id = 0
    while (id >= 0) {
        id = Random.nextInt()
    }
    return id
}

internal fun sameGeo(message: Message, latitude: Double, longitude: Double): Boolean {
    val place = message.geoPlace ?: return false
    return kotlin.math.abs(place.latitude - latitude) < 1e-4 &&
        kotlin.math.abs(place.longitude - longitude) < 1e-4
}

internal fun matchingPending(messages: List<Message>, incoming: Message): Message? {
    messages.firstOrNull { it.id.id == incoming.id.id }?.let { return null }
    if (incoming.randomId != 0L) {
        messages.firstOrNull {
            it.randomId == incoming.randomId && (it.pending || it.failed || it.id.id < 0)
        }?.let { return it }
    }
    if (incoming.mediaKind == "geo") {
        incoming.geoPlace?.let { place ->
            messages.lastOrNull { pending ->
                pending.outgoing &&
                    (pending.pending || pending.failed || pending.id.id < 0) &&
                    pending.mediaKind == "geo" &&
                    sameGeo(pending, place.latitude, place.longitude)
            }?.let { return it }
        }
    }
    if (!incoming.outgoing) return null
    val sameKind = messages.filter {
        it.outgoing &&
            (it.pending || it.failed || it.id.id < 0) &&
            it.mediaKind == incoming.mediaKind &&
            (incoming.groupedId == null || it.groupedId == incoming.groupedId)
    }
    if (sameKind.isEmpty()) return null
    incoming.groupedId?.let { group ->
        return sameKind.firstOrNull { it.groupedId == group && it.text == incoming.text }
            ?: sameKind.firstOrNull { it.groupedId == group }
    }
    return sameKind.lastOrNull {
        it.text == incoming.text || (it.text.isNullOrBlank() && incoming.text.isNullOrBlank())
    }
}

internal fun visibleMessages(messages: List<Message>): List<Message> =
    messages.filter {
        !it.text.isNullOrBlank() ||
            !it.mediaKind.isNullOrBlank() ||
            !it.fwdFrom.isNullOrBlank() ||
            !it.viaBot.isNullOrBlank() ||
            !it.replyMarkup?.rows.isNullOrEmpty() ||
            it.pending ||
            it.failed
    }

internal fun filterThreadMessages(messages: List<Message>, threadTopMsgId: Int): List<Message> {
    if (ForumIo.historyThreadId(threadTopMsgId) <= 0) return messages
    val known = messages.associateBy { it.id.id }
    return messages.filter { messageBelongsToThread(it, threadTopMsgId, known) }
}

internal fun messageBelongsToThread(
    message: Message,
    threadTopMsgId: Int,
    knownMessages: List<Message>,
): Boolean = messageBelongsToThread(message, threadTopMsgId, knownMessages.associateBy { it.id.id })

internal fun messageBelongsToThread(
    message: Message,
    threadTopMsgId: Int,
    knownMessages: Map<Int, Message>,
): Boolean {
    val top = ForumIo.historyThreadId(threadTopMsgId)
    if (top <= 0) return true
    if (message.pending || message.failed) return true
    if (message.id.id == top || message.replyToTopId == top || message.replyToMsgId == top) return true
    val parentId = message.replyToMsgId ?: return false
    val visited = HashSet<Int>()
    fun reachesTop(id: Int): Boolean {
        if (!visited.add(id)) return false
        if (id == top) return true
        val parent = knownMessages[id] ?: return false
        if (parent.replyToTopId == top || parent.replyToMsgId == top) return true
        return parent.replyToMsgId?.let(::reachesTop) == true
    }
    return reachesTop(parentId)
}

internal fun mergeById(messages: List<Message>): List<Message> {
    val seen = LinkedHashSet<Int>()
    return historyOrder(visibleMessages(messages.filter { seen.add(it.id.id) }))
}

internal fun upsertMessage(messages: List<Message>, incoming: Message): List<Message> {
    val index = messages.indexOfFirst { it.id.id == incoming.id.id }
    if (index < 0) {
        return mergeById(listOf(incoming) + messages)
    }
    val existing = messages[index]
    val merged = if (incoming.entities.isEmpty() && existing.entities.isNotEmpty()) {
        incoming.copy(entities = existing.entities)
    } else {
        incoming
    }
    return messages.toMutableList().also { it[index] = merged }
}
