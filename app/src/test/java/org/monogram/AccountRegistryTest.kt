package org.monogram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.AuthSession
import org.monogram.core.models.PeerId
import org.monogram.push.NotificationPresenter
import java.nio.file.Files

class AccountRegistryTest {
    @Test
    fun validLiveRegistryWinsOverStaleBackup() {
        val root = Files.createTempDirectory("monogram-registry-live-wins").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            val live = root.resolve("accounts.json").readText()
            root.resolve("accounts.json.bak").writeText(
                """{"activeUserId":22,"accounts":[{"userId":22,"dcId":4,"sessionPath":"x","databaseName":"monogram-account-22.db","mediaPath":"x"}]}"""
            )
            root.resolve("accounts.json").writeText(live)

            assertEquals(listOf(11L), AccountRegistry(root).load().map { it.userId })
            assertFalse(root.resolve("accounts.json.bak").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoresBackupAfterInterruptedWrite() {
        val root = Files.createTempDirectory("monogram-registry-recovery").toFile()
        try {
            AccountRegistry(root).ensure(AuthSession(PeerId(11), 2))
            val file = root.resolve("accounts.json")
            assertTrue(file.renameTo(root.resolve("accounts.json.bak")))
            file.writeText("{")
            val staged = root.resolve("accounts.json.tmp")
            staged.writeText("interrupted")

            val restored = AccountRegistry(root)
            assertEquals(listOf(11L), restored.load().map { it.userId })
            assertFalse(root.resolve("accounts.json.bak").exists())
            assertFalse(staged.exists())
            restored.ensure(AuthSession(PeerId(22), 4))
            assertEquals(listOf(11L, 22L), AccountRegistry(root).load().map { it.userId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingPrimaryRestoresBackupAndDiscardsStaging() {
        val root = Files.createTempDirectory("monogram-registry-missing-primary").toFile()
        try {
            AccountRegistry(root).ensure(AuthSession(PeerId(11), 2))
            assertTrue(root.resolve("accounts.json").renameTo(root.resolve("accounts.json.bak")))
            root.resolve("accounts.json.tmp").writeText("interrupted")

            val restored = AccountRegistry(root)
            assertEquals(listOf(11L), restored.load().map { it.userId })
            assertFalse(root.resolve("accounts.json.tmp").exists())
            restored.setActive(11)
            assertEquals(11L, AccountRegistry(root).apply { load() }.activeUserId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidBackupDoesNotReplaceOrRemoveRegistryFiles() {
        val root = Files.createTempDirectory("monogram-registry-invalid-backup").toFile()
        try {
            val primary = root.resolve("accounts.json")
            val backup = root.resolve("accounts.json.bak")
            val staged = root.resolve("accounts.json.tmp")
            staged.writeText("interrupted")
            val invalidState =
                """{"activeUserId":0,"accounts":[{"userId":0,"dcId":2,"sessionPath":"x","databaseName":"monogram.db","mediaPath":"x"}]}"""
            for (contents in listOf("{", invalidState)) {
                primary.writeText("{")
                backup.writeText(contents)
                assertNotNull(runCatching { AccountRegistry(root).load() }.exceptionOrNull())
                assertEquals("{", primary.readText())
                assertEquals(contents, backup.readText())
            }

            assertTrue(primary.delete())
            assertNotNull(runCatching { AccountRegistry(root).load() }.exceptionOrNull())
            assertFalse(primary.exists())
            assertEquals(invalidState, backup.readText())
            assertEquals("interrupted", staged.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loneStagedRegistryIsNotOverwritten() {
        val root = Files.createTempDirectory("monogram-registry-staged-only").toFile()
        try {
            val staged = root.resolve("accounts.json.tmp")
            val contents = "interrupted registry write"
            staged.writeText(contents)

            val registry = AccountRegistry(root)
            assertNotNull(runCatching { registry.load() }.exceptionOrNull())
            assertNotNull(runCatching {
                registry.ensure(
                    AuthSession(
                        PeerId(11),
                        2
                    )
                )
            }.exceptionOrNull())
            assertNotNull(runCatching {
                registry.adoptLegacy(
                    AuthSession(
                        PeerId(11),
                        2
                    )
                )
            }.exceptionOrNull())
            assertTrue(registry.all().isEmpty())
            assertEquals(null, registry.activeUserId)
            assertEquals(contents, staged.readText())
            assertFalse(root.resolve("accounts.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingStagedRegistryIsRemovedAfterFailedWriteRecovery() {
        val root = Files.createTempDirectory("monogram-registry-staged-write").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            val staged = root.resolve("accounts.json.tmp")
            val contents = "interrupted registry write"
            staged.writeText(contents)

            assertNotNull(runCatching {
                registry.ensure(
                    AuthSession(
                        PeerId(22),
                        4
                    )
                )
            }.exceptionOrNull())
            assertFalse(staged.exists())
            assertEquals(listOf(11L), registry.all().map { it.userId })
            registry.ensure(AuthSession(PeerId(22), 4))
            assertEquals(listOf(11L, 22L), AccountRegistry(root).load().map { it.userId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedWriteRestoresInMemoryAccountsFromDurableRegistry() {
        val root = Files.createTempDirectory("monogram-registry-write-failure").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            assertTrue(root.resolve("accounts.json.tmp").mkdir())

            assertNotNull(runCatching {
                registry.ensure(
                    AuthSession(
                        PeerId(22),
                        4
                    )
                )
            }.exceptionOrNull())
            assertNotNull(runCatching { registry.all() }.exceptionOrNull())
            val staged = root.resolve("accounts.json.tmp")
            assertTrue(staged.isDirectory)
            assertNotNull(runCatching { AccountRegistry(root).load() }.exceptionOrNull())
            assertTrue(staged.delete())
            assertEquals(listOf(11L), registry.load().map { it.userId })
            assertEquals(11L, registry.activeUserId)
            registry.ensure(AuthSession(PeerId(22), 4))
            assertEquals(listOf(11L, 22L), AccountRegistry(root).load().map { it.userId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedWriteAndRecoveryCannotExposeUncommittedAccounts() {
        val root = Files.createTempDirectory("monogram-registry-unreconciled").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            val primary = root.resolve("accounts.json")
            val durable = primary.readText()
            primary.writeText("{")
            val staged = root.resolve("accounts.json.tmp")
            staged.writeText("interrupted")

            val failure =
                runCatching { registry.ensure(AuthSession(PeerId(22), 4)) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.suppressed.isNotEmpty())
            assertNotNull(runCatching { registry.all() }.exceptionOrNull())
            assertNotNull(runCatching { registry.get(11) }.exceptionOrNull())
            assertNotNull(runCatching { registry.activeUserId }.exceptionOrNull())
            assertNotNull(runCatching { registry.remove(11) }.exceptionOrNull())
            assertEquals("interrupted", staged.readText())

            primary.writeText(durable)
            assertEquals(listOf(11L), registry.load().map { it.userId })
            assertEquals(11L, registry.activeUserId)
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun startupRejectsRegisteredAccountsWithoutAnActiveAccount() {
        val root = Files.createTempDirectory("monogram-registry-no-active").toFile()
        try {
            val registry = AccountRegistry(root)
            assertEquals(null, registry.activeForStartup())
            registry.ensure(AuthSession(PeerId(11), 2), activate = false)
            val restored = AccountRegistry(root).also { it.load() }
            assertNotNull(runCatching { restored.activeForStartup() }.exceptionOrNull())
            assertEquals(listOf(11L), restored.all().map { it.userId })
            restored.setActive(11)
            assertEquals(11L, restored.activeForStartup()?.userId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptRegistryDoesNotBecomeAnEmptyRegistry() {
        val root = Files.createTempDirectory("monogram-corrupt-registry").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            root.resolve("accounts.json").writeText("{")

            val failure = runCatching { registry.load() }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(listOf(11L), registry.all().map { it.userId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun multiAccountNotificationsIdentifyTheirOwningAccountAndServiceEvents() {
        assertEquals(
            "New message · Account 22",
            NotificationPresenter.accountQualifiedNotificationTitle("New message", "Account 22", 2),
        )
        assertEquals(
            "New message",
            NotificationPresenter.accountQualifiedNotificationTitle("New message", "Account 22", 1),
        )
        val servicePayload = org.monogram.core.common.push.PushPayload(
            locKey = "SESSION_REVOKE",
            locArgs = emptyList(),
            title = "",
            body = "",
            chatId = null,
            messageId = null,
            maxId = null,
            deletedIds = emptyList(),
            mention = false,
            silent = false,
            sound = null,
            attachB64 = null,
            userId = null,
            action = org.monogram.core.common.push.PushAction.SessionRevoke,
            channelKind = org.monogram.core.common.push.PushChannelKind.Other,
        )
        assertTrue(
            NotificationPresenter.notificationIdFor(servicePayload, 11L) !=
                    NotificationPresenter.notificationIdFor(servicePayload, 22L),
        )
    }

    @Test
    fun bubblesUseAccountQualifiedConversationShortcutIds() {
        assertEquals("account:11:chat:7", NotificationPresenter.shortcutId(7L, 11L))
        assertEquals("account:22:chat:7", NotificationPresenter.shortcutId(7L, 22L))
        assertEquals("chat:7", NotificationPresenter.shortcutId(7L, null))
    }

    @Test
    fun accountQualifiedNotificationIdsDoNotCollideAcrossAccounts() {
        val first = NotificationPresenter.notificationId(7L, 11L)
        val second = NotificationPresenter.notificationId(7L, 22L)
        assertTrue(first != second)
    }

    @Test
    fun firstAuthorizationAdoptsLegacyAndAdditionalAuthorizationUsesKeyedStorage() {
        val root = Files.createTempDirectory("monogram-authorized-accounts").toFile()
        try {
            val registry = AccountRegistry(root)
            val first = registry.adoptAuthorized(AuthSession(PeerId(11), 2))
            val second = registry.adoptAuthorized(AuthSession(PeerId(22), 4))

            assertEquals("monogram.db", first.databaseName)
            assertEquals("monogram-account-22.db", second.databaseName)
            assertFalse(first.sessionPath == second.sessionPath)
            assertFalse(first.mediaPath == second.mediaPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyAndAccountStorageSurviveRegistryReload() {
        val root = Files.createTempDirectory("monogram-registry-validation").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.adoptAuthorized(AuthSession(PeerId(11), 2))
            registry.adoptAuthorized(AuthSession(PeerId(22), 4))

            val accounts = AccountRegistry(root).load()
            assertEquals("monogram.db", accounts[0].databaseName)
            assertEquals(root.resolve("mtproto.session.json"), accounts[0].sessionPath)
            assertEquals("monogram-account-22.db", accounts[1].databaseName)
            assertEquals(root.resolve("accounts/22/mtproto.session.json"), accounts[1].sessionPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unusableOrDuplicateRegistryDoesNotFallBackToLegacyStorage() {
        val root = Files.createTempDirectory("monogram-registry-invalid").toFile()
        try {
            val file = root.resolve("accounts.json")
            val invalid =
                """{"activeUserId":0,"accounts":[{"userId":0,"dcId":2,"sessionPath":"x","databaseName":"monogram.db","mediaPath":"x"}]}"""
            val duplicate =
                """{"activeUserId":11,"accounts":[{"userId":11,"dcId":2,"sessionPath":"x","databaseName":"monogram.db","mediaPath":"x"},{"userId":11,"dcId":2,"sessionPath":"x","databaseName":"monogram-account-11.db","mediaPath":"x"}]}"""
            for (contents in listOf(invalid, duplicate)) {
                file.writeText(contents)
                assertNotNull(runCatching { AccountRegistry(root).load() }.exceptionOrNull())
                assertEquals(contents, file.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoredPathsAreDerivedAndCannotBeRedirectedByRegistryContents() {
        val root = Files.createTempDirectory("monogram-registry-paths").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            root.resolve("accounts.json").writeText(
                """{"activeUserId":11,"accounts":[{"userId":11,"dcId":2,"sessionPath":"/outside/session","databaseName":"monogram-account-99.db","mediaPath":"/outside/media"}]}"""
            )
            val restored = AccountRegistry(root)
            val account = restored.load().single()
            assertEquals(root.resolve("accounts/11/mtproto.session.json"), account.sessionPath)
            assertEquals("monogram-account-11.db", account.databaseName)
            assertEquals(root.resolve("accounts/11/media"), account.mediaPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun registeringWithoutActivationPreservesActiveAccountAcrossRestart() {
        val root = Files.createTempDirectory("monogram-account-staging").toFile()
        try {
            val registry = AccountRegistry(root)
            registry.ensure(AuthSession(PeerId(11), 2))
            registry.ensure(AuthSession(PeerId(22), 4), activate = false)
            val restored = AccountRegistry(root).also { it.load() }
            assertEquals(listOf(11L, 22L), restored.all().map { it.userId })
            assertEquals(11L, restored.activeUserId)
            restored.setActive(22)
            restored.ensure(AuthSession(PeerId(11), 3), activate = false)
            assertEquals(22L, AccountRegistry(root).also { it.load() }.activeUserId)
            restored.ensure(AuthSession(PeerId(11), 4))
            val reactivated = AccountRegistry(root).also { it.load() }
            assertEquals(11L, reactivated.activeUserId)
            assertEquals(4, reactivated.get(11)?.dcId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistsAccountsAndKeepsStoragePathsIndependent() {
        val root = Files.createTempDirectory("monogram-accounts").toFile()
        try {
            val first = AccountRegistry(root)
            first.ensure(AuthSession(PeerId(11), 2))
            first.ensure(AuthSession(PeerId(22), 4))
            first.setActive(22)

            val restored = AccountRegistry(root)
            assertEquals(listOf(11L, 22L), restored.load().map { it.userId })
            assertEquals(22L, restored.activeUserId)
            assertNotNull(restored.get(11)?.sessionPath)
            assertFalse(restored.get(11)!!.sessionPath == restored.get(22)!!.sessionPath)

            val removed = restored.remove(11)
            assertEquals(11L, removed?.userId)
            assertEquals(listOf(22L), restored.all().map { it.userId })
            assertTrue(restored.get(11) == null)
        } finally {
            root.deleteRecursively()
        }
    }
}
