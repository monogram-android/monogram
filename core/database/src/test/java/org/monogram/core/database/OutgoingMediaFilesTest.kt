package org.monogram.core.database

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutgoingMediaFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun retainedCopiesSurviveSourceCleanupAndStoreRecreation() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile("attach-photo").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val descriptor = OutgoingMediaFiles(root).retain(listOf(original), 3).single()
        assertFalse(descriptor.name.startsWith("attach-"))
        original.delete()
        val restored = OutgoingMediaFiles(root)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.verifiedFile(descriptor).readBytes())
        restored.release(listOf(descriptor))
        restored.release(listOf(descriptor))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun unavailableOwnedDirectoryIsAnIoFailure() = runBlocking {
        val blocked = temporary.newFile("blocked")
        val original = temporary.newFile().apply { writeText("one") }
        expectFailure<IOException> { OutgoingMediaFiles(blocked).retain(listOf(original), 10) }
        assertEquals("one", original.readText())
    }

    @Test
    fun changedSourceDoesNotChangeTheRetainedPayload() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile().apply { writeText("one") }
        val store = OutgoingMediaFiles(root)
        val descriptor = store.retain(listOf(original), 10).single()
        original.writeText("two")
        assertEquals("one", store.verifiedFile(descriptor).readText())
        Unit
    }

    @Test
    fun failedBatchRemovesAlreadyCopiedFilesAndTemporaryFiles() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile().apply { writeText("one") }
        expectFailure<IOException> {
            OutgoingMediaFiles(root).retain(listOf(original, File(temporary.root, "missing")), 10)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertEquals("one", original.readText())
    }

    @Test
    fun aggregateBudgetIsEnforcedAcrossTheWholeBatch() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile().apply { writeText("one") }
        expectFailure<IOException> {
            OutgoingMediaFiles(root).retain(
                listOf(original, original),
                5
            )
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun hashAndLengthChangesAreRejectedBeforeUpload() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile().apply { writeText("one") }
        val store = OutgoingMediaFiles(root)
        val descriptor = store.retain(listOf(original), 10).single()
        File(root, descriptor.name).writeText("two")
        expectFailure<IOException> { store.verifiedFile(descriptor) }
        File(root, descriptor.name).writeText("longer")
        expectFailure<IOException> { store.verifiedFile(descriptor) }
    }

    @Test
    fun malformedOwnershipCannotDeleteOrReadAnExternalFile() = runBlocking {
        val root = temporary.newFolder("owned")
        val outside = temporary.newFile("outside.bin").apply { writeText("private") }
        val store = OutgoingMediaFiles(root)
        val descriptor = RetainedMediaSource("../outside.bin", 7, "0".repeat(64))
        val valid = store.retain(listOf(outside), 7).single()
        expectFailure<IOException> { store.release(listOf(valid, descriptor)) }
        assertEquals("private", store.verifiedFile(valid).readText())
        expectFailure<IOException> { store.verifiedFile(descriptor) }
        assertEquals("private", outside.readText())
    }

    @Test
    fun cancelledAdmissionCreatesNoFiles() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile().apply { writeText("one") }
        expectFailure<CancellationException> {
            withContext(Job().apply { cancel() }) {
                OutgoingMediaFiles(root).retain(
                    listOf(original),
                    10
                )
            }
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun recoveryKeepsReferencedCopiesAndOnlyRemovesOwnedOrphans() = runBlocking {
        val root = File(temporary.root, "owned")
        val original = temporary.newFile().apply { writeText("one") }
        val store = OutgoingMediaFiles(root)
        val sources = store.retain(listOf(original, original), 6)
        val partial = File(
            root,
            java.util.UUID.randomUUID().toString() + ".bin.part"
        ).apply { writeText("partial") }
        val unrelated = File(root, "unrelated.bin").apply { writeText("keep") }
        store.removeUnreferenced(setOf(sources.first().name))
        assertEquals("one", store.verifiedFile(sources.first()).readText())
        assertFalse(File(root, sources.last().name).exists())
        assertFalse(partial.exists())
        assertEquals("keep", unrelated.readText())
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try {
            block(); fail("expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
