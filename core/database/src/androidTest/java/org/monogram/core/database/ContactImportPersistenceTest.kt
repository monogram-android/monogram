package org.monogram.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.models.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactImportPersistenceTest {
    @Test
    fun importKeyRetainsLegacyJsonEscapingForExistingRecords() {
        val contact = ContactSendSpec("+15555550123", "A/B", "Quoted \"name\"\nКонтакт")
        val legacy = org.json.JSONObject().put("send_version", 1).put("p", contact.phone)
            .put("f", contact.firstName).put("l", contact.lastName).put("c", "").put("u", 0)
            .toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacy.toByteArray(Charsets.UTF_8))
        val key = "contact_import:" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(key, contactImportMetaKey(contact))
        assertEquals(key, contactImportMetaKey(contact.copy(vcard = "Ignored")))
    }

    @Test
    fun syntheticImportRecoversUncertaintyAndRequiresExplicitRetryWithStableIdentityAndEpoch() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "import-attempt-${UUID.randomUUID()}.db"
            fun open() = Room.databaseBuilder(context, MonogramDatabase::class.java, name).build()
            var db = open()
            val contact =
                ContactSendSpec("+15555550123", "Synthetic", "Contact", "Excluded from import")
            try {
                val first = OfflineWarmup(db)
                val epoch = first.outgoingAccountEpoch()
                val started = first.beginContactImport(contact, epoch, retry = false)
                assertTrue(started.spec.clientId != 0L)
                assertEquals(
                    ContactImportPhase.IN_FLIGHT,
                    first.contactImportAttempt(contact, epoch)?.phase
                )
                try {
                    first.beginContactImport(
                        contact,
                        epoch,
                        retry = true
                    ); fail("concurrent import admitted")
                } catch (_: IllegalStateException) {
                }
                db.close(); db = open()
                val restored = OfflineWarmup(db)
                val current = restored.outgoingAccountEpoch()
                val uncertain = restored.contactImportAttempt(contact, current)!!
                assertEquals(started.spec, uncertain.spec)
                assertEquals(ContactImportPhase.UNCERTAIN, uncertain.phase)
                assertEquals(
                    uncertain,
                    restored.contactImportAttempt(contact.copy(vcard = "Different card"), current)
                )
                try {
                    restored.beginContactImport(
                        contact,
                        current,
                        retry = false
                    ); fail("interrupted import replayed")
                } catch (_: IllegalStateException) {
                }
                val retry = restored.beginContactImport(contact, current, retry = true)
                assertEquals(started.spec.clientId, retry.spec.clientId)
                assertTrue(
                    restored.finishContactImport(
                        contact,
                        current,
                        retry.copy(phase = ContactImportPhase.RETRY_REQUIRED)
                    )
                )
                assertEquals(
                    ContactImportPhase.RETRY_REQUIRED,
                    restored.contactImportAttempt(contact, current)?.phase
                )
                try {
                    restored.beginContactImport(
                        contact,
                        current,
                        retry = false
                    ); fail("server retry replayed implicitly")
                } catch (_: IllegalStateException) {
                }
                val last = restored.beginContactImport(contact, current, retry = true)
                assertEquals(started.spec, last.spec)
                val imported = last.copy(phase = ContactImportPhase.IMPORTED, userId = PeerId(42))
                assertTrue(restored.finishContactImport(contact, current, imported))
                assertEquals(imported, restored.contactImportAttempt(contact, current))
                try {
                    restored.finishContactImport(
                        contact,
                        current,
                        last.copy(phase = ContactImportPhase.UNCERTAIN)
                    ); fail("late settlement overwrote imported result")
                } catch (_: IllegalStateException) {
                }
                try {
                    restored.beginContactImport(
                        contact,
                        current,
                        retry = true
                    ); fail("terminal import repeated")
                } catch (_: IllegalStateException) {
                }
                restored.deactivateOutgoingAccount(); restored.activateOutgoingAccount()
                assertFalse(restored.finishContactImport(contact, current, imported))
                try {
                    restored.contactImportAttempt(
                        contact,
                        current
                    ); fail("stale account read accepted")
                } catch (_: IllegalStateException) {
                }
                restored.clearAccountCache()
                restored.activateOutgoingAccount()
                val clearedEpoch = restored.outgoingAccountEpoch()
                assertNull(restored.contactImportAttempt(contact, clearedEpoch))
                val unknown = restored.beginContactImport(contact, clearedEpoch, retry = false)
                restored.finishContactImport(
                    contact,
                    clearedEpoch,
                    unknown.copy(phase = ContactImportPhase.NOT_ADDED)
                )
                try {
                    restored.beginContactImport(
                        contact,
                        clearedEpoch,
                        retry = false
                    ); fail("unregistered result retried implicitly")
                } catch (_: IllegalStateException) {
                }
                val later = restored.beginContactImport(contact, clearedEpoch, retry = true)
                assertEquals(unknown.spec, later.spec)
                restored.finishContactImport(
                    contact,
                    clearedEpoch,
                    later.copy(phase = ContactImportPhase.NOT_ADDED)
                )
                Unit
            } finally {
                db.close(); context.deleteDatabase(name)
            }
        }
}
