package org.monogram.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.AccountRecord
import java.io.File

class NotificationActionOwnerTest {
    private fun account(id: Long, legacy: Boolean = false) = AccountRecord(
        userId = id,
        dcId = 1,
        sessionPath = File("session-$id"),
        databaseName = if (legacy) "monogram.db" else "monogram-account-$id.db",
        mediaPath = File("media-$id"),
    )

    @Test
    fun qualifiedActionsRequireRegisteredOwner() {
        val accounts = listOf(account(1, legacy = true), account(2))
        assertEquals(2L, notificationActionOwner(2, accounts))
        assertNull(notificationActionOwner(3, accounts))
    }

    @Test
    fun notificationTapSwitchesToOwningAccountBeforeOpeningChat() {
        val accounts = listOf(account(1, legacy = true), account(2))
        assertEquals(NotificationAccountRoute.Switch(2), notificationAccountRoute(2, 1, accounts))
        assertEquals(NotificationAccountRoute.Open, notificationAccountRoute(2, 2, accounts))
        assertEquals(NotificationAccountRoute.Reject, notificationAccountRoute(3, 1, accounts))
    }

    @Test
    fun unqualifiedActionsRequireSingleLegacyAccount() {
        assertEquals(1L, notificationActionOwner(0, listOf(account(1, legacy = true))))
        assertNull(notificationActionOwner(0, listOf(account(1))))
        assertNull(notificationActionOwner(0, listOf(account(1, legacy = true), account(2))))
    }
}
