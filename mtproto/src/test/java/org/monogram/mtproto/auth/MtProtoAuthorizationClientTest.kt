package org.monogram.mtproto.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.crypto.DhParameterValidatorTest
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.tl.generated.cloud.layer223.EmailVerificationCode
import org.monogram.mtproto.tl.generated.cloud.layer223.InputCheckPasswordSrp_5100d694df
import org.monogram.mtproto.tl.generated.cloud.layer223.PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow
import org.monogram.mtproto.tl.generated.cloud.layer223.PasswordKdfAlgoUnknown
import org.monogram.mtproto.tl.generated.cloud.layer223.SecurePasswordKdfAlgoUnknown
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Password_ac67a26d5c
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CheckPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ResendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignIn
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignUp
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcException
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoAuthorizationClientTest {
    @Test
    fun buildsSendCodeRequestWithCallerSuppliedSettings() {
        val transport = CapturingTransport()
        val client = MtProtoAuthorizationClient(transport)
        val settings = CodeSettings_3f851bba91(
            allowFlashcall = false,
            currentNumber = true,
            allowAppHash = true,
            allowMissedCall = false,
            allowFirebase = false,
            unknownNumber = false,
            logoutTokens = null,
            token = null,
            appSandbox = null,
        )

        assertThrows(Capture::class.java) {
            runBlocking { client.sendCode("+10000000000", settings, 12345, "hash") }
        }

        val request = transport.method as SendCode
        assertEquals("+10000000000", request.phoneNumber)
        assertEquals(12345, request.apiId)
        assertEquals("hash", request.apiHash)
        assertEquals(settings, request.settings)
    }

    @Test
    fun buildsResendAndSignInRequests() {
        val transport = CapturingTransport()
        val client = MtProtoAuthorizationClient(transport)

        assertThrows(Capture::class.java) {
            runBlocking { client.resendCode("+10000000000", "hash", "user") }
        }
        val resend = transport.method as ResendCode
        assertEquals("hash", resend.phoneCodeHash)
        assertEquals("user", resend.reason)

        assertThrows(Capture::class.java) {
            runBlocking { client.signIn("+10000000000", "hash", "12345") }
        }
        val signIn = transport.method as SignIn
        assertEquals("+10000000000", signIn.phoneNumber)
        assertEquals("hash", signIn.phoneCodeHash)
        assertEquals("12345", signIn.phoneCode)
    }

    @Test
    fun buildsEmailCodeSignInRequest() {
        val transport = CapturingTransport()
        val client = MtProtoAuthorizationClient(transport)

        assertThrows(Capture::class.java) {
            runBlocking { client.signInWithEmailCode("+10000000000", "hash", "123456") }
        }

        val signIn = transport.method as SignIn
        assertEquals("+10000000000", signIn.phoneNumber)
        assertEquals("hash", signIn.phoneCodeHash)
        assertEquals(null, signIn.phoneCode)
        assertEquals(EmailVerificationCode("123456"), signIn.emailVerification)
    }

    @Test
    fun buildsSignupRequestWithVerifiedPhoneAndName() {
        val transport = CapturingTransport()
        val client = MtProtoAuthorizationClient(transport)

        assertThrows(Capture::class.java) {
            runBlocking { client.signUp("+10000000000", "hash", "Ada", "Lovelace") }
        }

        val signUp = transport.method as SignUp
        assertEquals(false, signUp.noJoinedNotifications)
        assertEquals("+10000000000", signUp.phoneNumber)
        assertEquals("hash", signUp.phoneCodeHash)
        assertEquals("Ada", signUp.firstName)
        assertEquals("Lovelace", signUp.lastName)
    }

    @Test
    fun rejectsInvalidAuthInputsBeforeTransport() {
        val client = MtProtoAuthorizationClient(CapturingTransport())
        val settings = CodeSettings_3f851bba91(false, false, false, false, false, false, null, null, null)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.sendCode("", settings, 12345, "hash") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.resendCode("+1", "", null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.signIn("+1", "hash", "") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.signUp("+1", "hash", "", "") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.signInWithEmailCode("+1", "hash", "") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.checkPassword("") }
        }
    }

    @Test
    fun fetchesPasswordMetadataWithoutExposingSrpParameters() = runBlocking {
        val transport = ScriptedTransport(passwordConfiguration(hint = "hint", hasRecovery = true))
        val client = MtProtoAuthorizationClient(transport, EntropySource { it.fill(1) })

        assertEquals(MtProtoPasswordChallengeInfo("hint", true), client.getPasswordChallengeInfo())
        assertEquals(listOf(GetPassword), transport.methods)
    }

    @Test
    fun fetchesFreshChallengeAndBuildsCheckPasswordRequest() = runBlocking {
        val authorization = Authorization_d8660c55a3(false, null, null, null, UserEmpty(1L))
        val transport = ScriptedTransport(passwordConfiguration(srpId = 42L), authorization)
        val client = MtProtoAuthorizationClient(transport, EntropySource { it.fill(3) })

        assertEquals(authorization, client.checkPassword("password"))
        assertEquals(GetPassword, transport.methods[0])
        val request = transport.methods[1] as CheckPassword
        val proof = request.password as InputCheckPasswordSrp_5100d694df
        assertEquals(42L, proof.srpId)
        assertEquals(256, proof.a.toByteArray().size)
        assertEquals(32, proof.m1.toByteArray().size)
    }

    @Test
    fun retriesOnceWithFreshChallengeOnlyForInvalidSrpId() = runBlocking {
        val authorization = Authorization_d8660c55a3(false, null, null, null, UserEmpty(1L))
        val transport = ScriptedTransport(
            passwordConfiguration(srpId = 1L),
            MtProtoRpcException(400, "SRP_ID_INVALID"),
            passwordConfiguration(srpId = 2L),
            authorization,
        )
        val client = MtProtoAuthorizationClient(transport, EntropySource { it.fill(4) })

        assertEquals(authorization, client.checkPassword("password"))
        assertEquals(listOf(GetPassword, CheckPassword::class, GetPassword, CheckPassword::class), transport.methodKinds())
        val retriedProof = (transport.methods.last() as CheckPassword).password as InputCheckPasswordSrp_5100d694df
        assertEquals(2L, retriedProof.srpId)
    }

    @Test
    fun rejectsUnsupportedOrIncompletePasswordChallengeBeforeCheck() {
        val unsupported = passwordConfiguration().copy(currentAlgo = PasswordKdfAlgoUnknown)
        val missingB = passwordConfiguration().copy(srpB = null)

        listOf(unsupported, missingB).forEach { configuration ->
            val transport = ScriptedTransport(configuration)
            val client = MtProtoAuthorizationClient(transport, EntropySource { it.fill(5) })
            assertThrows(IllegalStateException::class.java) { runBlocking { client.checkPassword("password") } }
            assertEquals(listOf(GetPassword), transport.methods)
        }
    }

    private fun passwordConfiguration(
        hint: String? = null,
        hasRecovery: Boolean = false,
        srpId: Long = 1L,
    ): Password_ac67a26d5c = Password_ac67a26d5c(
        hasRecovery = hasRecovery,
        hasSecureValues = false,
        hasPassword = true,
        currentAlgo = PasswordKdfAlgoSha256Sha256Pbkdf2Hmacsha512Iter100000Sha256ModPow(
            salt1 = TlBytes.copyOf(byteArrayOf(1, 2, 3)),
            salt2 = TlBytes.copyOf(byteArrayOf(4, 5, 6)),
            g = 3,
            p = TlBytes.copyOf(DhParameterValidatorTest.PRIME),
        ),
        srpB = TlBytes.copyOf(ByteArray(248) { 1 }),
        srpId = srpId,
        hint = hint,
        emailUnconfirmedPattern = null,
        newAlgo = PasswordKdfAlgoUnknown,
        newSecureAlgo = SecurePasswordKdfAlgoUnknown,
        secureRandom = TlBytes.copyOf(byteArrayOf(1)),
        pendingResetDate = null,
        loginEmailPattern = null,
    )

    private class Capture : RuntimeException()

    private class CapturingTransport : MtProtoRpcTransport {
        var method: TlMethod<*>? = null

        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            throw Capture()
        }

        override fun close() = Unit
    }

    private class ScriptedTransport(vararg responses: Any) : MtProtoRpcTransport {
        private val responses = ArrayDeque(responses.toList())
        val methods = mutableListOf<TlMethod<*>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            methods += method
            return when (val response = responses.removeFirst()) {
                is Throwable -> throw response
                else -> response as R
            }
        }

        fun methodKinds(): List<Any> = methods.map { if (it is GetPassword) it else it::class }

        override fun close() = Unit
    }
}
