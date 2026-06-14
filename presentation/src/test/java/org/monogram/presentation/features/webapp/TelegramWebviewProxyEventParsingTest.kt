package org.monogram.presentation.features.webapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.webapp.WebAppEvent

class TelegramWebviewProxyEventParsingTest {
    @Test
    fun `open popup parses callback id`() {
        val event = TelegramWebviewEventParser.parse(
            "web_app_open_popup",
            JSONObject()
                .put("title", "Title")
                .put("message", "Body")
                .put("callback_id", "cb-1")
        )

        assertTrue(event is WebAppEvent.OpenPopup)
        assertEquals("cb-1", (event as WebAppEvent.OpenPopup).callbackId)
    }

    @Test
    fun `parses device and secure storage clear events`() {
        val deviceEvent = TelegramWebviewEventParser.parse(
            "web_app_device_storage_clear",
            JSONObject().put("req_id", "req-1")
        )
        val secureEvent = TelegramWebviewEventParser.parse(
            "web_app_secure_storage_clear",
            JSONObject().put("req_id", "req-2")
        )

        assertEquals(WebAppEvent.DeviceStorageClear("req-1"), deviceEvent)
        assertEquals(WebAppEvent.SecureStorageClear("req-2"), secureEvent)
    }
}
