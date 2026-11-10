package org.monogram.push

import android.app.NotificationManager
import android.graphics.Bitmap
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.common.push.NotificationDecision
import org.monogram.core.common.push.PushChannelKind
import org.monogram.core.common.push.parsePushPayload
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationShadeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                .use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
        }
        clearPosted()
        val local = org.monogram.core.common.push.NotificationLocalStore(context)
        for (chatId in listOf(7L, -5L, -1_000_000_000_009L)) local.saveNotificationLedger(
            chatId,
            org.monogram.core.common.push.NotificationLedger()
        )
        android.os.SystemClock.sleep(300)
    }

    @After
    fun clearPosted() {
        NotificationPresenter.cancel(context, 7L)
        NotificationPresenter.cancel(context, -5L)
        NotificationPresenter.cancel(context, -1_000_000_000_009L)
        NotificationPresenter.cancel(context, 7L, accountUserId = 11001L)
        NotificationPresenter.cancel(context, 7L, accountUserId = 22002L)
    }

    @Test
    fun identicalChatPushesPostIndependentlyForTwoAccounts() {
        NotificationChannels.ensure(context)
        val firstAccount = 11001L
        val secondAccount = 22002L
        val firstStore = org.monogram.core.common.push.NotificationLocalStore(context, firstAccount)
        val secondStore =
            org.monogram.core.common.push.NotificationLocalStore(context, secondAccount)
        firstStore.saveNotificationLedger(7L, org.monogram.core.common.push.NotificationLedger())
        secondStore.saveNotificationLedger(7L, org.monogram.core.common.push.NotificationLedger())
        val payload = parsePushPayload(
            """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hello"],"custom":{"from_id":7,"msg_id":71}}""",
        )
        val decision =
            NotificationDecision(true, true, false, false, false, true, PushChannelKind.Private)

        NotificationPresenter.show(context, payload, decision, accountUserId = firstAccount)
        NotificationPresenter.show(context, payload, decision, accountUserId = secondAccount)
        android.os.SystemClock.sleep(300)

        val first = posted(NotificationPresenter.notificationIdFor(payload, firstAccount))
        val second = posted(NotificationPresenter.notificationIdFor(payload, secondAccount))
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first!!.id != second!!.id)
        assertEquals("account:$firstAccount:chat:7", first.notification.shortcutId)
        assertEquals("account:$secondAccount:chat:7", second.notification.shortcutId)
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
    fun lateThumbnailStaysOnItsMessageAcrossAvatarRefreshAndDeletion() {
        NotificationChannels.ensure(context)
        val photo =
            parsePushPayload("""{"loc_key":"MESSAGE_PHOTO","loc_args":["Ada"],"custom":{"from_id":7,"msg_id":1}}""")
        val decision =
            NotificationDecision(true, true, false, false, false, false, PushChannelKind.Private)
        val file = File(context.cacheDir, "notification-test.jpg")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
        }
        try {
            NotificationPresenter.show(context, photo, decision)
            android.os.SystemClock.sleep(300)
            show(
                """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","newer"],"custom":{"from_id":7,"msg_id":2}}""",
                true
            )
            android.os.SystemClock.sleep(300)
            NotificationPresenter.show(context, photo, decision, quiet = true, pictureFile = file)
            android.os.SystemClock.sleep(300)
            var messages = styleFor(NotificationPresenter.notificationId(7L)).messages
            assertNotNull(messages.first().dataUri)
            assertEquals("image/jpeg", messages.first().dataMimeType)
            assertNull(messages.last().dataUri)
            NotificationPresenter.show(context, photo, decision, quiet = true)
            android.os.SystemClock.sleep(300)
            messages = styleFor(NotificationPresenter.notificationId(7L)).messages
            assertNotNull(messages.first().dataUri)
            NotificationPresenter.editMessage(
                context,
                7L,
                1,
                "restricted media",
                org.monogram.core.common.push.BadgeSettings(),
                hideMedia = true
            )
            awaitState { styleFor(NotificationPresenter.notificationId(7L)).messages.first().dataUri == null }
            NotificationPresenter.dropMessages(
                context,
                7L,
                setOf(1),
                null,
                org.monogram.core.common.push.BadgeSettings()
            )
            NotificationPresenter.show(context, photo, decision, quiet = true, pictureFile = file)
            messages = styleFor(NotificationPresenter.notificationId(7L)).messages
            assertEquals(1, messages.size)
            assertEquals("newer", messages.single().text.toString())
            assertNull(messages.single().dataUri)
        } finally {
            file.delete()
        }
    }

    @Test
    fun hiddenGroupPreviewOmitsSenderAndConversationAndCanBeRead() {
        NotificationChannels.ensure(context)
        show(
            """{"loc_key":"CHAT_MESSAGE_TEXT","loc_args":["secret-sender","secret-group","secret-body"],"custom":{"chat_id":5,"msg_id":4}}""",
            false,
            PushChannelKind.Group
        )
        val id = NotificationPresenter.notificationId(-5L)
        val notification = posted(id)!!.notification
        assertNull(styleFor(id).conversationTitle)
        assertFalse(notification.extras.toString().contains("secret-"))
        NotificationPresenter.show(
            context,
            parsePushPayload("""{"loc_key":"CHAT_MESSAGE_TEXT","loc_args":["secret-sender","secret-group","secret-body"],"custom":{"chat_id":5,"msg_id":4}}"""),
            NotificationDecision(true, true, false, false, false, false, PushChannelKind.Group),
            quiet = true,
        )
        android.os.SystemClock.sleep(300)
        assertFalse(posted(id)!!.notification.extras.toString().contains("secret-"))
        NotificationPresenter.dropMessages(
            context,
            -5L,
            emptySet(),
            4,
            org.monogram.core.common.push.BadgeSettings()
        )
        val deadline = android.os.SystemClock.elapsedRealtime() + 2_000
        while (posted(id) != null && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
        }
        assertNull(posted(id))
    }

    @Test
    fun captureImagePreviewInExpandedShade() {
        NotificationChannels.ensure(context)
        val file = File(context.cacheDir, "notification-render-test.jpg")
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(40, 110, 170))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW }
        canvas.drawCircle(160f, 100f, 60f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val payload =
            parsePushPayload("""{"loc_key":"MESSAGE_PHOTO","loc_args":["Notification preview test"],"custom":{"from_id":7,"msg_id":77}}""")
        val decision =
            NotificationDecision(true, true, false, false, false, false, PushChannelKind.Private)
        try {
            NotificationPresenter.show(context, payload, decision)
            android.os.SystemClock.sleep(300)
            NotificationPresenter.show(context, payload, decision, quiet = true, pictureFile = file)
            android.os.SystemClock.sleep(500)
            assertNotNull(styleFor(NotificationPresenter.notificationId(7L)).messages.single().dataUri)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("cmd statusbar expand-notifications").close()
            android.os.SystemClock.sleep(700)
            val screenshot = automation.takeScreenshot()
            assertNotNull(screenshot)
            File(context.getExternalFilesDir(null), "notification-preview-proof.png").outputStream()
                .use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            screenshot.recycle()
        } finally {
            file.delete()
        }
    }

    @Test
    fun rapidBurstKeepsDurableCountBeyondVisibleHistoryAndIgnoresOldDuplicates() {
        NotificationChannels.ensure(context)
        val decision =
            NotificationDecision(true, true, false, false, false, false, PushChannelKind.Private)
        for (messageId in 1..20) {
            NotificationPresenter.show(
                context,
                parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Burst test","message-$messageId"],"custom":{"from_id":7,"msg_id":$messageId}}"""),
                decision
            )
        }
        val id = NotificationPresenter.notificationId(7L)
        awaitState { posted(id)?.notification?.extras?.getInt("org.monogram.push.unread_count") == 20 }
        assertEquals(8, styleFor(id).messages.size)
        assertEquals(
            20,
            org.monogram.core.common.push.NotificationLocalStore(context)
                .notificationLedger(7L).pending.size
        )
        NotificationPresenter.show(
            context,
            parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Burst test","old duplicate"],"custom":{"from_id":7,"msg_id":1}}"""),
            decision
        )
        assertEquals("message-20", styleFor(id).messages.last().text.toString())
        NotificationPresenter.dropMessages(
            context,
            7L,
            emptySet(),
            15,
            org.monogram.core.common.push.BadgeSettings(enabled = false)
        )
        awaitState { posted(id)?.notification?.extras?.getInt("org.monogram.push.unread_count") == 5 }
        assertEquals(
            5,
            org.monogram.core.common.push.NotificationLocalStore(context)
                .notificationLedger(7L).pending.size
        )
    }

    @Test
    fun unscopedDeleteDoesNotDeleteAnEqualChannelMessageId() {
        NotificationChannels.ensure(context)
        show(
            """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","private"],"custom":{"from_id":7,"msg_id":4}}""",
            true
        )
        show(
            """{"loc_key":"CHANNEL_MESSAGE_TEXT","loc_args":["Channel","channel"],"custom":{"channel_id":9,"msg_id":4}}""",
            true,
            PushChannelKind.Channel
        )
        NotificationPresenter.dropUnscopedMessages(
            context,
            setOf(4),
            org.monogram.core.common.push.BadgeSettings()
        )
        awaitState { posted(NotificationPresenter.notificationId(7L)) == null }
        awaitState { posted(NotificationPresenter.notificationId(-1_000_000_000_009L)) != null }
        android.os.SystemClock.sleep(300)
        assertNotNull(posted(NotificationPresenter.notificationId(-1_000_000_000_009L)))
    }

    @Test
    fun emojiSequencesRemainIntactInNotificationText() {
        NotificationChannels.ensure(context)
        val text = "👨‍👩‍👧‍👦 👍🏽 ❤️ 🇺🇦"
        show(
            """{"loc_key":"MESSAGE_TEXT","loc_args":["Emoji preview test","$text"],"custom":{"from_id":7,"msg_id":5}}""",
            true
        )
        assertEquals(
            text,
            styleFor(NotificationPresenter.notificationId(7L)).messages.single().text.toString()
        )
    }

    @Test
    fun editsDuplicatesAndQuietRemovalsRefreshTheMultiChatSummary() {
        NotificationChannels.ensure(context)
        val direct =
            """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","original"],"custom":{"from_id":7,"msg_id":1}}"""
        show(direct, true)
        val originalTimestamp =
            styleFor(NotificationPresenter.notificationId(7L)).messages.single().timestamp
        show(
            """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","newer"],"custom":{"from_id":7,"msg_id":2}}""",
            true
        )
        show(
            """{"loc_key":"CHAT_MESSAGE_TEXT","loc_args":["Ada","Group","group-message"],"custom":{"chat_id":5,"msg_id":4}}""",
            true,
            PushChannelKind.Group
        )
        awaitState { posted(Int.MIN_VALUE) != null }
        // Each operation posts a child and a summary; respect Android's per-app enqueue quota.
        android.os.SystemClock.sleep(1_000)
        NotificationPresenter.show(
            context,
            parsePushPayload(direct),
            NotificationDecision(true, true, false, false, false, false, PushChannelKind.Private)
        )
        android.os.SystemClock.sleep(1_000)
        assertEquals(
            listOf("original", "newer"),
            styleFor(NotificationPresenter.notificationId(7L)).messages.map { it.text.toString() })
        NotificationPresenter.editMessage(
            context,
            7L,
            1,
            "edited",
            org.monogram.core.common.push.BadgeSettings()
        )
        awaitState { styleFor(NotificationPresenter.notificationId(7L)).messages.first().text.toString() == "edited" }
        var messages = styleFor(NotificationPresenter.notificationId(7L)).messages
        assertEquals(originalTimestamp, messages.first().timestamp)
        assertEquals(listOf("edited", "newer"), messages.map { it.text.toString() })
        android.os.SystemClock.sleep(1_000)
        NotificationPresenter.editMessage(
            context,
            7L,
            2,
            "edited-latest",
            org.monogram.core.common.push.BadgeSettings()
        )
        awaitState {
            posted(Int.MIN_VALUE)?.notification?.extras?.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
                ?.any { it.contains("edited-latest") } == true
        }
        android.os.SystemClock.sleep(1_000)
        val beforeDuplicateRevision =
            posted(NotificationPresenter.notificationId(7L))!!.notification.extras.getLong("org.monogram.push.revision")
        val decision =
            NotificationDecision(true, true, false, false, false, false, PushChannelKind.Private)
        NotificationPresenter.show(context, parsePushPayload(direct), decision)
        awaitState {
            posted(NotificationPresenter.notificationId(7L))!!.notification.extras.getLong(
                "org.monogram.push.revision"
            ) > beforeDuplicateRevision
        }
        val afterOlderDuplicateRevision =
            posted(NotificationPresenter.notificationId(7L))!!.notification.extras.getLong("org.monogram.push.revision")
        android.os.SystemClock.sleep(750)
        NotificationPresenter.show(
            context,
            parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","newer"],"custom":{"from_id":7,"msg_id":2}}"""),
            decision
        )
        awaitState {
            posted(NotificationPresenter.notificationId(7L))!!.notification.extras.getLong("org.monogram.push.revision") > afterOlderDuplicateRevision &&
                    styleFor(NotificationPresenter.notificationId(7L)).messages.map { it.text.toString() } == listOf(
                "edited",
                "edited-latest"
            ) &&
                    posted(Int.MIN_VALUE)?.notification?.extras?.getCharSequenceArray(
                        NotificationCompat.EXTRA_TEXT_LINES
                    )
                        ?.any { it.contains("edited-latest") } == true
        }
        assertEquals(
            2,
            org.monogram.core.common.push.NotificationLocalStore(context)
                .notificationLedger(7L).pending.size
        )
        messages = styleFor(NotificationPresenter.notificationId(7L)).messages
        assertEquals(2, messages.size)
        assertTrue(posted(NotificationPresenter.notificationId(7L))!!.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        android.os.SystemClock.sleep(1_000)
        NotificationPresenter.dropMessages(
            context,
            7L,
            setOf(2),
            null,
            org.monogram.core.common.push.BadgeSettings()
        )
        awaitState {
            posted(Int.MIN_VALUE)?.notification?.extras?.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
                ?.any { it.contains("edited") && !it.contains("edited-latest") } == true
        }
        NotificationPresenter.cancel(context, -5L)
        awaitState { posted(Int.MIN_VALUE) == null }
    }

    private fun awaitState(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(
            20
        )
        assertTrue("Android notification state did not converge", condition())
    }

    @Test
    fun inspectLivePreviewEntry() {
        val args = InstrumentationRegistry.getArguments()
        val marker = args.getString("proofMarker")
        val messageId = args.getString("proofMessageId")?.toIntOrNull()
        val chatId = args.getString("proofChatId")?.toLongOrNull() ?: return
        val entries = context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == NotificationPresenter.notificationId(chatId) }.flatMap {
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages.orEmpty()
            }
        val entry = if (marker != null) entries.firstOrNull { it.text?.contains(marker) == true }
        else if (messageId != null) entries.firstOrNull { it.extras.getInt("org.monogram.push.batch_msg_id") == messageId }
        else entries.maxByOrNull { it.timestamp }
        val result = android.os.Bundle().apply {
            putString("proof_found", (entry != null).toString())
            putString("proof_image", (entry?.dataUri != null).toString())
            putString("proof_mime", entry?.dataMimeType.orEmpty())
            putString("proof_emoji", (entry?.text?.contains("👨‍👩‍👧‍👦 👍🏽 ❤️ 🇺🇦") == true).toString())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result)
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
        val id = NotificationPresenter.notificationIdFor(payload)
        val expected =
            if (preview) payload.body else context.getString(org.monogram.R.string.push_hidden_body)
        val deadline = android.os.SystemClock.elapsedRealtime() + 2_000
        while (posted(id)?.notification?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)
                ?.toString() != expected &&
            android.os.SystemClock.elapsedRealtime() < deadline
        ) {
            android.os.SystemClock.sleep(20)
        }
        assertEquals(
            expected,
            posted(id)?.notification?.extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)
                ?.toString()
        )
        android.os.SystemClock.sleep(250)

    }

    private fun posted(id: Int) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }

    private fun styleFor(id: Int): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted(id)!!.notification)
        assertNotNull(style)
        return style!!
    }
}
