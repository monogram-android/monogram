package org.monogram.core.ui.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import org.monogram.core.common.PerfLog

/**
 * Dev-only recomposition counter for the compose perf harness
 * (`scripts/perf/compose-perf.sh`), the chat-list equivalent of the netcode spans.
 *
 * One `recomp <name> [detail]` line is emitted per composition that actually runs; skipped
 * compositions emit nothing, so counting the lines for a scenario gives restart counts and
 * lets a probe count be compared against the Studio Layout Inspector "Recompositions" column.
 *
 * [inline] on purpose: a non-inlined probe is itself a skippable composable call, so restarts
 * that repeat the same arguments would be silently skipped and under-counted. Inlined, the
 * `SideEffect` is registered in the caller's own group and every execution of the caller's body
 * is counted.
 *
 * The detail overloads take the raw id, and the primitive ones take it unboxed, so nothing is
 * formatted or boxed while the gate is off; these probes sit on the paths being optimised. The
 * nullable `Int?` overload exists for ids that arrive nullable (folder filters). Ids only ever reach
 * logcat in a build where someone enabled the gate deliberately.
 */
@Composable
inline fun RecompositionProbe(name: String) {
    if (PerfLog.isEnabled()) {
        SideEffect { PerfLog.event("recomp", name) }
    }
}

@Composable
inline fun RecompositionProbe(name: String, detail: Int?) {
    if (PerfLog.isEnabled()) {
        SideEffect { PerfLog.event("recomp", if (detail == null) name else "$name $detail") }
    }
}

@Composable
inline fun RecompositionProbe(name: String, detail: Int) {
    if (PerfLog.isEnabled()) {
        SideEffect { PerfLog.event("recomp", "$name $detail") }
    }
}

@Composable
inline fun RecompositionProbe(name: String, detail: Long) {
    if (PerfLog.isEnabled()) {
        SideEffect { PerfLog.event("recomp", "$name $detail") }
    }
}

/**
 * Times [block] and emits one `mark <op> <millis>ms` line. Used around `remember` blocks so
 * the span count *is* the recomputation count: a block that used to re-run on every state
 * emission logs fewer, cheaper spans after the fix. `PerfLog.dump` is deliberately not used
 * here; probing the native snapshot on every recomputation would drown the log.
 */
inline fun <T> perfSpan(op: String, block: () -> T): T {
    if (!PerfLog.isEnabled()) return block()
    val started = PerfLog.nowMs()
    return block().also { PerfLog.mark(op, PerfLog.nowMs() - started) }
}
