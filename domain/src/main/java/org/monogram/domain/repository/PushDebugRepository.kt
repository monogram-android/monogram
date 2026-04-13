package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

enum class UnifiedPushDebugStatus {
    IDLE,
    REGISTERING,
    REGISTERED,
    FAILED,
    UNREGISTERED
}

data class PushDiagnostics(
    val pushProvider: PushProvider = PushProvider.FCM,
    val backgroundServiceEnabled: Boolean = true,
    val hideForegroundNotification: Boolean = false,
    val isPowerSavingMode: Boolean = false,
    val isWakeLockEnabled: Boolean = true,
    val batteryOptimizationEnabled: Boolean = false,
    val isTdNotificationServiceRunning: Boolean = false,
    val unifiedPushStatus: UnifiedPushDebugStatus = UnifiedPushDebugStatus.IDLE,
    val unifiedPushEndpoint: String? = null,
    val unifiedPushSavedDistributor: String? = null,
    val unifiedPushAckDistributor: String? = null,
    val unifiedPushDistributorsCount: Int = 0
)

interface PushDebugRepository {
    val diagnostics: StateFlow<PushDiagnostics>
    fun triggerTestPush()
}
