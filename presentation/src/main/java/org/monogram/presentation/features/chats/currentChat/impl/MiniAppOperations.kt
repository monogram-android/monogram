package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log

import kotlinx.coroutines.flow.update
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.handleOpenMiniApp(url: String, name: String, botUserId: Long) {
    if (botUserId != 0L && !botPreferences.getWebappPermission(botUserId, "tos_accepted")) {
        _state.update { it.copy(
            showMiniAppTOS = true,
            miniAppTOSBotUserId = botUserId,
            miniAppTOSUrl = url,
            miniAppTOSName = name
        ) }
    } else {
        _state.update { it.copy(
            miniAppUrl = url,
            miniAppName = name,
            miniAppBotUserId = botUserId
        ) }
    }
}

internal fun DefaultChatComponent.handleAcceptMiniAppTOS() {
    val botUserId = _state.value.miniAppTOSBotUserId
    val url = _state.value.miniAppTOSUrl
    val name = _state.value.miniAppTOSName
    if (botUserId != 0L && url != null && name != null) {
        botPreferences.setWebappPermission(botUserId, "tos_accepted", true)
        _state.update { it.copy(
            showMiniAppTOS = false,
            miniAppTOSBotUserId = 0L,
            miniAppTOSUrl = null,
            miniAppTOSName = null,
            miniAppUrl = url,
            miniAppName = name,
            miniAppBotUserId = botUserId
        ) }
    }
}

internal fun DefaultChatComponent.handleDismissMiniAppTOS() {
    _state.update { it.copy(
        showMiniAppTOS = false,
        miniAppTOSBotUserId = 0L,
        miniAppTOSUrl = null,
        miniAppTOSName = null
    ) }
}

internal fun DefaultChatComponent.handleOpenInvoice(slug: String?, messageId: Long?) {
    Log.d("DefaultChatComponent", "onOpenInvoice: slug=$slug, messageId=$messageId")
    _state.update { it.copy(invoiceSlug = slug, invoiceMessageId = messageId) }
}

internal fun DefaultChatComponent.handleDismissInvoice(status: String) {
    Log.d("DefaultChatComponent", "onDismissInvoice: status=$status")
    _state.update { it.copy(invoiceSlug = null, invoiceMessageId = null) }
}
