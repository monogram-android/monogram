package org.monogram.data.mtproto

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_3f851bba91
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoPhoneAuthSessionFactoryTest {
    @Test
    fun `opens handle with default slot and closes transport once`() = runBlocking {
        val transport = FakeTransport()
        var slot: String? = null
        val factory = MtProtoPhoneAuthSessionFactory(
            openTransport = {
                slot = it
                transport
            },
            apiId = 123,
            apiHash = "hash",
            codeSettings = settings(),
        )

        val handle = factory.open()
        try {
            assertEquals("default", slot)
            assertEquals(org.monogram.domain.repository.AuthStep.InputPhone, handle.currentState())
        } finally {
            handle.close()
            handle.close()
        }
        assertEquals(1, transport.closeCalls.get())
        assertThrows(IllegalStateException::class.java) { handle.currentState() }
        Unit
    }

    @Test
    fun `rejects invalid api configuration before opening transport`() {
        var opened = false

        assertThrows(IllegalArgumentException::class.java) {
            MtProtoPhoneAuthSessionFactory(
                openTransport = {
                    opened = true
                    FakeTransport()
                },
                apiId = 0,
                apiHash = "hash",
                codeSettings = settings(),
            )
        }
        assertEquals(false, opened)
    }

    private fun settings() = CodeSettings_3f851bba91(
        allowFlashcall = false,
        currentNumber = false,
        allowAppHash = true,
        allowMissedCall = false,
        allowFirebase = false,
        unknownNumber = false,
        logoutTokens = null,
        token = null,
        appSandbox = null,
    )

    private class FakeTransport : MtProtoRpcTransport {
        val closeCalls = AtomicInteger()

        override suspend fun <R> execute(method: TlMethod<R>): R = error("not used")

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
