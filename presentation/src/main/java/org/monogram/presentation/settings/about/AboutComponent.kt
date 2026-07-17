package org.monogram.presentation.settings.about

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.GitHubCommitModel
import org.monogram.domain.models.UpdateState
import org.monogram.domain.repository.GitHubCommitRepository
import org.monogram.domain.repository.UpdateRepository
import org.monogram.presentation.BuildConfig
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

data class RecentCommitsState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val commits: List<GitHubCommitModel> = emptyList(),
    val errorMessage: String? = null
)

interface AboutComponent {
    val updateState: StateFlow<UpdateState>
    val tdLibVersion: StateFlow<String>
    val tdLibCommitHash: StateFlow<String>
    val recentCommitsState: StateFlow<RecentCommitsState>
    val buildBranch: String
    val buildCommitHash: String
    val buildTimeMillis: Long
    val currentCommitUrl: String?
    val hasOpenSourceLicenses: Boolean
    fun onBackClicked()
    fun checkForUpdates()
    fun downloadUpdate()
    fun installUpdate()
    fun onRecentCommitsClicked()
    fun onRecentCommitsDismissed()
    fun retryRecentCommits()
    fun onTermsOfServiceClicked()
    fun onOpenSourceLicensesClicked()
}

class DefaultAboutComponent(
    context: AppComponentContext,
    private val updateRepository: UpdateRepository,
    private val gitHubCommitRepository: GitHubCommitRepository,
    private val onBack: () -> Unit,
    private val onTermsOfService: () -> Unit,
    private val onOpenSourceLicenses: () -> Unit
) : AboutComponent, AppComponentContext by context {

    private val scope = componentScope
    private val isTelemtBuild = BuildConfig.ENABLE_TELEMT_DNS
    private val stringProvider = container.utils.stringProvider()
    override val hasOpenSourceLicenses: Boolean = BuildConfig.HAS_OSS_LICENSES
    override val buildBranch: String = BuildConfig.BUILD_BRANCH
    override val buildCommitHash: String = BuildConfig.BUILD_COMMIT_HASH
    override val buildTimeMillis: Long = BuildConfig.BUILD_TIME_MILLIS
    override val currentCommitUrl: String? =
        buildCommitHash.takeIf { it.isNotBlank() && it != "unknown" }
            ?.let { "https://github.com/monogram-android/monogram/commit/$it" }

    private val _tdLibVersion = MutableStateFlow(stringProvider.getString("loading_text"))
    override val tdLibVersion: StateFlow<String> = _tdLibVersion.asStateFlow()

    private val _tdLibCommitHash = MutableStateFlow("")
    override val tdLibCommitHash: StateFlow<String> = _tdLibCommitHash.asStateFlow()

    private val _recentCommitsState = MutableStateFlow(RecentCommitsState())
    override val recentCommitsState: StateFlow<RecentCommitsState> =
        _recentCommitsState.asStateFlow()

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

    override fun onRecentCommitsClicked() {
        _recentCommitsState.update { it.copy(isVisible = true) }
        if (_recentCommitsState.value.commits.isEmpty() && !_recentCommitsState.value.isLoading) {
            loadRecentCommits(forceRefresh = true)
        }
    }

    override fun onRecentCommitsDismissed() {
        _recentCommitsState.update { it.copy(isVisible = false) }
    }

    override fun retryRecentCommits() {
        loadRecentCommits(forceRefresh = true)
    }

    override fun onTermsOfServiceClicked() {
        onTermsOfService()
    }

    override fun onOpenSourceLicensesClicked() {
        onOpenSourceLicenses()
    }

    private fun loadRecentCommits(forceRefresh: Boolean) {
        val currentState = _recentCommitsState.value
        if (currentState.isLoading) return

        val ref = buildBranch
            .takeIf { it.isNotBlank() && it != "unknown" && it != "detached" }
            ?: buildCommitHash

        scope.launch {
            _recentCommitsState.update {
                it.copy(
                    isVisible = true,
                    isLoading = true,
                    commits = if (forceRefresh) emptyList() else it.commits,
                    errorMessage = null
                )
            }

            val result = gitHubCommitRepository.getRecentCommits(ref)
            result.fold(
                onSuccess = { commits ->
                    _recentCommitsState.update {
                        it.copy(
                            isVisible = true,
                            isLoading = false,
                            commits = commits,
                            errorMessage = null
                        )
                    }
                },
                onFailure = {
                    _recentCommitsState.update {
                        it.copy(
                            isVisible = true,
                            isLoading = false,
                            errorMessage = stringProvider.getString("recent_commits_error")
                        )
                    }
                }
            )
        }
    }
}
