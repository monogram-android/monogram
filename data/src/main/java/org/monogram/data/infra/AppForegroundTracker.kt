package org.monogram.data.infra

import kotlinx.coroutines.flow.StateFlow

interface AppForegroundTracker {
    val isForeground: StateFlow<Boolean>

    fun start()
}
