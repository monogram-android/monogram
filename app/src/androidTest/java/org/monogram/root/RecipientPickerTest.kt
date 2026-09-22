package org.monogram.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.UploadItem
import org.monogram.network.bridge.MtprotoClient

class RecipientPickerTest {
    @Test
    fun forwardsSelectionWithCommentAndAuthorOptionToBothRecipients() = runBlocking {
        val calls = mutableListOf<String>()
        val lifecycle = LifecycleRegistry()
        val picker = withContext(Dispatchers.Main) {
            lifecycle.resume()
            RecipientPickerComponent(DefaultComponentContext(lifecycle),
                RecipientRequest(fromChatId = 9, messageIds = listOf(5, 3, 5)),
                fake(calls), null, null, {})
        }
        try {
            withContext(Dispatchers.Main) {
                picker.onToggle(1); picker.onToggle(2)
                picker.onComment("Comment"); picker.onDropAuthor(true); picker.onSend()
            }
            withTimeout(5_000) { picker.state.first { it.done } }
            assertEquals(listOf("forward:1:[3, 5]:true", "text:1:Comment", "forward:2:[3, 5]:true", "text:2:Comment"), calls)
        } finally { withContext(Dispatchers.Main) { lifecycle.destroy() } }
    }

    @Test
    fun retrySkipsRecipientsAndCommentsThatAlreadySucceeded() = runBlocking {
        val calls = mutableListOf<String>()
        var failOnce = true
        val lifecycle = LifecycleRegistry()
        val picker = withContext(Dispatchers.Main) {
            lifecycle.resume()
            RecipientPickerComponent(DefaultComponentContext(lifecycle),
                RecipientRequest(fromChatId = 9, messageIds = listOf(3)),
                fake(calls) { peer -> if (peer == 2L && failOnce) { failOnce = false; true } else false },
                null, null, {})
        }
        try {
            withContext(Dispatchers.Main) {
                picker.onToggle(1); picker.onToggle(2); picker.onComment("Comment"); picker.onSend()
            }
            withTimeout(5_000) { picker.state.first { it.error && !it.sending } }
            assertEquals(setOf(1L), picker.state.value.completed)
            withContext(Dispatchers.Main) { picker.onSend() }
            withTimeout(5_000) { picker.state.first { it.done } }
            assertEquals(1, calls.count { it == "text:2:Comment" })
            assertEquals(1, calls.count { it == "forward:1:[3]:false" })
            assertEquals(2, calls.count { it == "forward:2:[3]:false" })
        } finally { withContext(Dispatchers.Main) { lifecycle.destroy() } }
    }

    @Test
    fun sharedPhotoUsesSameRecipientFlowAndCaption() = runBlocking {
        val calls = mutableListOf<String>()
        val lifecycle = LifecycleRegistry()
        val picker = withContext(Dispatchers.Main) {
            lifecycle.resume()
            RecipientPickerComponent(DefaultComponentContext(lifecycle),
                RecipientRequest(share = IncomingShare("Caption", listOf(IncomingShareAttachment("/test/photo.jpg", "photo", "image/jpeg", "photo.jpg")))),
                fake(calls), null, null, {})
        }
        try {
            withContext(Dispatchers.Main) { picker.onToggle(1); picker.onToggle(2); picker.onSend() }
            withTimeout(5_000) { picker.state.first { it.done } }
            assertEquals(listOf("upload:1:Caption", "upload:2:Caption"), calls)
            assertTrue(picker.state.value.completed.containsAll(listOf(1L, 2L)))
        } finally { withContext(Dispatchers.Main) { lifecycle.destroy() } }
    }

    private fun fake(calls: MutableList<String>, failForward: (Long) -> Boolean = { false }): MtprotoClient =
        Proxy.newProxyInstance(MtprotoClient::class.java.classLoader, arrayOf(MtprotoClient::class.java)) { _, method, args ->
            fun message(peer: PeerId) = Message(MessageId(peer, 100), null, null, 1, outgoing = true)
            when (method.name) {
                "getChats" -> Outcome.Ok(listOf(Chat(PeerId(1), "Alice"), Chat(PeerId(2), "Bob")))
                "sendText" -> {
                    val peer = args[0] as PeerId
                    calls += "text:${peer.value}:${args[1]}"
                    Outcome.Ok(message(peer))
                }
                "forwardMessages" -> {
                    val peer = args[2] as PeerId
                    calls += "forward:${peer.value}:${args[1]}:${args[3]}"
                    if (failForward(peer.value)) Outcome.Err("test failure") else Outcome.Ok(listOf(message(peer)))
                }
                "sendUploadedMedia" -> {
                    val peer = args[0] as PeerId
                    calls += "upload:${peer.value}:${(args[1] as UploadItem).caption}"
                    Outcome.Ok(message(peer))
                }
                else -> error("Unexpected call: ${method.name}")
            }
        } as MtprotoClient
}
