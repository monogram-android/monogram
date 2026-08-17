package org.monogram.mtproto.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ResendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignIn
import org.monogram.mtproto.tl.runtime.TlMethod
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
    }

    private class Capture : RuntimeException()

    private class CapturingTransport : MtProtoRpcTransport {
        var method: TlMethod<*>? = null

        override suspend fun <R> execute(method: TlMethod<R>): R {
            this.method = method
            throw Capture()
        }

        override fun close() = Unit
    }
}
