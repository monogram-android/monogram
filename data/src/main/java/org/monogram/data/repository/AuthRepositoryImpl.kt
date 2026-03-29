package org.monogram.data.repository

import org.monogram.data.core.coRunCatching
import android.os.Build
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.BuildConfig
import org.monogram.data.datasource.remote.AuthRemoteDataSource
import org.monogram.data.di.TdLibClient
import org.monogram.data.di.TdLibException
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.toDomain
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import java.io.File
import java.util.*

class AuthRepositoryImpl(
    private val remote: AuthRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val tdLibClient: TdLibClient,
    scopeProvider: ScopeProvider
) : AuthRepository {

    private val scope = scopeProvider.appScope
    private val context = tdLibClient.getContext()

    private val _authState = MutableStateFlow<AuthStep>(AuthStep.Loading)
    override val authState = _authState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    init {
        scope.launch {
            updates.authorizationState.collect { update ->
                if (update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
                    sendTdLibParameters()
                }
                val domainState = update.authorizationState.toDomain()
                _authState.update { domainState }
            }
        }
    }

    private suspend fun sendTdLibParameters() {
        coRunCatching {
            val parameters = TdApi.SetTdlibParameters().apply {
                databaseDirectory = File(context.filesDir, "td-db").absolutePath
                filesDirectory = File(context.filesDir, "td-files").absolutePath
                databaseEncryptionKey = byteArrayOf()
                apiId = BuildConfig.API_ID
                apiHash = BuildConfig.API_HASH
                systemLanguageCode = Locale.getDefault().language
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                systemVersion = Build.VERSION.RELEASE
                applicationVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: Exception) {
                    "1.0"
                }
                useMessageDatabase = true
                useFileDatabase = true
                useChatInfoDatabase = true
            }
            remote.setTdlibParameters(parameters)
        }.onFailure { emitError(it) }
    }

    override fun sendPhone(phone: String) {
        scope.launch {
            coRunCatching { remote.setPhoneNumber(phone) }
                .onFailure { emitError(it) }
        }
    }

    override fun resendCode() {
        scope.launch {
            coRunCatching { remote.resendCode() }
                .onFailure { emitError(it) }
        }
    }

    override fun sendCode(code: String) {
        scope.launch {
            val isEmail = (_authState.value as? AuthStep.InputCode)?.isEmailCode == true
            coRunCatching {
                if (isEmail) remote.checkEmailCode(code) else remote.setAuthCode(code)
            }.onFailure { emitError(it) }
        }
    }

    override fun sendPassword(password: String) {
        scope.launch {
            coRunCatching { remote.checkPassword(password) }
                .onFailure { emitError(it) }
        }
    }

    override fun reset() {
        _authState.update { AuthStep.InputPhone }
    }

    private fun emitError(t: Throwable) {
        val error = (t as? TdLibException)?.error
        val errorMessage = error?.message ?: ""

        val message = errorMessage.ifEmpty { t.message ?: "Unknown error" }
        _errors.tryEmit(message)
    }
}