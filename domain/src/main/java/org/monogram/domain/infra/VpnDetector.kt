package org.monogram.domain.infra

import kotlinx.coroutines.flow.StateFlow

interface VpnDetector {
    val isVpnActive: StateFlow<Boolean>
    fun startMonitoring()
    fun stopMonitoring()
}
