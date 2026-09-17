package org.monogram.feature.auth

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.AuthSession
import org.monogram.core.models.LoadingUi
import org.monogram.core.models.AuthState
import org.monogram.network.bridge.MtprotoClient

interface AuthStore : Store<AuthStore.Intent, AuthStore.State, AuthStore.Label> {
    sealed interface Intent {
        data class PhoneChanged(val value: String) : Intent
        data class CodeChanged(val value: String) : Intent
        data class PasswordChanged(val value: String) : Intent
        data class SetTestDc(val enabled: Boolean) : Intent
        data object SendCode : Intent
        data object ResendCode : Intent
        data object SignIn : Intent
        data class SubmitPassword(val password: String) : Intent
        data object BackToPhone : Intent
    }

    sealed interface Phase {
        data object PhoneEntry : Phase
        data class CodeEntry(
            val phone: String,
            val phoneCodeHash: String,
            val codeType: String = "",
        ) : Phase
        data object PasswordEntry : Phase
        data class Authorized(val session: AuthSession) : Phase
    }

    sealed interface Error {
        data object PhoneRequired : Error
        data object CodeRequired : Error
        data object PasswordRequired : Error
        data class Rpc(val error: TelegramError) : Error
    }

    data class State(
        val phone: String = "",
        val code: String = "",
        val password: String = "",
        val phase: Phase = Phase.PhoneEntry,
        val loading: Boolean = false,
        val error: Error? = null,
        val useTestDc: Boolean = false,
        val loadGeneration: Int = 0,
    ) {
        val loadingUi: LoadingUi
            get() = LoadingUi.flag(visible = loading, generation = loadGeneration)
    }

    sealed interface Label {
        data class Authorized(val session: AuthSession) : Label
    }
}

