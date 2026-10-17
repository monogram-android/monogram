package org.monogram.push

import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.common.push.NotificationDecision
import org.monogram.core.common.push.PushChannelKind
import org.monogram.core.common.push.parsePushPayload

@RunWith(AndroidJUnit4::class)
class ConversationShadeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearPosted() {
        NotificationPresenter.cancel(context, 7L)
        NotificationPresenter.cancel(context, -5L)
    }

    @Test
    fun directChatStacksMessagesWithoutAConversationTitle() {
        NotificationChannels.ensure(context)
        show("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","one"],"custom":{"from_id":7,"msg_id":1}}""", preview = true)
        show("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","two"],"custom":{"from_id":7,"msg_id":2}}""", preview = true)
        val style = styleFor(NotificationPresenter.notificationId(7L))
        assertNull(style.conversationTitle)
        assertEquals(listOf("one", "two"), style.messages.map { it.text?.toString() })
        assertEquals("chat:7", posted(NotificationPresenter.notificationId(7L))?.notification?.shortcutId)
    }

    @Test
    fun groupSetsConversationTitle() {
        NotificationChannels.ensure(context)
        show(
            """{"loc_key":"CHAT_MESSAGE_TEXT","loc_args":["Ada","Group","hi"],"custom":{"chat_id":5,"msg_id":4}}""",
            preview = true,
            kind = PushChannelKind.Group,
        )
        val style = styleFor(NotificationPresenter.notificationId(-5L))
        assertEquals("Group", style.conversationTitle?.toString())
    }

    @Test
    fun hiddenPreviewOmitsMessageText() {
        NotificationChannels.ensure(context)
        show(
            """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","secret-body-xyz"],"custom":{"from_id":7,"msg_id":3}}""",
            preview = false,
        )
        val notification = posted(NotificationPresenter.notificationId(7L))!!.notification
        val visible = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        val styleText = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.messages.orEmpty().joinToString { it.text?.toString().orEmpty() }
        assertFalse(visible.contains("secret-body-xyz"))
        assertFalse(styleText.contains("secret-body-xyz"))
        assertFalse(notification.extras.toString().contains("secret-body-xyz"))
    }

    @Test
    fun callsChannelIsHighImportance() {
        if (Build.VERSION.SDK_INT < 26) return
        NotificationChannels.ensure(context)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.CALLS)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    private fun show(json: String, preview: Boolean, kind: PushChannelKind = PushChannelKind.Private) {
        val payload = parsePushPayload(json)
        NotificationPresenter.show(
            context,
            payload,
            NotificationDecision(true, preview, false, false, false, false, kind),
        )
    }

    private fun posted(id: Int) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }

    private fun styleFor(id: Int): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted(id)!!.notification)
        assertNotNull(style)
        return style!!
    }
}
