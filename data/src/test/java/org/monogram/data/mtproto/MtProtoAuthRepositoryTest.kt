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
    fun `persists authorization before publishing ready`() = runTest {
        val storedSlots = mutableListOf<String>()
        val handle = FakeHandle(
            requestCode = { AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5) },
            submitCode = { AuthStep.Ready },
        )
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { handle },
            scope = backgroundScope,
            authorizationStore = object : MtProtoAccountAuthorizationStore {
                override suspend fun isAuthorized(accountSlot: String) = false
                override suspend fun markAuthorized(accountSlot: String) { storedSlots += accountSlot }
                override suspend fun clear(accountSlot: String) = Unit
            },
        )

        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()
        repository.sendCode("12345")
        testScheduler.runCurrent()

        assertEquals(listOf("default"), storedSlots)
        assertEquals(AuthStep.Ready, repository.authState.value)
    }

    @Test
    fun `restores ready only after authenticated session validation`() = runTest {
        val restoreCalls = AtomicInteger()
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { error("unexpected auth session") },
            scope = backgroundScope,
            authorizedSessionRestorer = MtProtoAuthorizedSessionRestorer {
                restoreCalls.incrementAndGet()
                true
            },
        )

        assertEquals(AuthStep.Loading, repository.authState.value)
        testScheduler.runCurrent()

        assertEquals(1, restoreCalls.get())
        assertEquals(AuthStep.Ready, repository.authState.value)
    }

    @Test
    fun `does not restore ready when authenticated session validation fails`() = runTest {
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { error("unexpected auth session") },
            scope = backgroundScope,
            authorizedSessionRestorer = MtProtoAuthorizedSessionRestorer { false },
        )

        assertEquals(AuthStep.Loading, repository.authState.value)
        testScheduler.runCurrent()

        assertEquals(AuthStep.InputPhone, repository.authState.value)
    }

    @Test
    fun `keeps authentication interactive when session restoration throws`() = runTest {
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { error("unexpected auth session") },
            scope = backgroundScope,
            authorizedSessionRestorer = MtProtoAuthorizedSessionRestorer {
                throw IllegalStateException("transport unavailable")
            },
        )

        assertEquals(AuthStep.Loading, repository.authState.value)
        testScheduler.runCurrent()

        assertEquals(AuthStep.InputPhone, repository.authState.value)
    }

    @Test
    fun `submits registration only after signup state and closes ready session`() = runTest {
        val handle = FakeHandle(
            requestCode = { AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5) },
            submitCode = { AuthStep.InputSignUp },
            submitSignUp = { _, _ -> AuthStep.Ready },
        )
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { handle },
            scope = backgroundScope,
        )

        repository.signUp("Ada", "Lovelace")
        testScheduler.runCurrent()
        assertEquals(emptyList<Pair<String, String>>(), handle.signUps)

        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()
        repository.sendCode("12345")
        testScheduler.runCurrent()
        assertEquals(AuthStep.InputSignUp, repository.authState.value)

        repository.signUp("Ada", "Lovelace")
        testScheduler.runCurrent()
        assertEquals(listOf("Ada" to "Lovelace"), handle.signUps)
        assertEquals(AuthStep.Ready, repository.authState.value)
        assertEquals(1, handle.closeCalls.get())
    }

    @Test
    fun `submits login email only from setup state`() = runTest {
        val handle = FakeHandle(
            requestCode = { AuthStep.InputLoginEmail },
            submitLoginEmail = {
                AuthStep.InputCode(
                    delivery = AuthCodeDelivery.EMAIL,
                    codeLength = 6,
                    isEmailCode = true,
                    isLoginEmailSetupCode = true,
                    emailPattern = "e***",
                )
            },
        )
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory { handle },
            scope = backgroundScope,
        )

        repository.sendLoginEmail("ada@example.com")
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), handle.loginEmails)

        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()
        assertEquals(AuthStep.InputLoginEmail, repository.authState.value)

        repository.sendLoginEmail("ada@example.com")
        testScheduler.runCurrent()
        assertEquals(listOf("ada@example.com"), handle.loginEmails)
        assertEquals(
            AuthStep.InputCode(
                delivery = AuthCodeDelivery.EMAIL,
                codeLength = 6,
                isEmailCode = true,
                isLoginEmailSetupCode = true,
                emailPattern = "e***",
            ),
            repository.authState.value,
        )
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
    fun `reopens on migrated DC and retries phone request once`() = runTest {
        val initial = FakeHandle(
            requestCode = { throw MtProtoRpcException(303, "PHONE_MIGRATE_5") },
        )
        val migrated = FakeHandle(
            requestCode = { AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5) },
        )
        val openedDcs = mutableListOf<Int>()
        val savedDcs = mutableListOf<Int>()
        val repository = MtProtoAuthRepository(
            sessionFactory = object : MtProtoAuthSessionHandleFactory {
                override suspend fun open(accountSlot: String): MtProtoAuthSessionHandle = initial

                override suspend fun open(accountSlot: String, dcId: Int): MtProtoAuthSessionHandle {
                    openedDcs += dcId
                    return migrated
                }
            },
            scope = backgroundScope,
            accountDcStore = object : MtProtoAccountDcStore {
                override suspend fun get(accountSlot: String): Int? = null
                override suspend fun save(accountSlot: String, dcId: Int) { savedDcs += dcId }
                override suspend fun delete(accountSlot: String) = Unit
            },
        )

        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()

        assertEquals(listOf(5), openedDcs)
        assertEquals(listOf(5), savedDcs)
        assertEquals(listOf("+10000000000"), initial.phones)
        assertEquals(listOf("+10000000000"), migrated.phones)
        assertEquals(1, initial.closeCalls.get())
        assertEquals(AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5), repository.authState.value)
    }

    @Test
    fun `reopens once on auth restart and resends phone request`() = runTest {
        val initial = FakeHandle(
            requestCode = { throw MtProtoRpcException(500, "AUTH_RESTART") },
        )
        val restarted = FakeHandle(
            requestCode = { AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5) },
        )
        val openCalls = AtomicInteger()
        val repository = MtProtoAuthRepository(
            sessionFactory = MtProtoAuthSessionHandleFactory {
                when (openCalls.incrementAndGet()) {
                    1 -> initial
                    2 -> restarted
                    else -> error("AUTH_RESTART must only replace the session once")
                }
            },
            scope = backgroundScope,
        )

        repository.sendPhone("+10000000000")
        testScheduler.runCurrent()

        assertEquals(2, openCalls.get())
        assertEquals(listOf("+10000000000"), initial.phones)
        assertEquals(listOf("+10000000000"), restarted.phones)
        assertEquals(1, initial.closeCalls.get())
        assertEquals(AuthStep.InputCode(AuthCodeDelivery.SMS, codeLength = 5), repository.authState.value)
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
        private val submitSignUp: suspend (String, String) -> AuthStep = { _, _ -> error("unexpected sign-up") },
        private val submitLoginEmail: suspend (String) -> AuthStep = { error("unexpected login email") },
    ) : MtProtoAuthSessionHandle {
        val phones = mutableListOf<String>()
        val codes = mutableListOf<String>()
        val passwords = mutableListOf<String>()
        val signUps = mutableListOf<Pair<String, String>>()
        val loginEmails = mutableListOf<String>()
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

        override suspend fun submitSignUp(firstName: String, lastName: String): AuthStep {
            signUps += firstName to lastName
            return submitSignUp.invoke(firstName, lastName)
        }

        override suspend fun submitLoginEmail(email: String): AuthStep {
            loginEmails += email
            return submitLoginEmail.invoke(email)
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
