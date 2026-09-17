package org.monogram.feature.auth

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import org.monogram.core.models.AuthSession
import org.monogram.network.bridge.MtprotoClient

@OptIn(ExperimentalCoroutinesApi::class)
class AuthComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    client: MtprotoClient,
    private val onAuthorized: (AuthSession) -> Unit,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        AuthStoreFactory(storeFactory, client).create()
    }

    val state: StateFlow<AuthStore.State> = store.stateFlow

    init {
        lifecycle.doOnDestroy { /* store cleared by InstanceKeeper */ }
    }

    fun onPhoneChanged(value: String) = store.accept(AuthStore.Intent.PhoneChanged(value))
    fun onCodeChanged(value: String) = store.accept(AuthStore.Intent.CodeChanged(value))
    fun onPasswordChanged(value: String) = store.accept(AuthStore.Intent.PasswordChanged(value))
    fun onSendCode() = store.accept(AuthStore.Intent.SendCode)
    fun onSignIn() = store.accept(AuthStore.Intent.SignIn)
    fun onSubmitPassword(password: String = "") =
        store.accept(AuthStore.Intent.SubmitPassword(password))
    fun onBackToPhone() = store.accept(AuthStore.Intent.BackToPhone)
    fun onIntent(intent: AuthStore.Intent) = store.accept(intent)

    fun consumeAuthorized() {
        val phase = state.value.phase
        if (phase is AuthStore.Phase.Authorized) {
            onAuthorized(phase.session)
        }
    }
}
