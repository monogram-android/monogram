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
class ContactOutboxPersistenceTest {
    @Test
    fun syntheticContactCardSurvivesReopenAndExplicitRetryWithoutImportOrDraftLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (location in listOf(false, true)) {
            val name = "contact-outbox-${UUID.randomUUID()}.db"
            fun open() = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
            var db = open()
            val spec = ContactSendSpec(
                "+15555550123", "Synthetic", "Contact",
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Synthetic Contact\r\nNOTE:Fixture ü\r\nEND:VCARD"
            )
            val place = GeoPlace(1.0, 2.0, live = true, periodSeconds = 900, heading = 360)
            val payload = if (location) encodeLocationSend(place) else encodeContactSend(spec)
            val kind = if (location) "geo" else "contact"
            val row = OutgoingTextSendEntity(
                123, MessageEntity(
                    chatId = 7,
                    id = -37,
                    senderId = null,
                    text = if (location) null else spec.displayName,
                    date = 100,
                    outgoing = true,
                    pending = true,
                    randomId = 123,
                    mediaCacheKey = null,
                    mediaKind = kind,
                    fileName = payload,
                    replyToMsgId = 42,
                    replyToTopId = 99,
                    forumTopic = true
                ), silent = true, noWebpage = false, scheduleDate = null, webpageUrl = null
            )
            try {
                val cache = OfflineWarmup(db)
                cache.saveLocalCloudDraft(CloudDraft(PeerId(7), 99, text = "Keep draft"))
                if (location) {
                    assertTrue(runCatching {
                        cache.enqueueOutgoingTextSend(
                            row.copy(scheduleDate = 0x7fff_fffe),
                            consumeDraft = false
                        )
                    }.isFailure)
                    assertTrue(runCatching {
                        cache.enqueueOutgoingTextSend(
                            row.copy(
                                message = row.message.copy(
                                    fileName = "{\"lat\":1.0,\"long\":2.0}"
                                )
                            ), consumeDraft = false
                        )
                    }.isFailure)
                }
                cache.enqueueOutgoingTextSend(row, consumeDraft = false)
                assertEquals(
                    "Keep draft",
                    cache.captureOutgoingTextDraft(
                        PeerId(7),
                        99,
                        cache.outgoingAccountEpoch()
                    ).draft.text
                )
                if (location) {
                    assertEquals(
                        place,
                        decodeLocationSend(db.outgoingTextSendDao().get(123)!!.message.fileName)
                    )
                    assertEquals(place, ServiceMedia.geo(payload))
                } else {
                    assertEquals(
                        spec,
                        decodeContactSend(db.outgoingTextSendDao().get(123)!!.message.fileName)
                    )
                    val preview = ServiceMedia.contact(payload)!!
                    assertEquals(0L, preview.userId); assertEquals(
                        spec.vcard,
                        preview.vcard
                    ); assertEquals(spec.phone, preview.phone)
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
                    check(method.name == if (location) "sendLocationWithOptions" else "sendContactWithOptions") { "unexpected ${method.name}; send must not import" }
                    attempts++
                    assertEquals(PeerId(7), args!![0]); assertEquals(
                    if (location) place else spec,
                    args[1]
                ); assertEquals(123L, args[2])
                    assertEquals(MessageSendOptions(silent = true), args[3]); assertEquals(
                    42,
                    args[4]
                ); assertEquals(99, args[5])
                    if (attempts == 1) Outcome.Err("RPC 500: INTERNAL")
                    else {
                        val response = org.json.JSONObject(payload)
                            .apply { remove("send_version"); if (!location) put("u", 42) }
                            .toString()
                        val message = Message(
                            MessageId(PeerId(7), 37),
                            null,
                            null,
                            if (location) System.currentTimeMillis() / 1000 else 200,
                            outgoing = true,
                            mediaKind = kind,
                            fileName = response
                        )
                        val result = OutgoingMessageResult.Sent(message)
                        runBlocking {
                            if (location) {
                                assertTrue(runCatching {
                                    restored.applyOutgoingResult(
                                        123,
                                        OutgoingMessageResult.Sent(
                                            message.copy(
                                                id = MessageId(
                                                    PeerId(8),
                                                    37
                                                )
                                            )
                                        )
                                    )
                                }.isFailure)
                                assertNull(
                                    restored.liveLocation(
                                        MessageId(PeerId(8), 37),
                                        restored.outgoingAccountEpoch()
                                    )
                                )
                                assertNotNull(db.outgoingTextSendDao().get(123))
                            }
                            restored.applyOutgoingResult(123, result)
                        }
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
                val delivered = db.messageDao().byIds(7, listOf(37)).single().toModel()
                if (location) assertEquals(place, delivered.geoPlace)
                else {
                    assertEquals(42L, delivered.contactCard?.userId)
                    assertEquals(spec.vcard, delivered.contactCard?.vcard)
                }
                assertEquals(123L, delivered.randomId)
                if (location) {
                    val id = delivered.id
                    val epoch = restored.outgoingAccountEpoch()
                    val active = restored.liveLocation(id, epoch)!!
                    assertEquals(123L, active.randomId)
                    assertEquals(delivered.date + 900, active.expiresAt)
                    assertEquals(LiveLocationPhase.ACTIVE, active.phase)
                    assertFalse(active.expired(active.expiresAt!! - 1)); assertTrue(
                        active.expired(
                            active.expiresAt!!
                        )
                    )
                    restored.recoverLiveLocations(epoch)
                    val paused = restored.liveLocation(id, epoch)!!
                    assertEquals(LiveLocationPhase.PAUSED, paused.phase)
                    assertFalse(
                        restored.transitionLiveLocation(
                            id,
                            active.revision,
                            LiveLocationPhase.ACTIVE,
                            epoch
                        )
                    )
                    assertTrue(
                        restored.transitionLiveLocation(
                            id,
                            paused.revision,
                            LiveLocationPhase.STOPPING,
                            epoch
                        )
                    )
                    db.close(); db = open()
                    val restarted = OfflineWarmup(db)
                    val newEpoch = restarted.outgoingAccountEpoch()
                    restarted.recoverLiveLocations(newEpoch)
                    val uncertain = restarted.liveLocation(id, newEpoch)!!
                    assertEquals(LiveLocationPhase.UNCERTAIN_STOP, uncertain.phase)
                    assertFalse(
                        restarted.transitionLiveLocation(
                            id,
                            uncertain.revision,
                            LiveLocationPhase.ACTIVE,
                            newEpoch
                        )
                    )
                    assertTrue(
                        restarted.transitionLiveLocation(
                            id,
                            uncertain.revision,
                            LiveLocationPhase.STOPPING,
                            newEpoch
                        )
                    )
                    val stopping = restarted.liveLocation(id, newEpoch)!!
                    assertTrue(
                        restarted.transitionLiveLocation(
                            id,
                            stopping.revision,
                            LiveLocationPhase.STOPPED,
                            newEpoch
                        )
                    )
                    val stopped = restarted.liveLocation(id, newEpoch)!!
                    restarted.applyOutgoingResult(123, OutgoingMessageResult.Sent(delivered))
                    assertEquals(stopped, restarted.liveLocation(id, newEpoch))
                    assertFalse(
                        restarted.transitionLiveLocation(
                            id,
                            stopped.revision,
                            LiveLocationPhase.ACTIVE,
                            newEpoch
                        )
                    )
                    restarted.clearAccountCache()
                    assertTrue(runCatching {
                        restarted.transitionLiveLocation(
                            id,
                            stopped.revision,
                            LiveLocationPhase.STOPPING,
                            newEpoch
                        )
                    }.isFailure)
                    restarted.activateOutgoingAccount()
                    assertNull(restarted.liveLocation(id, restarted.outgoingAccountEpoch()))
                }
            } finally {
                db.close(); context.deleteDatabase(name)
            }
        }
    }
}
