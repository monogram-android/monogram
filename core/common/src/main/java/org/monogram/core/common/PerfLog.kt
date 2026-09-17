package org.monogram.core.common

import android.util.Log

/**
 * Opt-in netcode timing spans; off by default, switchable from adb:
 * `setprop log.tag.monogram.perf DEBUG` (on) / `SILENT` (off).
 * Op names, durations and counters only; never credentials or message bodies.
 */
object PerfLog {
    const val TAG = "monogram.perf"

    private const val MAX_LINE = 3000
    private const val RESOLVE_INTERVAL_MS = 1_000L

    @Volatile
    private var enabled = false

    @Volatile
    private var resolvedAt = 0L

    @Volatile
    private var nativeSetEnabled: ((Boolean) -> Unit)? = null

    @Volatile
    private var nativeSnapshot: (() -> String)? = null

    fun isEnabled(): Boolean {
        val now = nowMs()
        if (now - resolvedAt >= RESOLVE_INTERVAL_MS) {
            resolvedAt = now
            // runCatching keeps plain JVM unit tests (no android.util.Log) silent.
            enabled = runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)
        }
        return enabled
    }

    fun nowMs(): Long = System.nanoTime() / 1_000_000L

    fun installNative(setEnabled: (Boolean) -> Unit, snapshot: () -> String) {
        nativeSetEnabled = setEnabled
        nativeSnapshot = snapshot
        runCatching { setEnabled(isEnabled()) }
    }

    fun mark(op: String, millis: Long, detail: String = "") {
        if (!isEnabled()) return
        runCatching { Log.i(TAG, if (detail.isEmpty()) "$op ${millis}ms" else "$op ${millis}ms $detail") }
    }

    /** One log line per event; the harness counts these. Never pass secrets. */
    fun event(op: String, detail: String = "") {
        if (!isEnabled()) return
        runCatching { Log.i(TAG, if (detail.isEmpty()) op else "$op $detail") }
    }

    /**
     * Structured span for correlating Kotlin/native work. Never pass message
     * text, tokens, auth keys, or session contents in [detail].
     */
    fun trace(
        op: String,
        phase: String,
        elapsedMs: Long? = null,
        handle: Long? = null,
        dispatchClass: Int? = null,
        result: String? = null,
        detail: String = "",
    ) {
        if (!isEnabled()) return
        val line = buildString {
            append("op=").append(op)
            append(" phase=").append(phase)
            if (handle != null && handle != 0L) append(" handle=").append(handle)
            if (dispatchClass != null) append(" class=").append(dispatchClass)
            if (elapsedMs != null) append(" elapsed_ms=").append(elapsedMs)
            if (result != null) append(" result=").append(result)
            if (detail.isNotEmpty()) append(' ').append(detail)
        }
        event("perf", line)
    }

    /** Logs one Rust snapshot window (JSON), chunked to survive logcat limits. */
    fun dump(label: String) {
        if (!isEnabled()) return
        val json = runCatching { nativeSnapshot?.invoke() }.getOrNull() ?: return
        if (json.length <= 2 || json == "{}") return
        val body = "$label $json"
        runCatching {
            var index = 0
            var part = 0
            while (index < body.length) {
                val end = minOf(index + MAX_LINE, body.length)
                Log.i(TAG, if (end == body.length) body.substring(index, end) else "[${part}] " + body.substring(index, end))
                index = end
                part++
            }
        }
    }

    fun flush() {
        runCatching { nativeSetEnabled?.invoke(false) }
    }
}

inline fun <T> perfOp(op: String, block: () -> T): T {
    if (!PerfLog.isEnabled()) return block()
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        PerfLog.mark(op, (System.nanoTime() - start) / 1_000_000)
        PerfLog.dump("perf:$op")
    }
}
