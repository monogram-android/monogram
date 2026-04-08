package org.monogram.presentation.features.webapp

import org.json.JSONObject
import org.monogram.domain.models.webapp.WebAppPopupButton

interface TelegramWebAppHost {
    // Navigation & UI
    fun onOpenLink(url: String, tryBrowser: Boolean, tryInstantView: Boolean)
    fun onOpenTgLink(path: String)
    fun onOpenInvoice(slug: String)
    fun onClose(returnBack: Boolean)
    fun onExpand()
    fun onRequestFullscreen()
    fun onExitFullscreen()
    fun onToggleOrientationLock(locked: Boolean)
    fun onAddToHomeScreen()
    fun onCheckHomeScreen()
    fun onShareToStory(mediaUrl: String, text: String?, widgetLink: JSONObject?)

    // Popups & Dialogs
    fun onOpenPopup(title: String?, message: String, buttons: List<WebAppPopupButton>, callbackId: String)
    fun onOpenScanQrPopup(text: String?)
    fun onCloseScanQrPopup()
    fun onRequestPhone()
    fun onRequestWriteAccess()

    // Button & Bar Setup
    fun onSetupMainButton(
        isVisible: Boolean,
        isActive: Boolean,
        text: String,
        color: Int?,
        textColor: Int?,
        isProgressVisible: Boolean,
        hasShineEffect: Boolean,
        iconCustomEmojiId: String?
    )

    fun onSetupSecondaryButton(
        isVisible: Boolean,
        isActive: Boolean,
        text: String,
        color: Int?,
        textColor: Int?,
        isProgressVisible: Boolean,
        hasShineEffect: Boolean,
        position: String,
        iconCustomEmojiId: String?
    )

    fun onSetBackButtonVisible(visible: Boolean)
    fun onSetSettingsButtonVisible(visible: Boolean)
    fun onSetBackgroundColor(color: Int)
    fun onSetHeaderColor(colorKey: String?, customColor: Int?)
    fun onResetHeaderColor()
    fun onSetBottomBarColor(color: Int)
    fun onResetBottomBarColor()
    fun onSetupClosingBehavior(needConfirmation: Boolean)
    fun onSetupSwipeBehavior(allowVertical: Boolean)

    // Data & Sensors
    fun onSwitchInlineQuery(query: String, chatTypes: List<String>)
    fun onSendWebViewData(data: String)
    fun onReadClipboard(reqId: String)
    fun onInvokeCustomMethod(reqId: String, method: String, params: String)
    fun onSendPreparedMessage(id: String)
    fun onFileDownloadRequested(url: String, fileName: String)

    // Storage (Device & Secure)
    fun onDeviceStorageSave(reqId: String, key: String, value: String)
    fun onDeviceStorageGet(reqId: String, key: String)
    fun onDeviceStorageDelete(reqId: String, key: String)
    fun onSecureStorageSave(reqId: String, key: String, value: String)
    fun onSecureStorageGet(reqId: String, key: String)
    fun onSecureStorageDelete(reqId: String, key: String)

    // Biometry
    fun onBiometryGetInfo()
    fun onBiometryRequestAccess(reason: String?)
    fun onBiometryRequestAuth(reason: String?)
    fun onBiometryUpdateToken(token: String)
    fun onBiometryOpenSettings()

    // Location
    fun onRequestLocation()
    fun onCheckLocation()
    fun onOpenLocationSettings()

    // Others
    fun onSetEmojiStatus(customEmojiId: Long, duration: Int)
    fun onRequestEmojiStatusAccess()
    fun onVerifyAge(age: Double)
}
