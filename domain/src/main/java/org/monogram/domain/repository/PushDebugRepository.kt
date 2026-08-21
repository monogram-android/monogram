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
    val backgroundServiceEnabled: Boolean = false,
    val hideForegroundNotification: Boolean = false,
    val isPowerSavingMode: Boolean = false,
    val isWakeLockEnabled: Boolean = false,
    val batteryOptimizationEnabled: Boolean = false,
    val isMtProtoNotificationServiceRunning: Boolean = false,
    val unifiedPushStatus: UnifiedPushDebugStatus = UnifiedPushDebugStatus.IDLE,
    val unifiedPushEndpoint: String? = null,
    val unifiedPushSavedDistributor: String? = null,
    val unifiedPushAckDistributor: String? = null,
    val unifiedPushDistributorsCount: Int = 0,
    val unifiedPushLastRegisterAttemptAt: Long = 0L,
    val unifiedPushLastRegisteredAt: Long = 0L,
    val unifiedPushLastPushAt: Long = 0L
)

interface PushDebugRepository {
    val diagnostics: StateFlow<PushDiagnostics>
    fun triggerTestPush()
}
