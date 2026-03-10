package org.monogram.presentation.features.auth

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.features.auth.AuthStore.Intent
import org.monogram.presentation.features.auth.AuthStore.Label

class AuthStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultAuthComponent
) {

    fun create(): AuthStore =
        object : AuthStore, Store<Intent, AuthComponent.Model, Label> by storeFactory.create(
            name = "AuthStore",
            initialState = component.model.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, AuthComponent.Model, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                is Intent.PhoneEntered -> component.onPhoneEntered(intent.phone)
                is Intent.CodeEntered -> component.onCodeEntered(intent.code)
                Intent.ResendCode -> component.onResendCode()
                is Intent.PasswordEntered -> component.onPasswordEntered(intent.password)
                Intent.BackToPhone -> component.onBackToPhone()
                Intent.ProxyClicked -> component.onProxyClicked()
                Intent.DismissError -> component.dismissError()
                Intent.Reset -> component.onReset()
                is Intent.UpdateModel -> dispatch(Message.UpdateModel(intent.model))
            }
        }
    }

    private object ReducerImpl : Reducer<AuthComponent.Model, Message> {
        override fun AuthComponent.Model.reduce(msg: Message): AuthComponent.Model =
            when (msg) {
                is Message.UpdateModel -> msg.model
            }
    }

    sealed class Message {
        data class UpdateModel(val model: AuthComponent.Model) : Message()
    }
}
