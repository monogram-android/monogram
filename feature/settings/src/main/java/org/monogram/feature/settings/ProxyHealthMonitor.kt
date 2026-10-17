package org.monogram.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

class ProxyHealthMonitor(
    private val manager: ProxyProfileManager,
    private val scope: CoroutineScope,
    private val test: suspend (ProxyProfile) -> Long?,
    private val intervalMs: Long = 60_000,
    private val maxBackoffMs: Long = 15 * 60_000,
    private val onResult: (ProxyProfile, Long?) -> Unit = { _, _ -> },
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = intervalMs
            while (isActive) {
                val active = manager.active()
                if (active == null || !active.enabled || !active.isAllowedOn(manager.context)) break
                val latency = runCatching { test(active) }.getOrNull()
                manager.record(active, latency)
                onResult(active, latency)
                backoff = if (latency != null) intervalMs else min(maxBackoffMs, backoff * 2)
                delay(backoff)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}