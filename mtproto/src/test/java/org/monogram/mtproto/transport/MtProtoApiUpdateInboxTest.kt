package org.monogram.mtproto.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong

class MtProtoApiUpdateInboxTest {
    @Test
    fun retainsEveryAcceptedUpdateAndClosesReceiveSide() = runBlocking {
        val inbox = MtProtoApiUpdateInbox()
        repeat(UPDATE_COUNT) { inbox.admit(UpdatesTooLong) }

        assertEquals(MtProtoApiUpdateInboxMetrics(UPDATE_COUNT.toLong(), 0, UPDATE_COUNT.toLong()), inbox.metrics())
        repeat(UPDATE_COUNT) { assertEquals(UpdatesTooLong, inbox.receive()) }
        assertEquals(MtProtoApiUpdateInboxMetrics(UPDATE_COUNT.toLong(), UPDATE_COUNT.toLong(), 0), inbox.metrics())

        inbox.close()
        assertNull(inbox.receive())
    }

    private companion object {
        const val UPDATE_COUNT = 2_049
    }
}
