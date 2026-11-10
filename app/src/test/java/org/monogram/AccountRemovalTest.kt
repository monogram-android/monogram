package org.monogram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AccountRemovalTest {
    @Test
    fun adoptionRejectsExistingAccountStorageWithoutDeletingIt() {
        val root = Files.createTempDirectory("monogram-adoption-storage").toFile()
        try {
            val files = root.resolve("files")
            val noBackup = root.resolve("no-backup")
            val database = root.resolve("databases/monogram-account-22.db")
            val paths = listOf(
                files.resolve("accounts/22"),
                files.resolve("session-identity-22.json"),
                noBackup.resolve("outgoing-media/22"),
                database,
                root.resolve("databases/monogram-account-22.db-journal"),
                root.resolve("databases/monogram-account-22.db-wal"),
                root.resolve("databases/monogram-account-22.db-shm"),
            )
            requireVacantAccountStorage(files, noBackup, database, 22)
            paths.forEach { path ->
                path.parentFile.mkdirs()
                path.writeText("existing")
                assertTrue(runCatching {
                    requireVacantAccountStorage(
                        files,
                        noBackup,
                        database,
                        22
                    )
                }.isFailure)
                assertTrue(path.readText() == "existing")
                path.delete()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyOutgoingCleanupDoesNotEnterOtherAccountDirectories() {
        val root = Files.createTempDirectory("monogram-legacy-outgoing").toFile().canonicalFile
        try {
            val id = "123e4567-e89b-12d3-a456-426614174000"
            val legacy = root.resolve("$id.bin").apply { writeText("legacy") }
            val partial = root.resolve("$id.bin.part").apply { writeText("partial") }
            val unrelated = root.resolve("notes.bin").apply { writeText("shared") }
            val other = root.resolve("22/$id.bin").apply {
                parentFile.mkdirs()
                writeText("other")
            }

            deleteLegacyOutgoingMedia(root)

            assertFalse(legacy.exists())
            assertFalse(partial.exists())
            assertTrue(unrelated.exists())
            assertTrue(other.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyFileCleanupPreservesOtherAccountAndSharedFiles() {
        val root = Files.createTempDirectory("monogram-legacy-removal").toFile()
        try {
            val session = root.resolve("mtproto.session.json").apply { writeText("session") }
            val sidecars = listOf(
                "mtproto.session.json.tmp", "mtproto.session.tmp", "mtproto.session.json.key",
                "mtproto.session.json.key.bak", "mtproto.session.json.key.new",
            ).map { root.resolve(it).apply { writeText("sidecar") } }
            val identity = root.resolve("session-identity.json").apply { writeText("identity") }
            val shared = root.resolve("shared.txt").apply { writeText("shared") }
            val other = root.resolve("accounts/22/mtproto.session.json").apply {
                parentFile.mkdirs()
                writeText("other")
            }

            deleteLegacyAccountFiles(root)

            assertFalse(session.exists())
            assertTrue(sidecars.none { it.exists() })
            assertFalse(identity.exists())
            assertTrue(shared.exists())
            assertTrue(other.exists())
            deleteLegacyAccountFiles(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
