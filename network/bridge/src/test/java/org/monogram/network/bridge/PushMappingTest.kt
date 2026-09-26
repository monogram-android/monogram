package org.monogram.network.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.PushTokenType
import uniffi.monogram_mtproto.NotifyExceptionDto
import uniffi.monogram_mtproto.NotifySettingsDto
import org.monogram.network.bridge.notify.toModel

class PushMappingTest {
    @Test
    fun tokenTypesMatchRegisterDeviceDocs() {
        assertEquals(2, PushTokenType.Fcm.code)
        assertEquals(4, PushTokenType.Simple.code)
        assertEquals(10, PushTokenType.WebPush.code)
        assertEquals(PushTokenType.Fcm, PushTokenType.fromCode(2))
    }

    @Test
    fun notifySettingsDtoMapsWithoutUniffiLeakIntoDomain() {
        val settings = NotifySettingsDto(
            showPreviews = true,
            silent = false,
            muteUntil = 99,
            storiesMuted = true,
            storiesHideSender = false,
            sound = "none",
        ).toModel()
        assertEquals(99, settings.muteUntil)
        assertTrue(settings.storiesMuted)
        assertEquals("none", settings.sound)
        val exception = NotifyExceptionDto(
            peerKind = "peer",
            chatId = 7,
            showPreviews = false,
            silent = true,
            muteUntil = 1,
            storiesMuted = false,
            storiesHideSender = true,
            sound = "default",
        ).toModel()
        assertEquals(7, exception.chatId.value)
        assertEquals("peer", exception.peerKind)
        assertTrue(exception.settings.silent)
    }

    @Test
    fun tokenErrorsMapToTelegramError() {
        val empty = TelegramError.parse("RPC 400: TOKEN_EMPTY")
        assertEquals("TOKEN_EMPTY", empty.type)
        assertEquals(400, empty.httpCode)
        val invalid = TelegramError.parse("RPC 400: TOKEN_TYPE_INVALID")
        assertEquals("TOKEN_TYPE_INVALID", invalid.type)
        val web = TelegramError.parse("RPC 400: WEBPUSH_TOKEN_INVALID")
        assertEquals("WEBPUSH_TOKEN_INVALID", web.type)
    }
}
