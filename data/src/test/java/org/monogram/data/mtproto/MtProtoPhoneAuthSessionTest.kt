package org.monogram.data.mtproto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthCodeInputKind
import org.monogram.domain.repository.AuthStep
import org.monogram.mtproto.auth.MtProtoAuthorizationApi
import org.monogram.mtproto.auth.MtProtoPasswordChallengeInfo
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.AuthorizationSignUpRequired
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.CodeTypeSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodePaymentRequired
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeEmailCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSmsWord
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_f9e8fc1d16
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9
import org.monogram.mtproto.transport.MtProtoRpcException

class MtProtoPhoneAuthSessionTest {
    @Test
    fun retainsPhoneHashMapsCodeAndReusesHashForSignIn() = runBlocking {
        val api = FakeApi(
            sentCodes = ArrayDeque(listOf(
                SentCode_f9e8fc1d16(SentCodeTypeSms(5), "hash-1", CodeTypeSms, 30),
                SentCode_f9e8fc1d16(SentCodeTypeSms(6), "hash-2", null, 0),
            )),
            authorization = Authorization_d8660c55a3(false, null, null, null, fakeUser()),
        )
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())

        val first = session.requestCode("+10000000000") as AuthStep.InputCode
        assertEquals(AuthCodeDelivery.SMS, first.delivery)
        assertEquals(5, first.codeLength)
        assertEquals(AuthCodeDelivery.SMS, first.nextDelivery)

        val resent = session.resendCode() as AuthStep.InputCode
        assertEquals(6, resent.codeLength)
        assertEquals(false, resent.canResend)
        assertEquals("hash-1", api.lastHash)
        assertThrows(IllegalStateException::class.java) { runBlocking { session.resendCode() } }

