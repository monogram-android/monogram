package org.monogram.push

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccountPushRouterTest {
    @Test
    fun encryptedPushRoutesToDecryptingAccountAndForegroundTransitionsStayScoped() {
        val router = AccountPushRouter()
        val first = FakeHandler()
        val second = FakeHandler()
        router.register(11L, first)
        router.register(22L, second)
        router.setForeground(true, 11L)

        assertTrue(first.foregroundState)
        assertFalse(second.foregroundState)
        first.decryptResult = Outcome.Err("wrong account")
        second.decryptResult = Outcome.Ok(payload(22L))
        awaitCompletion { router.handleFcm("cipher", it) }
        assertEquals(listOf(payload(22L)), second.delivered)
        assertTrue(first.delivered.isEmpty())

        router.setForeground(false, 11L)
        assertFalse(first.foregroundState)
        assertFalse(second.foregroundState)
    }

    @Test
    fun wakeFansOutAndCompletesAfterEveryAccount() {
        val router = AccountPushRouter()
        val first = FakeHandler()
        val second = FakeHandler()
        router.register(11L, first)
        router.register(22L, second)

        awaitCompletion { router.handleFcm(null, it) }

        assertEquals(1, first.wakes)
        assertEquals(1, second.wakes)
    }

    private fun payload(userId: Long) =
        """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hello"],"user_id":"$userId","custom":{"msg_id":"3","channel_id":"9"}}"""

    private fun awaitCompletion(start: ((() -> Unit) -> Unit)) {
        val latch = CountDownLatch(1)
        start(latch::countDown)
        assertTrue("FCM routing did not complete", latch.await(3, TimeUnit.SECONDS))
    }

    private class FakeHandler : AccountPushHandler {
        @JvmField
        var foregroundState = false
        var wakes = 0
        val delivered = mutableListOf<String>()
        var decryptResult: Outcome<String> = Outcome.Err("not encrypted")
        override fun setForeground(value: Boolean) {
            foregroundState = value
        }

        override suspend fun unregisterPush() = Unit
        override fun stop() = Unit
        override fun onFcmToken(token: String, onComplete: (() -> Unit)?) {
            onComplete?.invoke()
        }

        override fun handleFcm(payload: String?, onComplete: (() -> Unit)?) {
            if (payload == null) wakes++
            onComplete?.invoke()
        }

        override fun decryptFcmPayload(cipher: String): Outcome<String> = decryptResult

        override fun handleDecryptedFcmPayload(json: String, onComplete: (() -> Unit)?) {
            delivered += json
            onComplete?.invoke()
        }

        override fun handleNotificationAction(intent: Intent, done: () -> Unit) = done()
    }
}
