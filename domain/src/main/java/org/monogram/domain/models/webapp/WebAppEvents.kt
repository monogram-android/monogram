package org.monogram.domain.models.webapp

sealed class WebAppEvent {
    data object Ready : WebAppEvent()
    data class Close(val returnBack: Boolean) : WebAppEvent()
    data object Expand : WebAppEvent()
    data object RequestViewport : WebAppEvent()
    data object RequestTheme : WebAppEvent()
    data class SetBackgroundColor(val color: String) : WebAppEvent()
    data class SetHeaderColor(val colorKey: String?, val color: String?) : WebAppEvent()
    data class SetBottomBarColor(val color: String) : WebAppEvent()
    data class SetupMainButton(
        val isVisible: Boolean,
        val isActive: Boolean,
        val text: String,
        val color: String?,
        val textColor: String?,
        val isProgressVisible: Boolean,
        val hasShineEffect: Boolean,
        val iconCustomEmojiId: String?
    ) : WebAppEvent()

    data class SetupSecondaryButton(
        val isVisible: Boolean,
        val isActive: Boolean,
        val text: String,
        val color: String?,
        val textColor: String?,
        val isProgressVisible: Boolean,
        val hasShineEffect: Boolean,
        val position: String,
        val iconCustomEmojiId: String?
    ) : WebAppEvent()

    data class SetupBackButton(val isVisible: Boolean) : WebAppEvent()
    data class SetupSettingsButton(val isVisible: Boolean) : WebAppEvent()
    data class OpenPopup(
        val title: String?,
        val message: String,
        val buttons: List<WebAppPopupButton>
    ) : WebAppEvent()

    data class OpenLink(val url: String, val tryBrowser: Boolean, val tryInstantView: Boolean) : WebAppEvent()
    data class OpenTgLink(val pathFull: String) : WebAppEvent()
    data class OpenInvoice(val slug: String) : WebAppEvent()
    data class OpenScanQrPopup(val text: String?) : WebAppEvent()
    data object CloseScanQrPopup : WebAppEvent()
    data class SetupClosingBehavior(val needConfirmation: Boolean) : WebAppEvent()
    data class SetupSwipeBehavior(val allowVerticalSwipe: Boolean) : WebAppEvent()
    data class TriggerHapticFeedback(val type: String, val impactStyle: String?, val notificationType: String?) :
        WebAppEvent()

    data class StartAccelerometer(val refreshRate: Long) : WebAppEvent()
    data object StopAccelerometer : WebAppEvent()
    data class StartGyroscope(val refreshRate: Long) : WebAppEvent()
    data object StopGyroscope : WebAppEvent()
    data class StartDeviceOrientation(val refreshRate: Long, val needAbsolute: Boolean) : WebAppEvent()
    data object StopDeviceOrientation : WebAppEvent()
    data class ToggleOrientationLock(val locked: Boolean) : WebAppEvent()
    data object RequestFullscreen : WebAppEvent()
    data object ExitFullscreen : WebAppEvent()
    data class DataSend(val data: String) : WebAppEvent()
    data class SwitchInlineQuery(val query: String, val chatTypes: List<String>) : WebAppEvent()
    data class ReadTextFromClipboard(val reqId: String) : WebAppEvent()
    data object RequestWriteAccess : WebAppEvent()
    data object RequestPhone : WebAppEvent()
    data class InvokeCustomMethod(val reqId: String, val method: String, val params: String) : WebAppEvent()
    data class SendPreparedMessage(val id: String) : WebAppEvent()
    data class RequestFileDownload(val url: String, val fileName: String) : WebAppEvent()
    data class DeviceStorageSave(val reqId: String, val key: String, val value: String) : WebAppEvent()
    data class DeviceStorageGet(val reqId: String, val key: String) : WebAppEvent()
    data class DeviceStorageRemove(val reqId: String, val key: String) : WebAppEvent()
    data class SecureStorageSave(val reqId: String, val key: String, val value: String) : WebAppEvent()
    data class SecureStorageGet(val reqId: String, val key: String) : WebAppEvent()
    data class SecureStorageRemove(val reqId: String, val key: String) : WebAppEvent()
    data object BiometryGetInfo : WebAppEvent()
    data class BiometryRequestAccess(val reason: String?) : WebAppEvent()
    data class BiometryRequestAuth(val reason: String?) : WebAppEvent()
    data class BiometryUpdateToken(val token: String) : WebAppEvent()
    data object BiometryOpenSettings : WebAppEvent()
    data class ShareToStory(val mediaUrl: String, val text: String?, val widgetLink: String?) : WebAppEvent()
    data class SetEmojiStatus(val customEmojiId: Long, val duration: Int) : WebAppEvent()
    data object RequestEmojiStatusAccess : WebAppEvent()
    data object AddToHomeScreen : WebAppEvent()
    data object CheckHomeScreen : WebAppEvent()
    data object RequestLocation : WebAppEvent()
    data object CheckLocation : WebAppEvent()
    data object OpenLocationSettings : WebAppEvent()
    data class VerifyAge(val age: Double) : WebAppEvent()
    data object RequestSafeArea : WebAppEvent()
    data object RequestContentSafeArea : WebAppEvent()
    data object HideKeyboard : WebAppEvent()
}
