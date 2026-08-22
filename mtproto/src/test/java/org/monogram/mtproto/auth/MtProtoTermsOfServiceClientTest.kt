package org.monogram.mtproto.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.DataJson_340cf194d4
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AcceptTermsOfService
import org.monogram.mtproto.tl.generated.cloud.layer223.help.AppConfigNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.help.GetTermsOfServiceUpdate
import org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfServiceUpdateEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfServiceUpdate_db081ee702
import org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfService_ca69dd05f0
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod

class MtProtoTermsOfServiceClientTest {
    private fun terms(popup: Boolean = false): org.monogram.mtproto.tl.generated.cloud.layer223.help.TermsOfService_ca69dd05f0 {
        val idData: TlBytes? = null
        return TermsOfService_ca69dd05f0(
            popup = popup,
            id = DataJson_340cf194d4("""{"id":"tos-1"}"""),
            text = "Be kind.",
            entities = emptyList(),
            minAgeConfirm = 16,
        )
    }

    @Test
    fun `reports pending terms with identifier text and age gate`() = runBlocking {
        val requests = mutableListOf<TlMethod<*>>()
        val client = MtProtoAuthorizationClient(
            transport = RecordingTransport { method ->
                requests += method
                TermsOfServiceUpdate_db081ee702(expires = 900, termsOfService = terms(popup = true))
            },
        )

        val state = client.termsOfServiceUpdate()

        state as MtProtoTermsOfServiceState.Pending
        assertEquals(900, state.expiresAtSeconds)
        assertTrue(state.popup)
        assertEquals("""{"id":"tos-1"}""", state.idData)
        assertEquals("Be kind.", state.text)
        assertEquals(16, state.minAgeConfirmYears)
        assertEquals(GetTermsOfServiceUpdate, requests.single())
    }

    @Test
    fun `accepts pending terms by identifier and reports current otherwise`() = runBlocking {
        var accepted: AcceptTermsOfService? = null
        var call = 0
        val client = MtProtoAuthorizationClient(
            transport = RecordingTransport { method ->
                when (method) {
                    is GetTermsOfServiceUpdate ->
                        if (call++ == 0) {
                            TermsOfServiceUpdate_db081ee702(0, terms())
                        } else {
                            TermsOfServiceUpdateEmpty(expires = 60)
                        }
                    is AcceptTermsOfService -> {
                        accepted = method
                        true
                    }
                    else -> error("Unexpected ${method::class.simpleName}")
                }
            },
        )

        val pending = client.termsOfServiceUpdate()
        pending as MtProtoTermsOfServiceState.Pending
        assertTrue(client.acceptTermsOfService(pending.idData))

        assertEquals(DataJson_340cf194d4("""{"id":"tos-1"}"""), accepted?.id)

        val after = client.termsOfServiceUpdate()
        assertEquals(MtProtoTermsOfServiceState.Current, after)
    }

    @Test
    fun `rejects blank identifiers`() {
        val client = MtProtoAuthorizationClient(
            transport = RecordingTransport { error("no request expected") },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.acceptTermsOfService("  ") }
        }
    }


    @Test
    fun `imports bot authorization with api credentials`() = runBlocking {
        val requests = mutableListOf<TlMethod<*>>()
        val client = MtProtoAuthorizationClient(
            transport = RecordingTransport { method ->
                requests += method
                org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3(
                    false, null, null, null,
                    org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty(1L),
                )
            },
        )

        val authorization = client.importBotAuthorization(apiId = 42, apiHash = "secret", botAuthToken = " 123:ABC ")

        assertTrue(authorization is org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_d8660c55a3)
        val request = requests.single() as org.monogram.mtproto.tl.generated.cloud.layer223.auth.ImportBotAuthorization
        assertEquals(0, request.flags)
        assertEquals(42, request.apiId)
        assertEquals("secret", request.apiHash)
        assertEquals("123:ABC", request.botAuthToken)
    }

    private open class RecordingTransport(
        private val respond: suspend (TlMethod<*>) -> Any,
    ) : org.monogram.mtproto.transport.MtProtoRpcTransport {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R = respond(method) as R
        override fun close() = Unit
    }
}
