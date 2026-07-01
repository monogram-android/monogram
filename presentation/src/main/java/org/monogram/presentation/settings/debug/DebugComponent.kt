package org.monogram.presentation.settings.debug

import com.arkivanov.decompose.value.Value
import org.monogram.domain.repository.PushProvider
import org.monogram.domain.repository.UnifiedPushDebugStatus

interface DebugComponent {
    val state: Value<State>

    fun onBackClicked()
    fun onCrashClicked()
    fun onForceSponsorSyncClicked()
    fun onTestPushClicked()
    fun onDropDatabasesClicked()
    fun onDropCachePrefsClicked()
    fun onDropPrefsClicked()
    fun onDropDatabaseCacheClicked()

    data class State(
        val pushProvider: PushProvider = PushProvider.FCM,
        val backgroundServiceEnabled: Boolean = false,
        val hideForegroundNotification: Boolean = false,
        val isPowerSavingMode: Boolean = false,
        val isWakeLockEnabled: Boolean = false,
        val batteryOptimizationEnabled: Boolean = false,
        val isTdNotificationServiceRunning: Boolean = false,
        val unifiedPushStatus: UnifiedPushDebugStatus = UnifiedPushDebugStatus.IDLE,
        val unifiedPushEndpoint: String? = null,
        val unifiedPushSavedDistributor: String? = null,
        val unifiedPushAckDistributor: String? = null,
        val unifiedPushDistributorsCount: Int = 0,
        val unifiedPushLastRegisterAttemptAt: Long = 0L,
        val unifiedPushLastRegisteredAt: Long = 0L,
        val unifiedPushLastPushAt: Long = 0L,
        val isGmsAvailable: Boolean = false,
        val isFcmAvailable: Boolean = false,
        val isUnifiedPushDistributorAvailable: Boolean = false,
        val supportersCount: Int = 0,
        val isSponsorsLoading: Boolean = true,
        val sponsorLastSyncAt: Long = 0L
    )
}
