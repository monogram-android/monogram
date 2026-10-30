package org.monogram.root

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.monogram.MonogramApp
import org.monogram.core.common.Outcome
import org.monogram.core.models.*

class ComposerLiveInteropTest {
    private fun <T> accepted(result: Outcome<T>, operation: String): T = when (result) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> throw AssertionError("Live fixture failed: $operation")
    }

    @Test
    fun silentTextRoundTripToVerifiedLocalTestingBot(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("composerLive") == "true")
        val peerId = requireNotNull(args.getString("composerBotId")).toLong()
        val username = requireNotNull(args.getString("composerBotUsername"))
        val expectedSelf = requireNotNull(args.getString("composerSelfSha256"))
        val nonce = requireNotNull(args.getString("composerFixtureNonce"))
        val randomId = requireNotNull(args.getString("composerRandomId")).toLong()
        require(peerId > 0 && randomId != 0L && nonce.matches(Regex("[a-f0-9-]{36}")))
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonogramApp
        withTimeout(60_000) {
            app.awaitReady()
            val client = app.client
            assertTrue(
                "Expected an already authorized installation",
                accepted(client.isAuthorized(), "authorization")
            )
            val self = accepted(client.getProfile(PeerId(0)), "self identity")
            val actualSelf =
                MessageDigest.getInstance("SHA-256").digest(self.id.value.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
            assertTrue(
                "Configured bot receiver does not match this account",
                actualSelf == expectedSelf
            )
            val peer = accepted(client.resolveUsername(username), "testing bot resolution")
            assertTrue(
                "Testing bot identity mismatch", peer.isBot && peer.peerId.value == peerId &&
                        peer.username?.equals(username, ignoreCase = true) == true
            )
            val body = "Monogram parity silent text fixture $nonce"
            val history =
                accepted(client.getHistory(peer.peerId, limit = 100), "testing chat history")
            val existing = history.firstOrNull { it.outgoing && it.text == body }
            val attempt = File(app.filesDir, "composer-live-attempt-$nonce")
            val message = if (existing != null) existing else {
                assertFalse(
                    "Prior attempt is uncertain; inspect before any resend",
                    attempt.exists()
                )
                attempt.writeText(randomId.toString())
                val result = accepted(
                    client.sendTextWithOptions(
                        peer.peerId, body,
                        MessageSendOptions(silent = true), randomId
                    ), "silent send"
                )
                assertTrue(
                    "Immediate fixture returned scheduled mode",
                    result is OutgoingMessageResult.Sent
                )
                (result as OutgoingMessageResult.Sent).message
            }
            assertTrue(
                "Unexpected returned message identity",
                message.id.chatId == peer.peerId && message.id.id > 0 && message.outgoing
            )
            val confirmed =
                accepted(client.getHistory(peer.peerId, limit = 100), "sent history confirmation")
                    .firstOrNull { it.id == message.id }
            assertTrue("Sent fixture was not confirmed losslessly", confirmed?.text == body)
            File(
                app.filesDir,
                "composer-live-proof-$nonce"
            ).writeText("silent=true mode=sent historyConfirmed=true id=${message.id.id}")
        }
    }
}
