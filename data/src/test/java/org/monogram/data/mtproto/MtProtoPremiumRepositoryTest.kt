package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ToggleSponsoredMessages
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoPremiumRepositoryTest {
    @Test
    fun `updates sponsored preference after accepted server response`() = runBlocking {
        val transport = Transport(true)
        val repository = MtProtoPremiumRepositoryImpl(MtProtoSessionTransportFactory { transport })

        repository.setSponsoredMessagesEnabled(false)

        assertEquals(listOf(ToggleSponsoredMessages(false)), transport.requests)
        assertTrue(transport.closed)
    }

    @Test
    fun `rejects sponsored preference when server does not acknowledge it`() = runBlocking {
        val transport = Transport(false)
        val repository = MtProtoPremiumRepositoryImpl(MtProtoSessionTransportFactory { transport })

        val failure = runCatching { repository.setSponsoredMessagesEnabled(true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(transport.closed)
    }

    private class Transport(private val response: Boolean) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return response as R
        }

        override fun close() { closed = true }
    }
}