internal class AuthStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
) {
    fun create(): AuthStore =
        object :
            AuthStore,
            Store<AuthStore.Intent, AuthStore.State, AuthStore.Label> by storeFactory.create(
                name = "AuthStore",
                initialState = AuthStore.State(),
                executorFactory = { ExecutorImpl() },
                reducer = ReducerImpl,
            ) {}

    private sealed interface Msg {
        data class Phone(val value: String) : Msg
        data class Code(val value: String) : Msg
        data class Password(val value: String) : Msg
        data class Loading(val value: Boolean) : Msg
        data class Error(val value: AuthStore.Error?) : Msg
        data class Phase(val value: AuthStore.Phase) : Msg
        data class UseTestDc(val value: Boolean) : Msg
        data object RestartAuth : Msg
        data object BackToPhone : Msg
    }

    private fun isAuthRestart(error: TelegramError): Boolean =
        error.type.contains("AUTH_RESTART")

    private fun isPasswordInvalid(error: TelegramError): Boolean =
        error.type.contains("PASSWORD_HASH_INVALID") ||
            error.type == "PASSWORD_EMPTY" ||
            error.type == "PASSWORD_REQUIRED"

    private fun isStaleAuth(error: TelegramError): Boolean =
        isAuthRestart(error) ||
            error.type.contains("SRP_ID_INVALID") ||
            error.type == "SESSION_PASSWORD_NEEDED" ||
            error.type == "AUTH_KEY_UNREGISTERED" ||
            // Native sign-out signal or a revoked key: the stored key is unusable.
            error.type == "SESSION_INVALIDATED" ||
            error.type == "AUTH_KEY_DUPLICATED" ||
            error.type == "SESSION_REVOKED" ||
            error.type == "SESSION_EXPIRED"

    private inner class ExecutorImpl :
        CoroutineExecutor<AuthStore.Intent, Nothing, AuthStore.State, Msg, AuthStore.Label>() {
        override fun executeIntent(intent: AuthStore.Intent) {
            when (intent) {
                is AuthStore.Intent.PhoneChanged -> dispatch(Msg.Phone(intent.value))
                is AuthStore.Intent.CodeChanged -> dispatch(Msg.Code(intent.value))
                is AuthStore.Intent.PasswordChanged -> dispatch(Msg.Password(intent.value))
                is AuthStore.Intent.SetTestDc -> {
                    dispatch(Msg.UseTestDc(intent.enabled))
                    scope.launch { client.setTestDc(intent.enabled) }
                }
                AuthStore.Intent.SendCode -> sendCode()
                AuthStore.Intent.ResendCode -> resendCode()
                AuthStore.Intent.SignIn -> signIn()
                is AuthStore.Intent.SubmitPassword -> submitPassword(intent.password)
                AuthStore.Intent.BackToPhone -> dispatch(Msg.BackToPhone)
            }
        }

        private fun sendCode() {
            val phone = normalizePhone(state().phone)
            if (phone.isEmpty()) {
                dispatch(Msg.Error(AuthStore.Error.PhoneRequired))
                return
            }
            dispatch(Msg.Phone(phone))
            dispatch(Msg.Loading(true))
            dispatch(Msg.Error(null))
            AppLog.api("auth", "sendCode start")
            scope.launch {
                client.setTestDc(state().useTestDc)
                when (val result = client.sendAuthCode(phone)) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Error(null))
                        dispatch(
                            Msg.Phase(
                                AuthStore.Phase.CodeEntry(
                                    phone = result.value.phone,
                                    phoneCodeHash = result.value.phoneCodeHash,
                                    codeType = result.value.codeType,
                                ),
                            ),
                        )
                    }
                    is Outcome.Err -> {
                        val rpc = result.telegramError
                        if (isStaleAuth(rpc) && state().phase is AuthStore.Phase.PhoneEntry) {
                            dispatch(Msg.RestartAuth)
                            dispatch(Msg.Error(AuthStore.Error.Rpc(rpc)))
                        } else if (isAuthRestart(rpc)) {
                            dispatch(Msg.RestartAuth)
                            dispatch(Msg.Error(AuthStore.Error.Rpc(rpc)))
                        } else {
                            dispatch(Msg.Error(AuthStore.Error.Rpc(rpc)))
                        }
                    }
                }
                dispatch(Msg.Loading(false))
            }
        }

        private fun resendCode() {
            val phase = state().phase as? AuthStore.Phase.CodeEntry ?: return
            dispatch(Msg.Loading(true))
            dispatch(Msg.Error(null))
            AppLog.api("auth", "resendCode start")
            scope.launch {
                when (
                    val result = client.resendAuthCode(
                        phone = phase.phone,
                        phoneCodeHash = phase.phoneCodeHash,
                    )
                ) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Error(null))
                        dispatch(
                            Msg.Phase(
                                AuthStore.Phase.CodeEntry(
                                    phone = result.value.phone,
                                    phoneCodeHash = result.value.phoneCodeHash,
                                    codeType = result.value.codeType,
                                ),
                            ),
                        )
                    }
                    is Outcome.Err -> dispatch(Msg.Error(AuthStore.Error.Rpc(result.telegramError)))
                }
                dispatch(Msg.Loading(false))
            }
        }

        private fun signIn() {
            val phase = state().phase as? AuthStore.Phase.CodeEntry ?: return
            val code = state().code.trim()
            if (code.isEmpty()) {
                dispatch(Msg.Error(AuthStore.Error.CodeRequired))
                return
            }
            dispatch(Msg.Loading(true))
            dispatch(Msg.Error(null))
            scope.launch {
                when (
                    val result = client.signIn(
                        phone = phase.phone,
                        phoneCodeHash = phase.phoneCodeHash,
                        code = code,
                    )
                ) {
                    is Outcome.Ok -> when (val auth = result.value) {
                        is AuthState.Authorized -> {
                            dispatch(Msg.Phase(AuthStore.Phase.Authorized(auth.session)))
                            publish(AuthStore.Label.Authorized(auth.session))
                        }
                        AuthState.AwaitingPassword -> {
                            dispatch(Msg.Phase(AuthStore.Phase.PasswordEntry))
                        }
                        else -> dispatch(
                            Msg.Error(
                                AuthStore.Error.Rpc(
                                    TelegramError.parse("Unexpected auth state"),
                                ),
                            ),
                        )
                    }
                    is Outcome.Err -> {
                        val rpc = result.telegramError
                        if (isAuthRestart(rpc)) {
                            dispatch(Msg.RestartAuth)
                            val phone = state().phone.trim()
                            if (phone.isNotEmpty()) {
                                dispatch(Msg.Loading(false))
                                sendCode()
                                return@launch
                            }
                        } else {
                            dispatch(Msg.Error(AuthStore.Error.Rpc(rpc)))
                        }
                    }
                }
                dispatch(Msg.Loading(false))
            }
        }

        private fun submitPassword(rawPassword: String) {
            if (state().phase !is AuthStore.Phase.PasswordEntry) return
            // Do not trim: cloud passwords may contain leading/trailing spaces.
            val password = rawPassword.ifEmpty { state().password }
            if (password.isEmpty()) {
                dispatch(Msg.Error(AuthStore.Error.PasswordRequired))
                return
            }
            dispatch(Msg.Password(password))
            dispatch(Msg.Loading(true))
            dispatch(Msg.Error(null))
            scope.launch {
                when (val result = client.checkPassword(password)) {
                    is Outcome.Ok -> {
                        dispatch(Msg.Phase(AuthStore.Phase.Authorized(result.value.session)))
                        publish(AuthStore.Label.Authorized(result.value.session))
                    }
                    is Outcome.Err -> {
                        val rpc = result.telegramError
                        when {
                            isPasswordInvalid(rpc) ->
                                dispatch(Msg.Error(AuthStore.Error.Rpc(rpc)))
                            isStaleAuth(rpc) -> {
                                dispatch(Msg.RestartAuth)
                                val phone = state().phone.trim()
                                if (phone.isNotEmpty()) {
                                    dispatch(Msg.Loading(false))
                                    sendCode()
                                    return@launch
                                }
                            }
                            else ->
                                dispatch(Msg.Error(AuthStore.Error.Rpc(rpc)))
                        }
                    }
                }
                dispatch(Msg.Loading(false))
            }
        }
    }

    private object ReducerImpl : Reducer<AuthStore.State, Msg> {
        override fun AuthStore.State.reduce(msg: Msg): AuthStore.State = when (msg) {
            is Msg.Phone -> copy(phone = msg.value, error = null)
            is Msg.Code -> copy(code = msg.value, error = null)
            is Msg.Password -> copy(password = msg.value, error = null)
            is Msg.Loading -> if (msg.value) {
                copy(loading = true, loadGeneration = loadGeneration + 1)
            } else {
                copy(loading = false)
            }
            is Msg.Error -> copy(error = msg.value)
            is Msg.Phase -> copy(phase = msg.value, error = null)
            is Msg.UseTestDc -> copy(useTestDc = msg.value, error = null)
            Msg.RestartAuth -> copy(
                phase = AuthStore.Phase.PhoneEntry,
                code = "",
                password = "",
                loading = false,
                error = AuthStore.Error.Rpc(TelegramError.parse("AUTH_RESTART")),
            )
            Msg.BackToPhone -> copy(
                phase = AuthStore.Phase.PhoneEntry,
                code = "",
                password = "",
                loading = false,
                error = null,
            )
        }
    }
}

internal fun normalizePhone(raw: String): String {
    val compact = raw.trim().replace(" ", "")
    if (compact.isEmpty() || compact == "+") return ""
    return if (compact.startsWith("+")) compact else "+$compact"
}
