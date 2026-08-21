package org.monogram.presentation.settings.debug

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ConversationPipelineMode
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.core.util.conversationPipelineModeWithLegacyKillSwitch
import org.monogram.presentation.core.util.defaultConversationPipelineMode
import org.monogram.presentation.core.util.isConversationPipelineKillSwitchAvailable
import org.monogram.presentation.root.AppComponentContext
import java.io.File

class DefaultDebugComponent(
    private val context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onAdBlock: () -> Unit
) : DebugComponent, AppComponentContext by context {

    private val messageDisplayer = container.utils.messageDisplayer()
    private val assetsManager = container.utils.assetsManager()
    private val distrManager = container.utils.distrManager()
    private val pushDebugRepository = container.repositories.pushDebugRepository
    private val sponsorRepository = container.repositories.sponsorRepository
    private val appPreferences = container.preferences.appPreferencesProvider
    private val scope = componentScope

    private val conversationPipelineDefault = defaultConversationPipelineMode(
        isDebug = BuildConfig.DEBUG,
    )
    private val isConversationPipelineKillSwitchAvailable =
        isConversationPipelineKillSwitchAvailable()

    private val _state = MutableValue(
        DebugComponent.State(
            isConversationPipelineKillSwitchAvailable = isConversationPipelineKillSwitchAvailable,
            isLegacyConversationPipelineForced =
                appPreferences.conversationPipelineMode.value == ConversationPipelineMode.Legacy,
            isGmsAvailable = distrManager.isGmsAvailable(),
            isFcmAvailable = distrManager.isFcmAvailable(),
            isUnifiedPushDistributorAvailable = distrManager.isUnifiedPushDistributorAvailable(),
            isInstalledFromGooglePlay = distrManager.isInstalledFromGooglePlay()
        )
    )
    override val state: Value<DebugComponent.State> = _state

    init {
        appPreferences.conversationPipelineMode.onEach { mode ->
            _state.update {
                it.copy(isLegacyConversationPipelineForced = mode == ConversationPipelineMode.Legacy)
            }
        }.launchIn(scope)


        pushDebugRepository.diagnostics.onEach { diagnostics ->
            _state.update {
                it.copy(
                    pushProvider = diagnostics.pushProvider,
                    backgroundServiceEnabled = diagnostics.backgroundServiceEnabled,
                    hideForegroundNotification = diagnostics.hideForegroundNotification,
                    isPowerSavingMode = diagnostics.isPowerSavingMode,
                    isWakeLockEnabled = diagnostics.isWakeLockEnabled,
                    batteryOptimizationEnabled = diagnostics.batteryOptimizationEnabled,
                    isMtProtoNotificationServiceRunning = diagnostics.isMtProtoNotificationServiceRunning,
                    unifiedPushStatus = diagnostics.unifiedPushStatus,
                    unifiedPushEndpoint = diagnostics.unifiedPushEndpoint,
                    unifiedPushSavedDistributor = diagnostics.unifiedPushSavedDistributor,
                    unifiedPushAckDistributor = diagnostics.unifiedPushAckDistributor,
                    unifiedPushDistributorsCount = diagnostics.unifiedPushDistributorsCount,
                    unifiedPushLastRegisterAttemptAt = diagnostics.unifiedPushLastRegisterAttemptAt,
                    unifiedPushLastRegisteredAt = diagnostics.unifiedPushLastRegisteredAt,
                    unifiedPushLastPushAt = diagnostics.unifiedPushLastPushAt
                )
            }
        }.launchIn(scope)

        scope.launch {
            sponsorRepository.sponsorState.collectLatest { sponsorState ->
                _state.update {
                    it.copy(
                        supportersCount = sponsorState.supportersCount,
                        isSponsorsLoading = !sponsorState.isLoaded && sponsorState.supportersCount == 0,
                        sponsorLastSyncAt = sponsorState.lastSyncAt
                    )
                }
            }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onCrashClicked() {
        throw RuntimeException("Test crash")
    }

    override fun onForceSponsorSyncClicked() {
        sponsorRepository.forceSponsorSync()
        messageDisplayer.show("Sponsor sync started")
    }

    override fun onConversationPipelineKillSwitchChanged(forceLegacy: Boolean) {
        if (!isConversationPipelineKillSwitchAvailable) return
        appPreferences.setConversationPipelineMode(
            conversationPipelineModeWithLegacyKillSwitch(
                forceLegacy = forceLegacy,
                defaultMode = conversationPipelineDefault
            )
        )
        messageDisplayer.show("Chat pipeline mode will apply to newly opened chats")
    }

    override fun onTestPushClicked() {
        pushDebugRepository.triggerTestPush()
        messageDisplayer.show("Debug push dispatched")
    }

    override fun onAdBlockClicked() {
        onAdBlock()
    }

    override fun onDropDatabasesClicked() {
        messageDisplayer.show("Dropping databases and restarting...")
        assetsManager.getDatabasePath("monogram_db").delete()
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
