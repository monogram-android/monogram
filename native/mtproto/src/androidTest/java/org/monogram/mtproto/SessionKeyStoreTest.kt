package org.monogram.mtproto

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionKeyStoreTest {
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "session-key-test-${UUID.randomUUID()}",
    ).apply { mkdirs() }
    private val session = File(directory, "session")

    @After
    fun cleanUp() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (prefix in listOf("monogram.session.", "monore.session.")) {
            val alias = prefix + MessageDigest.getInstance("SHA-256")
                .digest(session.canonicalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            keyStore.deleteEntry(alias)
        }
        directory.deleteRecursively()
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
