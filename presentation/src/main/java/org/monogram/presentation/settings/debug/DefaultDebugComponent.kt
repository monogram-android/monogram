package org.monogram.presentation.settings.debug

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext
import java.io.File

class DefaultDebugComponent(
    private val context: AppComponentContext,
    private val onBack: () -> Unit
) : DebugComponent, AppComponentContext by context {

    private val messageDisplayer = container.utils.messageDisplayer()
    private val assetsManager = container.utils.assetsManager()
    private val externalNavigator = container.utils.externalNavigator()
    private val distrManager = container.utils.distrManager()
    private val pushDebugRepository = container.repositories.pushDebugRepository
    private val sponsorRepository = container.repositories.sponsorRepository
    private val scope = componentScope

    private val _state = MutableValue(
        DebugComponent.State(
            isGmsAvailable = distrManager.isGmsAvailable(),
            isFcmAvailable = distrManager.isFcmAvailable(),
            isUnifiedPushDistributorAvailable = distrManager.isUnifiedPushDistributorAvailable()
        )
    )
    override val state: Value<DebugComponent.State> = _state

    init {
        pushDebugRepository.diagnostics.onEach { diagnostics ->
            _state.update {
                it.copy(
                    pushProvider = diagnostics.pushProvider,
                    backgroundServiceEnabled = diagnostics.backgroundServiceEnabled,
                    hideForegroundNotification = diagnostics.hideForegroundNotification,
                    isPowerSavingMode = diagnostics.isPowerSavingMode,
                    isWakeLockEnabled = diagnostics.isWakeLockEnabled,
                    batteryOptimizationEnabled = diagnostics.batteryOptimizationEnabled,
                    isTdNotificationServiceRunning = diagnostics.isTdNotificationServiceRunning,
                    unifiedPushStatus = diagnostics.unifiedPushStatus,
                    unifiedPushEndpoint = diagnostics.unifiedPushEndpoint,
                    unifiedPushSavedDistributor = diagnostics.unifiedPushSavedDistributor,
                    unifiedPushAckDistributor = diagnostics.unifiedPushAckDistributor,
                    unifiedPushDistributorsCount = diagnostics.unifiedPushDistributorsCount
                )
            }
        }.launchIn(scope)
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onCrashClicked() {
        throw RuntimeException("Test crash")
    }

    override fun onShowSponsorSheetClicked() {
        externalNavigator.openUrl("https://boosty.to/monogram")
    }

    override fun onForceSponsorSyncClicked() {
        sponsorRepository.forceSponsorSync()
        messageDisplayer.show("Sponsor sync started")
    }

    override fun onTestPushClicked() {
        pushDebugRepository.triggerTestPush()
        messageDisplayer.show("Debug push dispatched")
    }

    override fun onDropDatabasesClicked() {
        messageDisplayer.show("Dropping databases and restarting...")
        assetsManager.getDatabasePath("monogram_db").delete()
        File(assetsManager.getFilesDir(), "td-db").deleteRecursively()
        assetsManager.exitProcess(0)
    }

    override fun onDropDatabaseCacheClicked() {
        messageDisplayer.show("Dropping databases and restarting...")
        assetsManager.getDatabasePath("monogram_db").delete()
        assetsManager.exitProcess(0)
    }

    override fun onDropCachePrefsClicked() {
        messageDisplayer.show("Dropping cache prefs and restarting...")
        assetsManager.clearSharedPreferences("monogram_cache")
        assetsManager.exitProcess(0)
    }

    override fun onDropPrefsClicked() {
        messageDisplayer.show("Dropping prefs and restarting...")
        assetsManager.clearSharedPreferences("monogram_prefs")
        assetsManager.exitProcess(0)
    }
}
