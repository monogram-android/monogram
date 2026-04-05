package org.monogram.presentation.settings.sessions

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.SessionModel
import org.monogram.domain.repository.SessionRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface SessionsComponent {
    val state: Value<State>
    fun onBackClicked()
    fun terminateSession(id: Long)
    fun onQrCodeScanned(link: String)
    fun confirmAuth()
    fun dismissAuth()
    fun toggleScanner(show: Boolean)
    fun refresh()

    data class State(
        val sessions: List<SessionModel> = emptyList(),
        val isLoading: Boolean = false,
        val isScanning: Boolean = false,
        val pendingLink: String? = null,
        val showConfirmation: Boolean = false
    )
}

class DefaultSessionsComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : SessionsComponent, AppComponentContext by context {

    private val repository: SessionRepository = container.repositories.sessionRepository
    private val _state = MutableValue(SessionsComponent.State())
    override val state: Value<SessionsComponent.State> = _state
    private val scope = componentScope

    init {
        refresh()
    }

    override fun refresh() {
        _state.update { it.copy(isLoading = true) }
        scope.launch {
            val list = repository.getActiveSessions()
            _state.update { it.copy(sessions = list, isLoading = false) }
        }
    }

    override fun terminateSession(id: Long) {
        scope.launch {
            if (repository.terminateSession(id)) refresh()
        }
    }

    override fun onQrCodeScanned(link: String) {
        _state.update { it.copy(pendingLink = link, showConfirmation = true, isScanning = false) }
    }

    override fun confirmAuth() {
        val link = _state.value.pendingLink ?: return
        _state.update { it.copy(showConfirmation = false, isLoading = true, pendingLink = null) }
        scope.launch {
            val success = repository.confirmQrCode(link)
            if (success) refresh() else _state.update { it.copy(isLoading = false) }
        }
    }

    override fun dismissAuth() {
        _state.update { it.copy(showConfirmation = false, pendingLink = null) }
    }

    override fun toggleScanner(show: Boolean) {
        _state.update { it.copy(isScanning = show) }
    }

    override fun onBackClicked() = onBack()
}