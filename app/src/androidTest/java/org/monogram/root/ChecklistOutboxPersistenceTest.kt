package org.monogram.root

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.common.Outcome
import org.monogram.core.database.*
import org.monogram.core.database.entity.*
import org.monogram.core.models.*
import org.monogram.network.bridge.message.MessageOps

@RunWith(AndroidJUnit4::class)
class ChecklistOutboxPersistenceTest {
    @Test
    fun checklistPermissionsAndItemsSurviveReopenUncertaintyAndExplicitRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "checklist-outbox-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
        var db = open()
        val spec = ChecklistSendSpec(
            "Synthetic list",
            listOf("First", "Second"),
            othersCanAppend = true,
            othersCanComplete = false
        )
        val pending = spec.pendingChecklist()
        val row = OutgoingTextSendEntity(
            123, MessageEntity(
                chatId = 7,
                id = -37,
                senderId = null,
                text = spec.title,
                date = 100,
                outgoing = true,
                pending = true,
                randomId = 123,
                mediaCacheKey = null,
                mediaKind = "todo",
                checklistJson = Checklists.serialize(pending),
                replyToMsgId = 42,
                replyToTopId = 99,
                forumTopic = true
            ), silent = true, noWebpage = false, scheduleDate = 0x7ffffffe, webpageUrl = null
        )
        try {
            val cache = OfflineWarmup(db)
            cache.saveLocalCloudDraft(CloudDraft(PeerId(7), 99, text = "Keep draft"))
            cache.enqueueOutgoingTextSend(row, consumeDraft = false)
            assertEquals(
                "Keep draft",
                cache.captureOutgoingTextDraft(
                    PeerId(7),
                    99,
                    cache.outgoingAccountEpoch()
                ).draft.text
            )
            assertEquals(
                spec,
                ChecklistSendSpec.fromPending(
                    db.outgoingTextSendDao().get(123)!!.message.toModel().checklist!!
                )
            )
            try {
                ChecklistSendSpec.fromPending(
                    pending.copy(
                        items = listOf(
                            pending.items.first().copy(done = true)
                        )
                    )
                ); fail("completed pending item accepted")
            } catch (_: IllegalArgumentException) {
            }
            try {
                ChecklistSendSpec.fromPending(
                    pending.copy(
                        items = listOf(
                            pending.items.first().copy(id = 9)
                        )
                    )
                ); fail("changed pending id accepted")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(1, db.outgoingTextSendDao().claim(123))
            db.close(); db = open()
            val restored = OfflineWarmup(db)
            restored.recoverInterruptedTextSends()
            assertEquals(
                row.copy(phase = OutgoingSendPhase.UNCERTAIN),
                db.outgoingTextSendDao().get(123)
            )
            assertEquals(0, db.outgoingTextSendDao().claim(123))
            var attempts = 0
            val client = Proxy.newProxyInstance(
                MessageOps::class.java.classLoader,
                arrayOf(MessageOps::class.java)
            ) { _, method, args ->
                check(method.name == "sendChecklistWithOptions") { "unexpected ${method.name}" }
                attempts++
                assertEquals(PeerId(7), args!![0]); assertEquals(spec, args[1]); assertEquals(
                123L,
                args[2]
            )
                assertEquals(MessageSendOptions(silent = true, scheduleDate = 0x7ffffffe), args[3])
                assertEquals(42, args[4]); assertEquals(99, args[5])
                if (attempts == 1) Outcome.Err("RPC 500: INTERNAL")
                else {
                    val message = Message(
                        MessageId(PeerId(7), 37),
                        null,
                        null,
                        200,
                        outgoing = true,
                        mediaKind = "todo",
                        checklist = pending
                    )
                    val result = OutgoingMessageResult.Scheduled(ScheduledMessage(message))
                    runBlocking { restored.applyOutgoingResult(123, result) }
                    Outcome.Ok(result)
                }
            } as MessageOps
            val worker = OutgoingTextSync(restored, { sendRetainedMessage(client, it) })
            worker.syncOnce(); assertEquals(0, attempts)
            restored.retryOutgoingTextSend(123)
            worker.syncOnce(); assertEquals(1, attempts)
            worker.syncOnce(); assertEquals(1, attempts)
            assertEquals(OutgoingSendPhase.UNCERTAIN, db.outgoingTextSendDao().get(123)?.phase)
            restored.retryOutgoingTextSend(123); worker.requestResume()
            worker.syncOnce(); assertEquals(2, attempts)
            assertNull(db.outgoingTextSendDao().get(123))
            val queued = db.scheduledMessageDao().forChat(7).single().message.toModel()
            assertEquals(pending, queued.checklist)
            assertEquals(123L, queued.randomId)
        } finally {
            db.close(); context.deleteDatabase(name)
        }
    }
}
