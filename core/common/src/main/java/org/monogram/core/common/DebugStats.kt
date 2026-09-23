package org.monogram.core.common

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class DebugStatKind { RPC, MEDIA, CONNECTION, ERROR }

data class DebugStatRecord(
    val atEpochMs: Long,
    val kind: DebugStatKind,
    val op: String,
    val durationMs: Long,
    val result: String,
    val errorKind: String? = null,
    val bytes: Long? = null,
) {
    val bytesPerSec: Long?
        get() {
            val size = bytes ?: return null
            if (durationMs <= 0L || size <= 0L) return null
            return (size * 1000L) / durationMs
        }
}

data class DebugStatAverage(
    val op: String,
    val count: Int,
    val avgMs: Long,
    val maxMs: Long,
)

data class DebugStatSnapshot(
    val enabled: Boolean,
    val records: List<DebugStatRecord>,
) {
    fun slowest(kind: DebugStatKind? = null, limit: Int = 50): List<DebugStatRecord> {
        val filtered = if (kind == null) records else records.filter { it.kind == kind }
        return filtered.sortedByDescending { it.durationMs }.take(limit)
    }

    fun recentErrors(limit: Int = 50): List<DebugStatRecord> =
        records.asReversed().filter { it.result != "ok" && it.result != "cancel" }.take(limit)

    fun averages(): List<DebugStatAverage> =
        records.groupBy { it.op }.map { (op, rows) ->
            DebugStatAverage(
                op = op,
                count = rows.size,
                avgMs = rows.map { it.durationMs }.average().toLong(),
                maxMs = rows.maxOf { it.durationMs },
            )
        }.sortedByDescending { it.avgMs }
}

/**
 * Debug-build collector for RPC, media, and connection timings.
 * Never stores bodies, tokens, keys, or session paths.
 */
object DebugStats {
    const val MAX_RECORDS = 2500
    const val REPORT_LIMIT = 50
    private const val FILE_NAME = "debug-stats.jsonl"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024

    @Volatile
    var enabled: Boolean = false
        private set

    private val records = CopyOnWriteArrayList<DebugStatRecord>()
    private val pendingBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var file: File? = null

    fun install(enabled: Boolean, cacheDir: File? = null) {
        this.enabled = enabled
        file = if (enabled) cacheDir?.let { File(it, FILE_NAME) } else null
        records.clear()
        if (enabled) loadLocked()
    }

    fun resetForTests(enabled: Boolean = true) {
        this.enabled = enabled
        file = null
        records.clear()
        pendingBytes.clear()
    }

    fun record(
        kind: DebugStatKind,
        op: String,
        durationMs: Long,
        result: String = "ok",
        errorKind: String? = null,
        bytes: Long? = null,
        atEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (!enabled) return
        val safeOp = sanitize(op).ifBlank { "unknown" }
        val safeResult = sanitize(result).ifBlank { "ok" }.take(24)
        val safeError = errorKind?.let(::sanitize)?.take(48)
        val row = DebugStatRecord(
            atEpochMs = atEpochMs,
            kind = kind,
            op = safeOp.take(80),
            durationMs = durationMs.coerceAtLeast(0L),
            result = safeResult,
            errorKind = safeError,
            bytes = bytes?.takeIf { it > 0L },
        )
        records.add(row)
        while (records.size > MAX_RECORDS) {
            records.removeAt(0)
        }
        persistLine(row)
    }

    fun attachBytes(op: String, bytes: Long) {
        if (!enabled || bytes <= 0L) return
        pendingBytes[sanitize(op).take(80)] = bytes
        val last = records.lastOrNull { it.op == op }
        if (last != null) {
            val index = records.lastIndexOf(last)
            if (index >= 0) records[index] = last.copy(bytes = bytes)
        }
    }

    fun ingestPerfMark(op: String, millis: Long, detail: String = "") {
        if (!enabled) return
        val result = resultFromDetail(detail)
        val error = errorFromDetail(detail)
        val name = sanitize(op).take(80)
        val bytes = bytesFromDetail(detail) ?: pendingBytes.remove(name)
        record(kindFor(op, result), op, millis, result, error, bytes)
    }

    fun snapshot(): DebugStatSnapshot = DebugStatSnapshot(enabled, records.toList())

