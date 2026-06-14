package org.monogram.presentation.features.webapp

import org.json.JSONObject
import org.monogram.domain.models.webapp.WebAppEvent
import org.monogram.domain.models.webapp.WebAppPopupButton

internal object TelegramWebviewEventParser {
    fun parse(eventType: String, data: JSONObject): WebAppEvent? {
        return when (eventType) {
            "web_app_ready" -> WebAppEvent.Ready
            "web_app_close" -> WebAppEvent.Close(data.optBoolean("return_back", false))
            "web_app_expand" -> WebAppEvent.Expand
            "web_app_request_viewport" -> WebAppEvent.RequestViewport
            "web_app_request_theme" -> WebAppEvent.RequestTheme
            "web_app_set_background_color" -> WebAppEvent.SetBackgroundColor(
                data.optString(
                    "color",
                    "#ffffff"
                )
            )

            "web_app_set_header_color" -> WebAppEvent.SetHeaderColor(
                data.optString("color_key").takeIf { it.isNotEmpty() },
                data.optString("color").takeIf { it.isNotEmpty() }
            )

            "web_app_set_bottom_bar_color" -> WebAppEvent.SetBottomBarColor(data.optString("color"))
            "web_app_setup_main_button" -> WebAppEvent.SetupMainButton(
                data.optBoolean("is_visible"), data.optBoolean("is_active"),
                data.optString("text"), data.optString("color").takeIf { it.isNotEmpty() },
                data.optString("text_color").takeIf { it.isNotEmpty() },
                data.optBoolean("is_progress_visible"), data.optBoolean("has_shine_effect"),
                data.optString("icon_custom_emoji_id").takeIf { it.isNotEmpty() }
            )

            "web_app_setup_secondary_button" -> WebAppEvent.SetupSecondaryButton(
                data.optBoolean("is_visible"), data.optBoolean("is_active"),
                data.optString("text"), data.optString("color").takeIf { it.isNotEmpty() },
                data.optString("text_color").takeIf { it.isNotEmpty() },
                data.optBoolean("is_progress_visible"), data.optBoolean("has_shine_effect"),
                data.optString("position", "left"),
                data.optString("icon_custom_emoji_id").takeIf { it.isNotEmpty() }
            )

            "web_app_setup_back_button" -> WebAppEvent.SetupBackButton(data.optBoolean("is_visible"))
            "web_app_setup_settings_button" -> WebAppEvent.SetupSettingsButton(data.optBoolean("is_visible"))
            "web_app_open_popup" -> {
                val buttons = mutableListOf<WebAppPopupButton>()
                data.optJSONArray("buttons")?.let { arr ->
                    for (index in 0 until arr.length()) {
                        val button = arr.getJSONObject(index)
                        buttons += WebAppPopupButton(
                            id = button.getString("id"),
                            type = button.optString("type"),
                            text = button.getString("text"),
                            isDestructive = button.optBoolean("is_destructive")
                        )
                    }
                }
                WebAppEvent.OpenPopup(
                    title = data.optString("title").takeIf { it.isNotEmpty() },
                    message = data.getString("message"),
                    buttons = buttons,
                    callbackId = data.optString("callback_id").takeIf { it.isNotEmpty() }
                )
            }

            "web_app_open_link" -> WebAppEvent.OpenLink(
                data.optString("url"),
                data.optBoolean("try_browser"),
                data.optBoolean("try_instant_view")
            )

            "web_app_open_tg_link" -> WebAppEvent.OpenTgLink(data.optString("path_full"))
            "web_app_open_invoice" -> WebAppEvent.OpenInvoice(data.optString("slug"))
            "web_app_open_scan_qr_popup" -> WebAppEvent.OpenScanQrPopup(data.optString("text"))
            "web_app_close_scan_qr_popup" -> WebAppEvent.CloseScanQrPopup
            "web_app_setup_closing_behavior" -> WebAppEvent.SetupClosingBehavior(data.optBoolean("need_confirmation"))
            "web_app_setup_swipe_behavior" -> WebAppEvent.SetupSwipeBehavior(data.optBoolean("allow_vertical_swipe"))
            "web_app_trigger_haptic_feedback" -> WebAppEvent.TriggerHapticFeedback(
                data.optString("type"),
                data.optString("impact_style").takeIf { it.isNotEmpty() },
                data.optString("notification_type").takeIf { it.isNotEmpty() }
            )

            "web_app_start_accelerometer" -> WebAppEvent.StartAccelerometer(
                data.optLong(
                    "refresh_rate",
                    1000
                )
            )

            "web_app_stop_accelerometer" -> WebAppEvent.StopAccelerometer
            "web_app_start_gyroscope" -> WebAppEvent.StartGyroscope(
                data.optLong(
                    "refresh_rate",
                    1000
                )
            )

            "web_app_stop_gyroscope" -> WebAppEvent.StopGyroscope
            "web_app_start_device_orientation" -> WebAppEvent.StartDeviceOrientation(
                data.optLong("refresh_rate", 1000),
                data.optBoolean("need_absolute")
            )

            "web_app_stop_device_orientation" -> WebAppEvent.StopDeviceOrientation
            "web_app_toggle_orientation_lock" -> WebAppEvent.ToggleOrientationLock(data.optBoolean("locked"))
            "web_app_request_fullscreen" -> WebAppEvent.RequestFullscreen
            "web_app_exit_fullscreen" -> WebAppEvent.ExitFullscreen
            "web_app_data_send" -> WebAppEvent.DataSend(data.optString("data"))
            "web_app_switch_inline_query" -> {
                val types = mutableListOf<String>()
                data.optJSONArray("chat_types")?.let { arr ->
                    for (index in 0 until arr.length()) {
                        types += arr.getString(index)
                    }
                }
                WebAppEvent.SwitchInlineQuery(data.optString("query"), types)
            }

            "web_app_read_text_from_clipboard" -> WebAppEvent.ReadTextFromClipboard(data.getString("req_id"))
            "web_app_request_write_access" -> WebAppEvent.RequestWriteAccess
            "web_app_request_phone" -> WebAppEvent.RequestPhone
            "web_app_invoke_custom_method" -> WebAppEvent.InvokeCustomMethod(
                data.getString("req_id"),
                data.getString("method"),
                data.optJSONObject("params")?.toString() ?: "{}"
            )

            "web_app_send_prepared_message" -> WebAppEvent.SendPreparedMessage(data.optString("id"))
            "web_app_request_file_download" -> WebAppEvent.RequestFileDownload(
                data.optString("url"),
                data.optString("file_name")
            )

            "web_app_device_storage_save_key" -> WebAppEvent.DeviceStorageSave(
                data.getString("req_id"),
                data.getString("key"),
                data.getString("value")
            )

            "web_app_device_storage_get_key" -> WebAppEvent.DeviceStorageGet(
                data.getString("req_id"),
                data.getString("key")
            )

            "web_app_device_storage_remove_key" -> WebAppEvent.DeviceStorageRemove(
                data.getString("req_id"),
                data.getString("key")
            )

            "web_app_device_storage_clear" -> WebAppEvent.DeviceStorageClear(data.getString("req_id"))
            "web_app_secure_storage_save_key" -> WebAppEvent.SecureStorageSave(
                data.getString("req_id"),
                data.getString("key"),
                data.getString("value")
            )

            "web_app_secure_storage_get_key" -> WebAppEvent.SecureStorageGet(
                data.getString("req_id"),
                data.getString("key")
            )

            "web_app_secure_storage_remove_key" -> WebAppEvent.SecureStorageRemove(
                data.getString("req_id"),
                data.getString("key")
            )

            "web_app_secure_storage_restore_key" -> WebAppEvent.SecureStorageRestoreKey(
                data.getString("req_id"),
                data.getString("key")
            )

            "web_app_secure_storage_clear" -> WebAppEvent.SecureStorageClear(data.getString("req_id"))
            "web_app_biometry_get_info" -> WebAppEvent.BiometryGetInfo
            "web_app_biometry_request_access" -> WebAppEvent.BiometryRequestAccess(data.optString("reason"))
            "web_app_biometry_request_auth" -> WebAppEvent.BiometryRequestAuth(data.optString("reason"))
            "web_app_biometry_update_token" -> WebAppEvent.BiometryUpdateToken(data.getString("token"))
            "web_app_biometry_open_settings" -> WebAppEvent.BiometryOpenSettings
            "web_app_share_to_story" -> WebAppEvent.ShareToStory(
                data.getString("media_url"),
                data.optString("text"),
                data.optJSONObject("widget_link")?.toString()
            )

            "web_app_set_emoji_status" -> WebAppEvent.SetEmojiStatus(
                data.optLong("custom_emoji_id"),
                data.optInt("duration")
            )

            "web_app_request_emoji_status_access" -> WebAppEvent.RequestEmojiStatusAccess
            "web_app_add_to_home_screen" -> WebAppEvent.AddToHomeScreen
            "web_app_check_home_screen" -> WebAppEvent.CheckHomeScreen
            "web_app_request_location" -> WebAppEvent.RequestLocation
            "web_app_check_location" -> WebAppEvent.CheckLocation
            "web_app_open_location_settings" -> WebAppEvent.OpenLocationSettings
            "web_app_verify_age" -> WebAppEvent.VerifyAge(data.optDouble("age"))
            "web_app_request_safe_area" -> WebAppEvent.RequestSafeArea
            "web_app_request_content_safe_area" -> WebAppEvent.RequestContentSafeArea
            "web_app_hide_keyboard" -> WebAppEvent.HideKeyboard
            else -> null
        }
    }
}
