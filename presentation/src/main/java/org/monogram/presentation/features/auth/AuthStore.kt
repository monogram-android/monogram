package org.monogram.presentation.features.auth

import com.arkivanov.mvikotlin.core.store.Store

interface AuthStore : Store<AuthStore.Intent, AuthComponent.Model, AuthStore.Label> {

    sealed class Intent {
        data class PhoneEntered(val phone: String) : Intent()
        data class CodeEntered(val code: String) : Intent()
        object ResendCode : Intent()
        data class PasswordEntered(val password: String) : Intent()
        object BackToPhone : Intent()
        object ProxyClicked : Intent()
        object DismissError : Intent()
        object Reset : Intent()
        data class UpdateModel(val model: AuthComponent.Model) : Intent()
    }

    sealed class Label {
        object OpenProxy : Label()
    }
}