        assertEquals(AuthStep.Ready, session.submitCode("123456"))
        assertEquals("hash-2", api.lastHash)
        assertThrows(IllegalStateException::class.java) { runBlocking { session.resendCode() } }
        Unit
    }

    @Test
    fun rejectsResendAndSubmitBeforeCodeRequest() {
        val session = MtProtoPhoneAuthSession(FakeApi(), 12345, "hash", settings())
        assertThrows(IllegalStateException::class.java) { runBlocking { session.resendCode() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { session.submitCode("1234") } }
    }

    @Test
    fun rejectsUnsupportedPaymentAndEmailResultsWithoutChangingState() = runBlocking {
        val paymentApi = FakeApi(sentCodes = ArrayDeque(listOf(SentCodePaymentRequired("p", "h", "e", "s", "USD", 1))))
        val payment = MtProtoPhoneAuthSession(paymentApi, 12345, "hash", settings())
        assertThrows(UnsupportedOperationException::class.java) { runBlocking { payment.requestCode("+1") } }
        assertEquals(AuthStep.InputPhone, payment.currentState())

        val emailApi = FakeApi(sentCodes = ArrayDeque(listOf(
            SentCode_f9e8fc1d16(SentCodeTypeEmailCode(false, false, "e***", 6, null, null), "hash", null, 0),
        )))
        val email = MtProtoPhoneAuthSession(emailApi, 12345, "hash", settings())
        assertThrows(UnsupportedOperationException::class.java) { runBlocking { email.requestCode("+1") } }
        assertEquals(AuthStep.InputPhone, email.currentState())
        Unit
    }

    @Test
    fun preservesCodeStateWhenSignInRequiresSignup() = runBlocking {
        val api = FakeApi(
            sentCodes = ArrayDeque(listOf(
                SentCode_f9e8fc1d16(SentCodeTypeSms(5), "hash-1", null, 0),
            )),
            authorization = AuthorizationSignUpRequired(null),
        )
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())
        val codeState = session.requestCode("+1")

        assertThrows(UnsupportedOperationException::class.java) { runBlocking { session.submitCode("12345") } }
        assertEquals(codeState, session.currentState())
        assertEquals("hash-1", api.lastHash)
    }

    @Test
    fun mapsPasswordNeededToInputPasswordAndSubmitsPassword() = runBlocking {
        val authorization = Authorization_d8660c55a3(false, null, null, null, fakeUser())
        val api = FakeApi(
            sentCodes = ArrayDeque(listOf(SentCode_f9e8fc1d16(SentCodeTypeSms(5), "hash-1", null, 0))),
            signInError = MtProtoRpcException(400, "SESSION_PASSWORD_NEEDED"),
            passwordInfo = MtProtoPasswordChallengeInfo("secret", true),
            passwordAuthorization = authorization,
        )
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())

        session.requestCode("+1")
        assertEquals(AuthStep.InputPassword("secret", true), session.submitCode("12345"))
        assertEquals(AuthStep.Ready, session.submitPassword("password"))
    }

    @Test
    fun preservesPasswordStateWhenPasswordIsRejected() = runBlocking {
        val api = FakeApi(
            sentCodes = ArrayDeque(listOf(SentCode_f9e8fc1d16(SentCodeTypeSms(5), "hash-1", null, 0))),
            signInError = MtProtoRpcException(400, "SESSION_PASSWORD_NEEDED"),
            passwordInfo = MtProtoPasswordChallengeInfo("secret", false),
            passwordError = MtProtoRpcException(400, "PASSWORD_HASH_INVALID"),
        )
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())

        session.requestCode("+1")
        val passwordState = session.submitCode("12345")
        assertThrows(MtProtoRpcException::class.java) { runBlocking { session.submitPassword("bad") } }
        assertEquals(passwordState, session.currentState())
    }

    @Test
    fun mapsTextCodesAndPreservesPriorStateAfterUnsupportedResponse() = runBlocking {
        val api = FakeApi(sentCodes = ArrayDeque(listOf(
            SentCode_f9e8fc1d16(SentCodeTypeSmsWord("word"), "hash-1", CodeTypeSms, 10),
            SentCodePaymentRequired("p", "h", "e", "s", "USD", 1),
            SentCode_f9e8fc1d16(SentCodeTypeSms(5), "hash-2", null, 0),
        )))
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())

        val text = session.requestCode("+1") as AuthStep.InputCode
        assertEquals(AuthCodeInputKind.TEXT, text.inputKind)
        assertEquals("word", text.codeHint)

        assertThrows(UnsupportedOperationException::class.java) { runBlocking { session.requestCode("+2") } }
        assertEquals(text, session.currentState())
        session.resendCode()
        assertEquals("hash-1", api.lastHash)
        assertEquals("+1", api.lastPhone)
    }

    @Test
    fun serializesOverlappingCodeRequests() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val requestedPhones = mutableListOf<String>()
        val api = object : MtProtoAuthorizationApi {
            override suspend fun sendCode(
                phoneNumber: String,
                settings: CodeSettings_fb610807ca,
                apiId: Int,
                apiHash: String,
            ): SentCode_250764ccd9 {
                requestedPhones += phoneNumber
                if (phoneNumber == "+1") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                val length = if (phoneNumber == "+1") 5 else 6
                return SentCode_f9e8fc1d16(SentCodeTypeSms(length), "hash-$phoneNumber", null, 0)
            }

            override suspend fun resendCode(
                phoneNumber: String,
                phoneCodeHash: String,
                reason: String?,
            ): SentCode_250764ccd9 = error("Unexpected resend")

            override suspend fun signIn(
                phoneNumber: String,
                phoneCodeHash: String,
                phoneCode: String,
            ): Authorization_fb75ff221f = error("Unexpected sign-in")

            override suspend fun getPasswordChallengeInfo(): MtProtoPasswordChallengeInfo =
                error("Unexpected password challenge")

            override suspend fun checkPassword(password: String): Authorization_fb75ff221f =
                error("Unexpected password check")
        }
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())

        val first = async { session.requestCode("+1") }
        firstStarted.await()
        val second = async { session.requestCode("+2") }
        yield()
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        val finalState = second.await() as AuthStep.InputCode
        assertEquals(listOf("+1", "+2"), requestedPhones)
        assertEquals(6, finalState.codeLength)
        assertEquals(finalState, session.currentState())
    }

    private fun settings(): CodeSettings_fb610807ca = CodeSettings_3f851bba91(false, false, true, false, false, false, null, null, null)

    private fun fakeUser(): org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57 =
        org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty(1L)

    private class FakeApi(
        private val sentCodes: ArrayDeque<SentCode_250764ccd9> = ArrayDeque(),
        private val authorization: Authorization_fb75ff221f? = null,
        private val signInError: Throwable? = null,
        private val passwordInfo: MtProtoPasswordChallengeInfo? = null,
        private val passwordAuthorization: Authorization_fb75ff221f? = null,
        private val passwordError: Throwable? = null,
    ) : MtProtoAuthorizationApi {
        var lastHash: String? = null
        var lastPhone: String? = null

        override suspend fun sendCode(phoneNumber: String, settings: CodeSettings_fb610807ca, apiId: Int, apiHash: String): SentCode_250764ccd9 {
            lastPhone = phoneNumber
            return sentCodes.removeFirst()
        }
        override suspend fun resendCode(phoneNumber: String, phoneCodeHash: String, reason: String?): SentCode_250764ccd9 {
            lastPhone = phoneNumber
            lastHash = phoneCodeHash
            return sentCodes.removeFirst()
        }
        override suspend fun signIn(phoneNumber: String, phoneCodeHash: String, phoneCode: String): Authorization_fb75ff221f {
            lastPhone = phoneNumber
            lastHash = phoneCodeHash
            signInError?.let { throw it }
            return authorization ?: error("No authorization result")
        }

        override suspend fun getPasswordChallengeInfo(): MtProtoPasswordChallengeInfo =
            passwordInfo ?: error("No password challenge")

        override suspend fun checkPassword(password: String): Authorization_fb75ff221f {
            passwordError?.let { throw it }
            return passwordAuthorization ?: error("No password authorization")
        }
    }
}
