package org.monogram.feature.dialog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.WorkOwnership
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.StickerPack
import java.io.File

class CacheBoundTest {
    @After
    fun reset() {
        SenderTagMemory.clear()
        TopicListMemory.clear()
        PinnedBarMemory.clear()
        StickerPackMemory.clear()
    }

    @Test
    fun panelFrameTrimDropsTheOldestFilePastTheCap() {
        val dir = File.createTempFile("panel-frames", "").apply {
            delete()
            mkdirs()
        }
        val older = File(dir, "a.frames").apply { writeBytes(ByteArray(8)) }
        val newer = File(dir, "b.frames").apply { writeBytes(ByteArray(8)) }
        older.setLastModified(1_000L)
        newer.setLastModified(2_000L)
        trimFrameFiles(listOf(older, newer), 8L)
        assertFalse(older.exists())
        assertTrue(newer.exists())
        dir.deleteRecursively()
    }

    @Test
    fun cancelledWarmupFinishesOwnership() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val warmup = SelectedTargetWarmup(scope) { _, _, _, _ -> delay(60_000) }
        warmup.onFirstFrame()
        warmup.select(41L)
        warmup.clear()
        assertNull(WorkOwnership.state("history-target:41"))
        scope.cancel()
    }

    @Test
    fun releaseAttachCopyDeletesOnlyAttachFiles() {
        val dir = File.createTempFile("attach-dir", "").apply {
            delete()
            mkdirs()
        }
        val attach = File(dir, "attach-1.bin").apply { writeBytes(byteArrayOf(1)) }
        val other = File(dir, "photo.bin").apply { writeBytes(byteArrayOf(2)) }
        releaseAttachCopy(attach.path)
        releaseAttachCopy(other.path)
        assertFalse(attach.exists())
        assertTrue(other.exists())
        dir.deleteRecursively()
    }

    @Test
    fun stickerPacksDropTheOldestAndForgetNativeReady() {
        StickerPackMemory.clear()
        var dropped: Long? = null
        repeat(STICKER_PACK_CAP + 1) { id ->
            dropped = StickerPackMemory.put(StickerPack(id.toLong(), "t$id", "s$id", 1, false))
        }
        assertEquals(0L, dropped)
        assertNull(StickerPackMemory.get(0L))
        assertEquals(
            "t$STICKER_PACK_CAP",
            StickerPackMemory.get(STICKER_PACK_CAP.toLong())?.title,
        )
    }

    @Test
    fun topicListDropsTheOldestChat() {
        TopicListMemory.clear()
        val topic = ForumTopic(id = 1, title = "general")
        var evicted: Long? = null
        repeat(33) { chat ->
            evicted = TopicListMemory.put(chat.toLong(), listOf(topic))
        }
        assertEquals(0L, evicted)
        assertTrue(TopicListMemory.get(0L).isEmpty())
        assertEquals("general", TopicListMemory.get(32L).single().title)
    }

    @Test
    fun evictedDialogChatsDeletePinnedTagAndTopicMeta() = runBlocking {
        PinnedBarMemory.clear()
        SenderTagMemory.clear()
        TopicListMemory.clear()
        val message = Message(
            id = MessageId(PeerId(1), 1),
            senderId = null,
            text = "pin",
            date = 1L,
            outgoing = false,
        )
        val topic = ForumTopic(id = 1, title = "general")
        val tags = mapOf(PeerId(1) to "admin")
        repeat(32) { chat ->
            PinnedBarMemory.put(chat.toLong(), listOf(message), 0)
            SenderTagMemory.put(chat.toLong(), tags)
            TopicListMemory.put(chat.toLong(), listOf(topic))
        }
        val store = RecordingMeta()
        forgetEvictedDialogMeta(
            store,
            PinnedBarMemory.put(100L, listOf(message), 0),
            ::pinnedMetaKey
        )
        forgetEvictedDialogMeta(store, SenderTagMemory.put(100L, tags), ::tagsMetaKey)
        forgetEvictedDialogMeta(store, TopicListMemory.put(100L, listOf(topic)), ::topicsMetaKey)
        assertEquals(listOf("pinned.0", "tags.0", "topics.0"), store.deleted)
    }

    @Test
    fun pinnedMemoryKeepsOnlyTheNewestChats() {
        PinnedBarMemory.clear()
        val message = Message(
            id = MessageId(PeerId(1), 1),
            senderId = null,
            text = "pin",
            date = 1L,
            outgoing = false,
        )
        repeat(33) { PinnedBarMemory.put(it.toLong(), listOf(message), 0) }
        assertNull(PinnedBarMemory.get(0L))
        assertTrue(PinnedBarMemory.get(32L) != null)
    }
}

private class RecordingMeta : SessionMetadataStore() {
    val deleted = mutableListOf<String>()

    override suspend fun deleteMeta(key: String) {
        deleted += key
    }
}