    fun reportText(): String {
        val snap = snapshot()
        if (!snap.enabled) return "debug stats disabled\n"
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return buildString {
            appendLine("Monogram debug stats")
            appendLine("records=${snap.records.size} cap=$MAX_RECORDS")
            appendLine()
            appendLine("Slowest RPCs")
            snap.slowest(DebugStatKind.RPC, REPORT_LIMIT).forEach { row ->
                appendLine("  ${row.durationMs}ms ${row.op} ${row.result}")
            }
            appendLine()
            appendLine("Slowest media")
            snap.slowest(DebugStatKind.MEDIA, REPORT_LIMIT).forEach { row ->
                val speed = row.bytesPerSec?.let { " ${formatSpeed(it)}" } ?: ""
                val size = row.bytes?.let { " ${formatBytes(it)}" } ?: ""
                appendLine("  ${row.durationMs}ms ${row.op}$size$speed ${row.result}")
            }
            appendLine()
            appendLine("Connection")
            snap.slowest(DebugStatKind.CONNECTION, REPORT_LIMIT).forEach { row ->
                appendLine("  ${row.durationMs}ms ${row.op} ${row.result}")
            }
            appendLine()
            appendLine("Averages")
            snap.averages().take(REPORT_LIMIT).forEach { avg ->
                appendLine("  ${avg.op} n=${avg.count} avg=${avg.avgMs}ms max=${avg.maxMs}ms")
            }
            appendLine()
            appendLine("Recent errors")
            val errors = snap.recentErrors(REPORT_LIMIT)
            if (errors.isEmpty()) appendLine("  none")
            errors.forEach { row ->
                val whenText = fmt.format(Date(row.atEpochMs))
                appendLine("  $whenText ${row.op} ${row.result} ${row.errorKind ?: ""}".trimEnd())
            }
            appendLine()
        }
    }

    fun clear() {
        records.clear()
        runCatching { file?.takeIf { it.isFile }?.delete() }
    }

    internal fun kindFor(op: String, result: String): DebugStatKind {
        val name = op.lowercase(Locale.US)
        if (result != "ok" && result != "cancel") {
            if (name.startsWith("download:") || name.startsWith("stream") || name.contains("upload")) {
                return DebugStatKind.MEDIA
            }
            return DebugStatKind.ERROR
        }
        return when {
            name.startsWith("download:") || name.startsWith("stream") || name.contains("upload") ->
                DebugStatKind.MEDIA
            name.contains("connect") || name.contains("prewarm") -> DebugStatKind.CONNECTION
            else -> DebugStatKind.RPC
        }
    }

    internal fun resultFromDetail(detail: String): String {
        val match = RESULT.find(detail) ?: return "ok"
        return match.groupValues[1]
    }

    internal fun errorFromDetail(detail: String): String? =
        ERROR.find(detail)?.groupValues?.get(1)

    internal fun bytesFromDetail(detail: String): Long? =
        BYTES.find(detail)?.groupValues?.get(1)?.toLongOrNull()

    internal fun sanitize(value: String): String {
        var out = AppLog.redact(value)
        out = TOKEN.replace(out, "[redacted]")
        out = out.replace('\n', ' ').replace('\r', ' ')
        return out.trim()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    private fun persistLine(row: DebugStatRecord) {
        val dest = file ?: return
        runCatching {
            dest.appendText(
                buildString {
                    append(row.atEpochMs).append('\t')
                    append(row.kind.name).append('\t')
                    append(row.op).append('\t')
                    append(row.durationMs).append('\t')
                    append(row.result)
                    if (row.errorKind != null) append('\t').append(row.errorKind)
                    if (row.bytes != null) append('\t').append(row.bytes)
                    append('\n')
                },
            )
            if (dest.length() > MAX_FILE_BYTES) {
                val kept = dest.readText().takeLast(MAX_FILE_BYTES / 2)
                dest.writeText(kept)
            }
        }
    }

    private fun loadLocked() {
        val dest = file?.takeIf { it.isFile } ?: return
        runCatching {
            dest.readLines().takeLast(MAX_RECORDS).forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@forEach
                val kind = runCatching { DebugStatKind.valueOf(parts[1]) }.getOrNull() ?: return@forEach
                records.add(
                    DebugStatRecord(
                        atEpochMs = parts[0].toLongOrNull() ?: return@forEach,
                        kind = kind,
                        op = parts[2],
                        durationMs = parts[3].toLongOrNull() ?: 0L,
                        result = parts[4],
                        errorKind = parts.getOrNull(5)?.takeIf { it.isNotBlank() && it.toLongOrNull() == null },
                        bytes = parts.lastOrNull()?.toLongOrNull(),
                    ),
                )
            }
        }
    }

    private val RESULT = Regex("(?:^|\\s)result=(\\S+)")
    private val ERROR = Regex("(?:^|\\s)error=(\\S+)")
    private val BYTES = Regex("(?:^|\\s)bytes=(\\d+)")
    private val TOKEN = Regex("\\d{6,}:[A-Za-z0-9_-]{20,}")
}
