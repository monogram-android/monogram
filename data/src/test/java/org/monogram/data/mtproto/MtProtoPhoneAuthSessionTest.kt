package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.repository.AuthCodeDelivery
import org.monogram.domain.repository.AuthStep
import org.monogram.mtproto.auth.MtProtoAuthorizationApi
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodePaymentRequired
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCodeTypeSms
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_f9e8fc1d16
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9

class MtProtoPhoneAuthSessionTest {
    @Test
    fun retainsPhoneHashMapsCodeAndReusesHashForSignIn() = runBlocking {
        val api = FakeApi(
            sentCodes = ArrayDeque(listOf(
                SentCode_f9e8fc1d16(SentCodeTypeSms(5), "hash-1", null, 30),
                SentCode_f9e8fc1d16(SentCodeTypeSms(6), "hash-2", null, 0),
            )),
            authorization = Authorization_d8660c55a3(false, null, null, null, fakeUser()),
        )
        val session = MtProtoPhoneAuthSession(api, 12345, "hash", settings())

        val first = session.requestCode("+10000000000") as AuthStep.InputCode
        assertEquals(AuthCodeDelivery.SMS, first.delivery)
        assertEquals(5, first.codeLength)

        val resent = session.resendCode() as AuthStep.InputCode
        assertEquals(6, resent.codeLength)
        assertEquals("hash-1", api.lastHash)

        assertEquals(AuthStep.Ready, session.submitCode("123456"))
        assertEquals("hash-2", api.lastHash)
    }

    @Test
    fun rejectsResendAndSubmitBeforeCodeRequest() {
        val session = MtProtoPhoneAuthSession(FakeApi(), 12345, "hash", settings())
        assertThrows(IllegalStateException::class.java) { runBlocking { session.resendCode() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { session.submitCode("1234") } }
    }

    @Test
    fun rejectsUnsupportedPaymentAndSignupResults() = runBlocking {
        val paymentApi = FakeApi(sentCodes = ArrayDeque(listOf(SentCodePaymentRequired("p", "h", "e", "s", "USD", 1))))
        val payment = MtProtoPhoneAuthSession(paymentApi, 12345, "hash", settings())
        assertThrows(UnsupportedOperationException::class.java) { runBlocking { payment.requestCode("+1") } }
        Unit
    }

    private fun settings(): CodeSettings_fb610807ca = CodeSettings_3f851bba91(false, false, true, false, false, false, null, null, null)

    private fun fakeUser(): org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57 =
        org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty(1L)

    private class FakeApi(
        private val sentCodes: ArrayDeque<SentCode_250764ccd9> = ArrayDeque(),
        private val authorization: Authorization_fb75ff221f? = null,
    ) : MtProtoAuthorizationApi {
        var lastHash: String? = null

        override suspend fun sendCode(phoneNumber: String, settings: CodeSettings_fb610807ca, apiId: Int, apiHash: String): SentCode_250764ccd9 = sentCodes.removeFirst()
        override suspend fun resendCode(phoneNumber: String, phoneCodeHash: String, reason: String?): SentCode_250764ccd9 {
            lastHash = phoneCodeHash
            return sentCodes.removeFirst()
        }
        override suspend fun signIn(phoneNumber: String, phoneCodeHash: String, phoneCode: String): Authorization_fb75ff221f {
            lastHash = phoneCodeHash
            return authorization ?: error("No authorization result")
        }
    }
}
