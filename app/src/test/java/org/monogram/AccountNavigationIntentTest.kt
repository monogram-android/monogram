package org.monogram

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.push.NotificationPresenter
import org.monogram.root.RootComponent

class AccountNavigationIntentTest {
    @Test
    fun notificationIsHandledOnceButFreshTapAndOtherIntentsStillWork() {
        val chat = NotificationPresenter.ACTION_OPEN_CHAT
        val notifications = NotificationPresenter.ACTION_OPEN_NOTIFICATIONS
        assertTrue(
            shouldHandleIncomingIntent(
                chat,
                shareHandled = false,
                notificationHandled = false
            )
        )
        assertFalse(
            shouldHandleIncomingIntent(
                chat,
                shareHandled = false,
                notificationHandled = true
            )
        )
        assertTrue(
            shouldHandleIncomingIntent(
                notifications,
                shareHandled = true,
                notificationHandled = false
            )
        )
        assertFalse(
            shouldHandleIncomingIntent(
                notifications,
                shareHandled = false,
                notificationHandled = true
            )
        )
        assertFalse(
            shouldHandleIncomingIntent(
                Intent.ACTION_SEND,
                shareHandled = true,
                notificationHandled = false
            )
        )
        assertTrue(
            shouldHandleIncomingIntent(
                Intent.ACTION_SEND_MULTIPLE,
                shareHandled = false,
                notificationHandled = true
            )
        )
        assertTrue(
            shouldHandleIncomingIntent(
                Intent.ACTION_VIEW,
                shareHandled = true,
                notificationHandled = true,
                linkHandled = false
            )
        )
        assertFalse(
            shouldHandleIncomingIntent(
                Intent.ACTION_VIEW,
                shareHandled = false,
                notificationHandled = false,
                linkHandled = true
            )
        )
    }

    @Test
    fun switchDestinationSurvivesSavedStateEncoding() {
        val destinations = listOf(
            RootComponent.Config.Home,
            RootComponent.Config.Proxy,
            RootComponent.Config.Settings(),
            RootComponent.Config.Settings(openProxy = true),
            RootComponent.Config.Settings(openFolders = true),
            RootComponent.Config.Settings(openNotifications = true),
        )
        destinations.forEach { destination ->
            assertEquals(
                destination,
                accountSwitchDestinationFromKey(accountSwitchDestinationKey(destination))
            )
        }
        assertNull(accountSwitchDestinationFromKey(null))
        assertNull(accountSwitchDestinationFromKey("unknown"))
    }
}
