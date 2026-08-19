package org.monogram.data.mtproto

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus
import org.monogram.mtproto.transport.MtProtoRpcException

@OptIn(ExperimentalCoroutinesApi::class)
class MtProtoAuthRepositoryTest {
    @Test
    fun `drives phone code and password through one session`() = runTest {
        val inputCode = AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5, canResend = true)
        val inputPassword = AuthStep.InputPassword("hint", hasRecoveryEmail = true)
        val handle = FakeHandle(
            requestCode = { inputCode },
            resendCode = { inputCode },
            submitCode = { inputPassword },
            submitPassword = { AuthStep.Ready },
        )
        val slots = mutableListOf<String>()
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { slot ->
                slots += slot
                handle
            },
            scope = backgroundScope,
        )

        assertEquals(AuthStep.InputPhone, repository.authState.value)
        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()
        assertEquals(inputCode, repository.authState.value)

        repository.resendCode()
        testScheduler.runCurrent()
        assertEquals(inputCode, repository.authState.value)

        repository.sendCode("12345")
        testScheduler.runCurrent()
        assertEquals(inputPassword, repository.authState.value)

        repository.sendPassword("secret")
        testScheduler.runCurrent()
        assertEquals(AuthStep.Ready, repository.authState.value)
        assertEquals(listOf("default"), slots)
        assertEquals(listOf("+10000000000"), handle.phones)
        assertEquals(1, handle.resendCalls.get())
        assertEquals(listOf("12345"), handle.codes)
        assertEquals(listOf("secret"), handle.passwords)
        assertEquals(1, handle.closeCalls.get())
        assertEquals(AuthUiStatus.Idle, repository.authUiStatus.value)
    }

    @Test
    fun `maps failure and retry reuses session while preserving state`() = runTest {
        var attempt = 0
        val inputCode = AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 6)
        val handle = FakeHandle(
            requestCode = {
                attempt++
                if (attempt == 1) throw MtProtoRpcException(400, "PHONE_CODE_INVALID")
                inputCode
            },
        )
        val openCalls = AtomicInteger()
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory {
                openCalls.incrementAndGet()
                handle
            },
            scope = backgroundScope,
        )
        val error = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            repository.errors.first()
        }

        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()
        assertEquals(AuthError.InvalidCode, error.await())
        assertEquals(AuthStep.InputPhone, repository.authState.value)
        assertEquals(AuthUiStatus.Idle, repository.authUiStatus.value)

        repository.retryLastAction()
        testScheduler.runCurrent()
        assertEquals(inputCode, repository.authState.value)
        assertEquals(2, attempt)
        assertEquals(1, openCalls.get())
    }

    @Test
    fun `reset closes handle and rejects cancelled stale completion`() = runTest {
        val releaseRequest = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val inputCode = AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5)
        val handle = FakeHandle(
            requestCode = {
                started.complete(Unit)
                withContext(NonCancellable) { releaseRequest.await() }
                inputCode
            },
        )
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { handle },
            scope = backgroundScope,
        )
        val error = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            repository.errors.first()
        }

        repository.sendPhone("+10000000000")
        repository.sendPhone("+12222222222")
        testScheduler.runCurrent()
        started.await()
        assertEquals(1, handle.phones.size)

        repository.reset()
        assertEquals(AuthStep.InputPhone, repository.authState.value)
        assertEquals(AuthUiStatus.Idle, repository.authUiStatus.value)
        assertEquals(1, handle.closeCalls.get())

        releaseRequest.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(AuthStep.InputPhone, repository.authState.value)
        assertFalse(error.isCompleted)
    }

    private class FakeHandle(
        private val requestCode: suspend (String) -> AuthStep = { error("unexpected phone") },
        private val resendCode: suspend () -> AuthStep = { error("unexpected resend") },
        private val submitCode: suspend (String) -> AuthStep = { error("unexpected code") },
        private val submitPassword: suspend (String) -> AuthStep = { error("unexpected password") },
    ) : MtProtoAuthSessionHandle {
        val phones = mutableListOf<String>()
        val codes = mutableListOf<String>()
        val passwords = mutableListOf<String>()
        val resendCalls = AtomicInteger()
        val closeCalls = AtomicInteger()

        override fun currentState(): AuthStep = AuthStep.InputPhone

        override suspend fun requestCode(phone: String): AuthStep {
            phones += phone
            return requestCode.invoke(phone)
        }

        override suspend fun resendCode(): AuthStep {
            resendCalls.incrementAndGet()
            return resendCode.invoke()
        }

        override suspend fun submitCode(code: String): AuthStep {
            codes += code
            return submitCode.invoke(code)
        }

        override suspend fun submitPassword(password: String): AuthStep {
            passwords += password
            return submitPassword.invoke(password)
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
