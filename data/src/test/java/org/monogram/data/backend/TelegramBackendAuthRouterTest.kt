package org.monogram.data.backend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBackendAuthRouterTest {
    @Test
    fun `MTProto selection does not construct legacy auth`() = runTest {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        val legacy = FakeAuthRepository(AuthStep.InputPhone)
        val mtProto = FakeAuthRepository(AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5))
        var legacyFactoryCalls = 0
        var mtProtoFactoryCalls = 0
        val router = TelegramBackendAuthRouter(
            selectionStore = selection,
            legacyFactory = {
                legacyFactoryCalls++
                legacy
            },
            mtProtoFactory = {
                mtProtoFactoryCalls++
                mtProto
            },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()

        assertEquals(AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5), router.authState.value)
        assertEquals(0, legacyFactoryCalls)
        assertEquals(1, mtProtoFactoryCalls)

        router.sendCode("12345")
        assertEquals(emptyList<String>(), legacy.codes)
        assertEquals(listOf("12345"), mtProto.codes)
    }

    @Test
    fun `constructs legacy auth only after legacy is selected`() = runTest {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        var legacyFactoryCalls = 0
        var mtProtoFactoryCalls = 0
        val router = TelegramBackendAuthRouter(
            selectionStore = selection,
            legacyFactory = {
                legacyFactoryCalls++
                FakeAuthRepository(AuthStep.InputPhone)
            },
            mtProtoFactory = {
                mtProtoFactoryCalls++
                FakeAuthRepository(AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5))
            },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()
        selection.backend.value = TelegramBackendKind.LEGACY
        testScheduler.runCurrent()

        assertEquals(AuthStep.InputPhone, router.authState.value)
        assertEquals(1, legacyFactoryCalls)
        assertEquals(1, mtProtoFactoryCalls)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        val backend = MutableStateFlow(initial)

        override suspend fun get(accountId: String): TelegramBackendKind = backend.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = backend
        override suspend fun select(accountId: String, backend: TelegramBackendKind) {
            this.backend.value = backend
        }
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeAuthRepository(initialState: AuthStep) : AuthRepository {
        private val _authState = MutableStateFlow(initialState)
        override val authState = _authState.asStateFlow()
        private val _authUiStatus = MutableStateFlow<AuthUiStatus>(AuthUiStatus.Idle)
        override val authUiStatus = _authUiStatus.asStateFlow()
        private val _errors = MutableSharedFlow<AuthError>(extraBufferCapacity = 1)
        override val errors = _errors.asSharedFlow()
        val codes = mutableListOf<String>()

        override fun sendPhone(phone: String) = Unit
        override fun resendCode() = Unit
        override fun sendCode(code: String) {
            codes += code
        }
        override fun sendPassword(password: String) = Unit
        override fun retryLastAction() = Unit
        override fun reset() = Unit
    }
}
