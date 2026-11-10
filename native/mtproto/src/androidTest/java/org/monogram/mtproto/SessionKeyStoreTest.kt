package org.monogram.mtproto

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

class SessionKeyStoreTest {
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "session-key-test-${UUID.randomUUID()}",
    ).apply { mkdirs() }
    private val session = File(directory, "session")
    private val promoted = File(directory, "account/session")

    @After
    fun cleanUp() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (file in listOf(session, promoted)) {
            for (prefix in listOf("monogram.session.", "monore.session.")) {
                val alias = prefix + MessageDigest.getInstance("SHA-256")
                    .digest(file.canonicalPath.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                keyStore.deleteEntry(alias)
            }
        }
        directory.deleteRecursively()
    }

    @Test
    fun promotionRewrapsOriginalKeyForDestinationPath() {
        val original = SessionKeyStore.loadOrCreate(session.path)
        val encrypted = byteArrayOf(0xC3.toByte(), 0xA7.toByte(), 0x5E, 0x01, 42)
        session.writeBytes(encrypted)
        try {
            MtprotoNativeLoader.promoteSession(session, promoted)
            assertFalse(session.exists())
            assertArrayEquals(encrypted, promoted.readBytes())
            val restored = SessionKeyStore.loadOrCreate(promoted.path)
            try {
                assertArrayEquals(original, restored)
            } finally {
                restored.fill(0)
            }
        } finally {
            original.fill(0)
        }
    }

    @Test
    fun promotionDoesNotOverwriteExistingSession() {
        session.writeText("source")
        promoted.parentFile!!.mkdirs()
        promoted.writeText("existing")
        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.promoteSession(session, promoted)
        }
        assertEquals("source", session.readText())
        assertEquals("existing", promoted.readText())
    }

    @Test
    fun recoveryCompletesBeforeRenameAndIsIdempotentAfterRename() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        session.writeText("session data")
        MtprotoNativeLoader.promoteSession(session, promoted)
        val sourceKey = File("${session.path}.key").readBytes()
        val destinationKey = File("${promoted.path}.key").readBytes()
        check(promoted.renameTo(session))

        MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        MtprotoNativeLoader.recoverSessionPromotion(session, promoted)

        assertFalse(session.exists())
        assertEquals("session data", promoted.readText())
        assertArrayEquals(sourceKey, File("${session.path}.key").readBytes())
        assertArrayEquals(destinationKey, File("${promoted.path}.key").readBytes())
    }

    @Test
    fun recoveryRejectsDifferentKeysWithoutChangingFiles() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        promoted.parentFile!!.mkdirs()
        SessionKeyStore.loadOrCreate(promoted.path).fill(0)
        session.writeText("source")
        val sourceKey = File("${session.path}.key").readBytes()
        val destinationKey = File("${promoted.path}.key").readBytes()

        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        }
        assertEquals("source", session.readText())
        assertFalse(promoted.exists())
        assertArrayEquals(sourceKey, File("${session.path}.key").readBytes())
        assertArrayEquals(destinationKey, File("${promoted.path}.key").readBytes())
    }

    @Test
    fun recoveryRejectsMissingAndDamagedWrappedKeys() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        session.writeText("source")
        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        }
        MtprotoNativeLoader.promoteSession(session, promoted)
        val keyFile = File("${promoted.path}.key")
        val damaged =
            keyFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        keyFile.writeBytes(damaged)
        assertThrows(Exception::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(
                session,
                promoted
            )
        }
        assertArrayEquals(damaged, keyFile.readBytes())
        assertEquals("source", promoted.readText())
    }

    @Test
    fun recoveryRejectsKeysCopiedBetweenPaths() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        session.writeText("source")
        MtprotoNativeLoader.promoteSession(session, promoted)
        val copiedKey = File("${session.path}.key").readBytes()
        val destinationKey = File("${promoted.path}.key")
        destinationKey.writeBytes(copiedKey)

        assertThrows(Exception::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(
                session,
                promoted
            )
        }
        assertArrayEquals(copiedKey, destinationKey.readBytes())
        assertEquals("source", promoted.readText())
    }

    @Test
    fun recoveryRejectsBackupsAndAmbiguousSessionStates() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        session.writeText("source")
        MtprotoNativeLoader.promoteSession(session, promoted)
        val backup = File("${session.path}.key.bak")
        backup.writeText("backup")
        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        }
        assertEquals("backup", backup.readText())
        backup.delete()
        session.writeText("other")
        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        }
        assertEquals("other", session.readText())
        assertEquals("source", promoted.readText())
    }

    @Test
    fun recoveryRejectsAtomicNewAndMissingSessions() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        session.writeText("source")
        MtprotoNativeLoader.promoteSession(session, promoted)
        val pending = File("${promoted.path}.key.new")
        pending.writeText("pending")
        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        }
        assertEquals("pending", pending.readText())
        check(pending.renameTo(File(directory, "parked-key")))
        check(promoted.renameTo(File(directory, "parked-session")))
        assertThrows(IllegalStateException::class.java) {
            MtprotoNativeLoader.recoverSessionPromotion(session, promoted)
        }
        assertFalse(session.exists())
        assertFalse(promoted.exists())
    }

    @Test
    fun wrappedKeyRestoresWithoutWritingPlaintextKey() {
        val first = SessionKeyStore.loadOrCreate(session.path)
        val second = SessionKeyStore.loadOrCreate(session.path)
        try {
            assertArrayEquals(first, second)
            val disk = File("${session.path}.key").readBytes()
            assertEquals(61, disk.size)
            assertFalse(disk.asList().windowed(first.size).any { it == first.asList() })
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun encryptedSessionWithoutWrappedKeyIsNotOverwritten() {
        session.writeBytes(byteArrayOf(0xC3.toByte(), 0xA7.toByte(), 0x5E, 0x01))
        assertThrows(IllegalStateException::class.java) { SessionKeyStore.loadOrCreate(session.path) }
        assertFalse(File("${session.path}.key").exists())
    }

    @Test
    fun brandedEncryptedSessionWithoutWrappedKeyIsNotOverwritten() {
        session.writeBytes("MONOGRAM_SESSION\u0001".toByteArray(Charsets.US_ASCII))
        assertThrows(IllegalStateException::class.java) { SessionKeyStore.loadOrCreate(session.path) }
        assertFalse(File("${session.path}.key").exists())
    }

    @Test
    fun legacyEncryptedSessionWithoutWrappedKeyIsNotOverwritten() {
        session.writeBytes("MONORE_SESSION\u0001".toByteArray(Charsets.US_ASCII))
        assertThrows(IllegalStateException::class.java) { SessionKeyStore.loadOrCreate(session.path) }
        assertFalse(File("${session.path}.key").exists())
    }

    @Test
    fun damagedWrappedKeyFailsWithoutReplacement() {
        SessionKeyStore.loadOrCreate(session.path).fill(0)
        val file = File("${session.path}.key")
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(Exception::class.java) { SessionKeyStore.loadOrCreate(session.path) }
        assertArrayEquals(damaged, file.readBytes())
    }
}
