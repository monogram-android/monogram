package org.monogram.mtproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.monogram_mtproto.CloudDraft
import uniffi.monogram_mtproto.MtprotoException

class NativeApiContractTest {
    @Test
    fun messagingEntryPointsLoadAndRejectUnknownHandles() {
        val native = MtprotoNativeLoader.loadOrStub()
        assertNotSame(MtprotoNative.Stub, native)
        assertEquals(232, native.apiLayer())
        val draft = CloudDraft(7L, 0, "synthetic draft", null, 0, 0, false, false)
        val upload = uniffi.monogram_mtproto.UploadItemDto(
            path = "synthetic-unused",
            kind = "photo",
            mimeType = "image/jpeg",
            fileName = "fixture.jpg",
            caption = "synthetic caption",
            entitiesJson = null,
            duration = 0,
            width = 0,
            height = 0,
            randomId = 123L,
        )
        val calls: List<() -> Unit> = listOf(
            { native.replaceUploadedMedia(0L, 7L, 37, "fixture", upload.copy(caption = "")) },
            { native.sendUploadedMedia(0L, 7L, upload) },
            { native.sendUploadedAlbum(0L, 7L, listOf(upload)) },
            {
                native.sendUploadedMediaWithOptions(
                    0L, 7L, upload, 0, 0, null, uniffi.monogram_mtproto.SendOptions(
                        silent = true, noWebpage = false, scheduleDate = 0x7ffffffe
                    )
                )
            },
            {
                native.sendUploadedAlbumWithOptions(
                    0L, 7L, listOf(upload), 0, 0, uniffi.monogram_mtproto.SendOptions(
                        silent = true, noWebpage = false, scheduleDate = 0x7ffffffe
                    )
                )
            },
            { native.getMessageReactionsPage(0L, 7L, 37, "fixture") },
            { native.getPollVotesPage(0L, 7L, 37, "fixture") },
            {
                native.sendDiceWithOptions(
                    0L, 7L, "🎲", 0, 0, 123L, uniffi.monogram_mtproto.SendOptions(
                        silent = true, noWebpage = false, scheduleDate = null
                    )
                )
            },
            { native.toggleTodoCompleted(0L, 7L, 37, listOf(0), emptyList()) },
            { native.appendTodoItems(0L, 7L, 37, 1, listOf("Fixture item")) },
            {
                native.sendPollWithOptions(
                    0L, 7L, uniffi.monogram_mtproto.PollSendSpec(
                        "Synthetic question",
                        listOf("First", "Second"),
                        true,
                        false,
                        false,
                        null,
                        null
                    ), 0, 0, 123L, uniffi.monogram_mtproto.SendOptions(
                        silent = false, noWebpage = false, scheduleDate = null
                    )
                )
            },
            {
                native.sendChecklistWithOptions(
                    0L, 7L, uniffi.monogram_mtproto.ChecklistSendSpec(
                        "Synthetic list", listOf("First", "Second"), true, false
                    ), 0, 0, 123L, uniffi.monogram_mtproto.SendOptions(
                        silent = false, noWebpage = false, scheduleDate = null
                    )
                )
            },
            {
                native.importContacts(
                    0L, listOf(
                        uniffi.monogram_mtproto.ContactImportSpec(
                            123L, "+15555550123", "Synthetic", "Contact"
                        )
                    )
                )
            },
            {
                native.sendLocationWithOptions(
                    0L,
                    7L,
                    uniffi.monogram_mtproto.LocationSendSpec(0.0, 0.0, 900, null),
                    0,
                    0,
                    123L,
                    uniffi.monogram_mtproto.SendOptions(false, false, null)
                )
            },
            {
                native.sendContactWithOptions(
                    0L, 7L, uniffi.monogram_mtproto.ContactSendSpec(
                        "+15555550123", "Synthetic", "Contact", ""
                    ), 0, 0, 123L, uniffi.monogram_mtproto.SendOptions(
                        silent = false, noWebpage = false, scheduleDate = null
                    )
                )
            },
            { native.editLiveLocation(0L, 7L, 37, null, null, null, true) },
            {
                native.removeChecklistItem(
                    0L,
                    7L,
                    37,
                    uniffi.monogram_mtproto.ChecklistTextEditDto(0, "", null, "Old", null)
                )
            },
            {
                native.editChecklistText(
                    0L, 7L, 37, uniffi.monogram_mtproto.ChecklistTextEditDto(
                        null, "Synthetic title", null, "Old", null
                    )
                )
            },
            { native.editChecklistTitle(0L, 7L, 37, "Synthetic title") },
            { native.closePoll(0L, 7L, 37) },
            { native.getMessage(0L, 7L, 37) },
            { native.getCloudDrafts(0L) },
            { native.saveCloudDraft(0L, draft) },
            {
                native.sendTextMessageWithOptions(
                    0L, 7L, "synthetic draft", 0, null, 0, null, 123L,
                    uniffi.monogram_mtproto.SendOptions(
                        silent = true, noWebpage = true, scheduleDate = 0x7ffffffe
                    ),
                )
            },
            { native.getScheduledHistory(0L, 7L) },
            { native.getScheduledMessages(0L, 7L, listOf(1)) },
            { native.sendScheduledMessages(0L, 7L, listOf(1)) },
            { native.deleteScheduledMessages(0L, 7L, listOf(1)) },
            { native.editScheduledMessage(0L, 7L, 1, 0x7ffffffe, "replacement", null, false) },
        )
        calls.forEach { call -> assertThrows(MtprotoException::class.java) { call() } }
    }
}
