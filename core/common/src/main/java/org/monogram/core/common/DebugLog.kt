package org.monogram.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class DebugLogKind { API, RECOMPOSITION, MEDIA, CONNECTION, ERROR }

data class DebugLogEvent(
    val id: Long,
    val atEpochMs: Long,
    val kind: DebugLogKind,
    val summary: String,
    val detail: String,
)

object DebugLog {
    const val MAX_EVENTS = 2_000
    private const val SUMMARY_LIMIT = 180
    private const val DETAIL_LIMIT = 8_000

    private val events = ArrayDeque<DebugLogEvent>()
    private val lock = Any()
    private var nextId = 1L
    private val revision = MutableStateFlow(0L)

    @Volatile
    var enabled: Boolean = false
        private set

    val changes: StateFlow<Long> = revision

    fun install(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) clear()
    }

    fun resetForTests(enabled: Boolean = true) {
        this.enabled = enabled
        synchronized(lock) {
            events.clear()
            nextId = 1L
        }
        revision.value = 0L
    }

    fun ingestApi(op: String, detail: String) {
        if (!enabled) return
        val safeDetail = redactBlock(detail)
        add(DebugLogKind.API, oneLine(op, safeDetail, null), safeDetail)
    }

    fun ingestWarn(op: String, detail: String) {
        if (!enabled) return
        val safeDetail = redactBlock(detail)
        add(DebugLogKind.ERROR, oneLine(op, safeDetail, null), safeDetail)
    }

    fun ingestCrash(thread: String, detail: String) {
        if (!enabled) return
        val safeDetail = redactBlock(detail)
        add(DebugLogKind.ERROR, oneLine("crash", thread, null), safeDetail)
    }

    fun ingestPerf(op: String, detail: String, elapsedMs: Long?) {
        if (!enabled) return
        val safeDetail = redactBlock(detail)
        val kind = classify(op, safeDetail)
        add(kind, oneLine(op, safeDetail, elapsedMs), safeDetail)
    }

    fun query(kind: DebugLogKind?, text: String): List<DebugLogEvent> {
        val needle = text.trim()
        val newestFirst = synchronized(lock) { events.toList() }.asReversed()
        if (kind == null && needle.isEmpty()) return newestFirst
        return newestFirst.filter { event ->
            (kind == null || event.kind == kind) &&
                (needle.isEmpty() ||
                    event.summary.contains(needle, ignoreCase = true) ||
                    event.detail.contains(needle, ignoreCase = true))
        }
    }

    fun clear() {
        synchronized(lock) { events.clear() }
        revision.value = revision.value + 1L
    }

    fun exportText(kind: DebugLogKind? = null, text: String = ""): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            query(kind, text).forEach { event ->
                append(format.format(Date(event.atEpochMs)))
                append(' ')
                append(event.kind.name)
                append(' ')
                append(event.summary)
                append('\n')
                if (event.detail.isNotBlank() && event.detail != event.summary) {
                    append(event.detail)
                    append('\n')
                }
            }
        }
    }

    internal fun classify(op: String, detail: String): DebugLogKind {
        val subject = namedOp(detail) ?: op
        val name = subject.lowercase(Locale.US)
        if (name == "recomp" || name.startsWith("recomp")) return DebugLogKind.RECOMPOSITION
        if (hasFailure(detail)) return DebugLogKind.ERROR
        return when {
            isMedia(name) -> DebugLogKind.MEDIA
            isConnection(name) -> DebugLogKind.CONNECTION
            else -> DebugLogKind.API
        }
    }

    private fun add(kind: DebugLogKind, summary: String, detail: String) {
        val row = DebugLogEvent(
            id = 0L,
            atEpochMs = System.currentTimeMillis(),
            kind = kind,
            summary = DebugStats.sanitize(summary).take(SUMMARY_LIMIT),
            detail = detail.take(DETAIL_LIMIT),
        )
        synchronized(lock) {
            val stored = row.copy(id = nextId++)
            events.addLast(stored)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        revision.value = revision.value + 1L
    }

    private fun oneLine(op: String, detail: String, elapsedMs: Long?): String {
        val body = detail.replace('\n', ' ').replace('\r', ' ').trim()
        val head = buildString {
            append(DebugStats.sanitize(op).ifBlank { "log" })
            if (elapsedMs != null) append(' ').append(elapsedMs.coerceAtLeast(0L)).append("ms")
        }
        return if (body.isEmpty()) head else "$head $body"
    }

    private fun redactBlock(value: String): String =
        value.lineSequence().joinToString("\n") { DebugStats.sanitize(it) }

    private fun namedOp(detail: String): String? =
        NAMED_OP.find(detail)?.groupValues?.get(1)

    private fun hasFailure(detail: String): Boolean {
        val result = RESULT.find(detail)?.groupValues?.get(1) ?: return false
        return result != "ok" && result != "cancel"
    }

    private fun isMedia(name: String): Boolean =
        name.startsWith("download:") ||
            name.startsWith("stream") ||
            name.startsWith("cache") ||
            name.contains("upload") ||
            name.contains("queue_wait")

    private fun isConnection(name: String): Boolean =
        name.contains("connect") || name.contains("prewarm")

    private val NAMED_OP = Regex("(?:^|\\s)op=(\\S+)")
    private val RESULT = Regex("(?:^|\\s)result=(\\S+)")
}
