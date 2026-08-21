package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetContactSignUpNotification
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetContactSignUpNotification
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoClientOptionsRepositoryTest {
    @Test
    fun `maps enabled contact notifications to inverse silent request`() = runBlocking {
        val transport = Transport(true, true, true)
        val repository = MtProtoClientOptionsRepositoryImpl(MtProtoSessionTransportFactory { transport })

        assertTrue(repository.getContactJoinedNotificationsEnabled())
        repository.setContactJoinedNotificationsEnabled(true)
        repository.setContactJoinedNotificationsEnabled(false)

        assertEquals(
            listOf(
                GetContactSignUpNotification,
                SetContactSignUpNotification(silent = false),
                SetContactSignUpNotification(silent = true),
            ),
            transport.requests,
        )
        assertTrue(transport.closed)
    }

    private class Transport(private vararg val results: Boolean) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        private var index = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return results[index++] as R
        }

        override fun close() {
            closed = true
        }
    }
}
