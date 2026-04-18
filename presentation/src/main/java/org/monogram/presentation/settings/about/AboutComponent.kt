package org.monogram.presentation.settings.about

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.UpdateState
import org.monogram.domain.repository.UpdateRepository
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface AboutComponent {
    val updateState: StateFlow<UpdateState>
    val tdLibVersion: StateFlow<String>
    val tdLibCommitHash: StateFlow<String>
    fun onBackClicked()
    fun checkForUpdates()
    fun downloadUpdate()
    fun installUpdate()
    fun onTermsOfServiceClicked()
    fun onOpenSourceLicensesClicked()
}

class DefaultAboutComponent(
    context: AppComponentContext,
    private val updateRepository: UpdateRepository,
    private val onBack: () -> Unit,
    private val onTermsOfService: () -> Unit,
    private val onOpenSourceLicenses: () -> Unit
) : AboutComponent, AppComponentContext by context {

    private val scope = componentScope
    private val isTelemtBuild = BuildConfig.ENABLE_TELEMT_DNS

    private val _tdLibVersion = MutableStateFlow("Loading...")
    override val tdLibVersion: StateFlow<String> = _tdLibVersion.asStateFlow()

    private val _tdLibCommitHash = MutableStateFlow("")
    override val tdLibCommitHash: StateFlow<String> = _tdLibCommitHash.asStateFlow()

    init {
        scope.launch {
            _tdLibVersion.value = updateRepository.getTdLibVersion()
            _tdLibCommitHash.value = updateRepository.getTdLibCommitHash()
        }
    }

    override val updateState: StateFlow<UpdateState> = updateRepository.updateState

    override fun onBackClicked() {
        onBack()
    }

    override fun checkForUpdates() {
        if (isTelemtBuild) return
        scope.launch {
            updateRepository.checkForUpdates()
        }
    }

    override fun downloadUpdate() {
        updateRepository.downloadUpdate()
    }

    override fun installUpdate() {
        updateRepository.installUpdate()
    }

    override fun onTermsOfServiceClicked() {
        onTermsOfService()
    }

    override fun onOpenSourceLicensesClicked() {
        onOpenSourceLicenses()
    }
}
