@file:Suppress("NOTHING_TO_INLINE")

package org.monogram.core.ui.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import org.monogram.core.common.DebugLog
import org.monogram.core.common.PerfLog

@Composable
inline fun RecompositionProbe(name: String) {
    if (PerfLog.isEnabled() || DebugLog.enabled) {
        SideEffect {
            if (PerfLog.isEnabled()) {
                PerfLog.event("recomp", name)
            } else {
                DebugLog.ingestPerf("recomp", name, null)
            }
        }
    }
}

@Composable
inline fun RecompositionProbe(
    name: String,
    detail: Int?,
) {
    if (PerfLog.isEnabled() || DebugLog.enabled) {
        SideEffect {
            val line = if (detail == null) name else "$name $detail"
            if (PerfLog.isEnabled()) {
                PerfLog.event("recomp", line)
            } else {
                DebugLog.ingestPerf("recomp", line, null)
            }
        }
    }
}

@Composable
inline fun RecompositionProbe(
    name: String,
    detail: Int,
) {
    if (PerfLog.isEnabled() || DebugLog.enabled) {
        SideEffect {
            val line = "$name $detail"
            if (PerfLog.isEnabled()) {
                PerfLog.event("recomp", line)
            } else {
                DebugLog.ingestPerf("recomp", line, null)
            }
        }
    }
}

@Composable
inline fun RecompositionProbe(
    name: String,
    detail: Long,
) {
    if (PerfLog.isEnabled() || DebugLog.enabled) {
        SideEffect {
            val line = "$name $detail"
            if (PerfLog.isEnabled()) {
                PerfLog.event("recomp", line)
            } else {
                DebugLog.ingestPerf("recomp", line, null)
            }
        }
    }
}

/**
 * Times [block] and emits one `mark <op> <millis>ms` line. Used around `remember` blocks so
 * the span count *is* the recomputation count: a block that used to re-run on every state
 * emission logs fewer, cheaper spans after the fix. `PerfLog.dump` is deliberately not used
 * here; probing the native snapshot on every recomputation would drown the log.
 */
inline fun <T> perfSpan(
    op: String,
    block: () -> T,
): T {
    if (!PerfLog.isEnabled()) return block()
    val started = PerfLog.nowMs()
    return block().also { PerfLog.mark(op, PerfLog.nowMs() - started) }
}
