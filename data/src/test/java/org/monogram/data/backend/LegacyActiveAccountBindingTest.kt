package org.monogram.data.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyActiveAccountBindingTest {
    @Test
    fun `defaults to current single-session account and supports explicit lifecycle`() {
        val binding = LegacyActiveAccountBinding()

        assertEquals("default", binding.accountId.value)
        binding.requireActive("default")

        binding.bind("account_2")
        assertEquals("account_2", binding.accountId.value)
        assertThrows(IllegalStateException::class.java) { binding.requireActive("default") }

        binding.clear("default")
        assertEquals("account_2", binding.accountId.value)

        binding.clear("account_2")
        assertThrows(IllegalStateException::class.java) { binding.requireActive("account_2") }
    }

    @Test
    fun `guard requires both active account and legacy assignment`() = runBlocking {
        val selection = FakeSelectionStore()
        val guard = LegacyBackendAccessGuard(LegacyActiveAccountBinding("account_1"), selection)

        guard.requireAccess("account_1")
        selection.backend = TelegramBackendKind.KOTLIN_MTPROTO

        assertThrows(IllegalStateException::class.java) {
            runBlocking { guard.requireAccess("account_1") }
        }
        Unit
    }

    private class FakeSelectionStore : TelegramBackendSelectionStore {
        var backend = TelegramBackendKind.LEGACY

        override suspend fun get(accountId: String) = backend
        override fun observe(accountId: String) = error("not used")
        override suspend fun select(accountId: String, backend: TelegramBackendKind) {
            this.backend = backend
        }
        override suspend fun reset(accountId: String) {
            backend = TelegramBackendKind.LEGACY
        }
    }
}
