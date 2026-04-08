package org.monogram.presentation.features.webapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppEvent
import org.monogram.domain.models.webapp.WebAppPopupButton
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MiniAppLog"

class TelegramWebviewProxy(
    private val context: Context,
    private val webView: WebView,
    private var themeParams: ThemeParams,
    private val host: TelegramWebAppHost
) {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activeSensors = ConcurrentHashMap<Int, SensorEventListener>()

    private var lastSafeArea = JSONObject()
    private var lastContentSafeArea = JSONObject()

    init {
        webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateViewport()
        }
    }

    fun setThemeParams(newParams: ThemeParams) {
        if (this.themeParams == newParams) return
        this.themeParams = newParams
        dispatchToWebView(
            "theme_changed",
            JSONObject().put("theme_params", JSONObject(newParams.toJson()))
        )
        injectCSSVars(newParams.toJson())
    }

    fun updateSafeAreas(safeArea: JSONObject, contentSafeArea: JSONObject) {
        if (lastSafeArea.toString() == safeArea.toString() &&
            lastContentSafeArea.toString() == contentSafeArea.toString()
        ) return

        lastSafeArea = safeArea
        lastContentSafeArea = contentSafeArea

        dispatchToWebView("safe_area_changed", safeArea)
        dispatchToWebView("content_safe_area_changed", contentSafeArea)

        injectSafeAreaCSS(safeArea, contentSafeArea)
    }

    private fun injectSafeAreaCSS(safeArea: JSONObject, contentSafeArea: JSONObject) {
        val script = """
            document.documentElement.style.setProperty('--tg-safe-area-inset-top', '${safeArea.optInt("top")}px');
            document.documentElement.style.setProperty('--tg-safe-area-inset-bottom', '${safeArea.optInt("bottom")}px');
            document.documentElement.style.setProperty('--tg-safe-area-inset-left', '${safeArea.optInt("left")}px');
            document.documentElement.style.setProperty('--tg-safe-area-inset-right', '${safeArea.optInt("right")}px');
            document.documentElement.style.setProperty('--tg-content-safe-area-inset-top', '${contentSafeArea.optInt("top")}px');
            document.documentElement.style.setProperty('--tg-content-safe-area-inset-bottom', '${
            contentSafeArea.optInt(
                "bottom"
            )
        }px');
            document.documentElement.style.setProperty('--tg-content-safe-area-inset-left', '${contentSafeArea.optInt("left")}px');
            document.documentElement.style.setProperty('--tg-content-safe-area-inset-right', '${contentSafeArea.optInt("right")}px');
        """.trimIndent()

        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating JS for injecting safe area CSS", e)
            }
        }
    }

    @JavascriptInterface
    fun postEvent(eventType: String, eventData: String?) {
        Log.d(TAG, "postEvent: $eventType | Data: $eventData")
        webView.post {
            try {
                val json = safeJson(eventData)
                val event = parseEvent(eventType, json)
                if (event != null) {
                    handleEvent(event)
                } else {
                    Log.w(TAG, "Unhandled event: $eventType")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling $eventType", e)
            }
        }
    }

    private fun parseEvent(eventType: String, data: JSONObject): WebAppEvent? {
        return when (eventType) {
            "web_app_ready" -> WebAppEvent.Ready
            "web_app_close" -> WebAppEvent.Close(data.optBoolean("return_back", false))
            "web_app_expand" -> WebAppEvent.Expand
            "web_app_request_viewport" -> WebAppEvent.RequestViewport
            "web_app_request_theme" -> WebAppEvent.RequestTheme
            "web_app_set_background_color" -> WebAppEvent.SetBackgroundColor(data.optString("color", "#ffffff"))
            "web_app_set_header_color" -> WebAppEvent.SetHeaderColor(
                data.optString("color_key").takeIf { it.isNotEmpty() },
                data.optString("color").takeIf { it.isNotEmpty() })

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
                    for (i in 0 until arr.length()) {
                        val b = arr.getJSONObject(i)
                        buttons.add(
                            WebAppPopupButton(
                                b.getString("id"),
                                b.optString("type"),
                                b.getString("text"),
                                b.optBoolean("is_destructive")
                            )
                        )
                    }
                }
                WebAppEvent.OpenPopup(
                    data.optString("title").takeIf { it.isNotEmpty() },
                    data.getString("message"),
                    buttons
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
                data.optString("notification_type").takeIf { it.isNotEmpty() })

            "web_app_start_accelerometer" -> WebAppEvent.StartAccelerometer(data.optLong("refresh_rate", 1000))
            "web_app_stop_accelerometer" -> WebAppEvent.StopAccelerometer
            "web_app_start_gyroscope" -> WebAppEvent.StartGyroscope(data.optLong("refresh_rate", 1000))
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
                    for (i in 0 until arr.length()) types.add(arr.getString(i))
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

    private fun handleEvent(event: WebAppEvent) {
        when (event) {
            is WebAppEvent.Ready -> {
                dispatchToWebView("theme_changed", JSONObject().put("theme_params", JSONObject(themeParams.toJson())))
                updateViewport()
                injectCSSVars(themeParams.toJson())
                dispatchToWebView("safe_area_changed", lastSafeArea)
                dispatchToWebView("content_safe_area_changed", lastContentSafeArea)
                injectSafeAreaCSS(lastSafeArea, lastContentSafeArea)
            }

            is WebAppEvent.Close -> {
                host.onClose(event.returnBack)
                host.onResetHeaderColor()
                host.onResetBottomBarColor()
            }

            is WebAppEvent.Expand -> {
                updateViewport()
                host.onExpand()
            }

            is WebAppEvent.RequestViewport -> updateViewport()
            is WebAppEvent.RequestTheme -> dispatchToWebView(
                "theme_changed",
                JSONObject().put("theme_params", JSONObject(themeParams.toJson()))
            )

            is WebAppEvent.SetBackgroundColor -> host.onSetBackgroundColor(parseColor(event.color))
            is WebAppEvent.SetHeaderColor -> host.onSetHeaderColor(event.colorKey, event.color?.let { parseColor(it) })
            is WebAppEvent.SetBottomBarColor -> host.onSetBottomBarColor(parseColor(event.color))
            is WebAppEvent.SetupMainButton -> host.onSetupMainButton(
                event.isVisible,
                event.isActive,
                event.text,
                event.color?.let { parseColor(it) },
                event.textColor?.let { parseColor(it) },
                event.isProgressVisible,
                event.hasShineEffect,
                event.iconCustomEmojiId
            )

            is WebAppEvent.SetupSecondaryButton -> host.onSetupSecondaryButton(
                event.isVisible,
                event.isActive,
                event.text,
                event.color?.let { parseColor(it) },
                event.textColor?.let { parseColor(it) },
                event.isProgressVisible,
                event.hasShineEffect,
                event.position,
                event.iconCustomEmojiId
            )

            is WebAppEvent.SetupBackButton -> host.onSetBackButtonVisible(event.isVisible)
            is WebAppEvent.SetupSettingsButton -> host.onSetSettingsButtonVisible(event.isVisible)
            is WebAppEvent.OpenPopup -> {
                host.onOpenPopup(event.title, event.message, event.buttons, "")
            }

            is WebAppEvent.OpenLink -> host.onOpenLink(event.url, event.tryBrowser, event.tryInstantView)
            is WebAppEvent.OpenTgLink -> host.onOpenTgLink(event.pathFull)
            is WebAppEvent.OpenInvoice -> host.onOpenInvoice(event.slug)
            is WebAppEvent.OpenScanQrPopup -> host.onOpenScanQrPopup(event.text)
            is WebAppEvent.CloseScanQrPopup -> host.onCloseScanQrPopup()
            is WebAppEvent.SetupClosingBehavior -> host.onSetupClosingBehavior(event.needConfirmation)
            is WebAppEvent.SetupSwipeBehavior -> host.onSetupSwipeBehavior(event.allowVerticalSwipe)
            is WebAppEvent.TriggerHapticFeedback -> handleHaptics(event)
            is WebAppEvent.StartAccelerometer -> startSensor(
                Sensor.TYPE_ACCELEROMETER,
                event.refreshRate,
                "accelerometer"
            )

            is WebAppEvent.StopAccelerometer -> stopSensors("accelerometer", Sensor.TYPE_ACCELEROMETER)
            is WebAppEvent.StartGyroscope -> startSensor(Sensor.TYPE_GYROSCOPE, event.refreshRate, "gyroscope")
            is WebAppEvent.StopGyroscope -> stopSensors("gyroscope", Sensor.TYPE_GYROSCOPE)
            is WebAppEvent.StartDeviceOrientation -> startDeviceOrientation(event.refreshRate, event.needAbsolute)
            is WebAppEvent.StopDeviceOrientation -> stopSensors(
                "device_orientation",
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                Sensor.TYPE_ROTATION_VECTOR
            )

            is WebAppEvent.ToggleOrientationLock -> host.onToggleOrientationLock(event.locked)
            is WebAppEvent.RequestFullscreen -> {
                host.onRequestFullscreen()
                dispatchToWebView("fullscreen_changed", JSONObject().put("is_fullscreen", true))
            }

            is WebAppEvent.ExitFullscreen -> {
                host.onExitFullscreen()
                dispatchToWebView("fullscreen_changed", JSONObject().put("is_fullscreen", false))
            }

            is WebAppEvent.DataSend -> host.onSendWebViewData(event.data)
            is WebAppEvent.SwitchInlineQuery -> host.onSwitchInlineQuery(event.query, event.chatTypes)
            is WebAppEvent.ReadTextFromClipboard -> host.onReadClipboard(event.reqId)
            is WebAppEvent.RequestWriteAccess -> host.onRequestWriteAccess()
            is WebAppEvent.RequestPhone -> host.onRequestPhone()
            is WebAppEvent.InvokeCustomMethod -> host.onInvokeCustomMethod(event.reqId, event.method, event.params)
            is WebAppEvent.SendPreparedMessage -> host.onSendPreparedMessage(event.id)
            is WebAppEvent.RequestFileDownload -> host.onFileDownloadRequested(event.url, event.fileName)
            is WebAppEvent.DeviceStorageSave -> host.onDeviceStorageSave(event.reqId, event.key, event.value)
            is WebAppEvent.DeviceStorageGet -> host.onDeviceStorageGet(event.reqId, event.key)
            is WebAppEvent.DeviceStorageRemove -> host.onDeviceStorageDelete(event.reqId, event.key)
            is WebAppEvent.SecureStorageSave -> host.onSecureStorageSave(event.reqId, event.key, event.value)
            is WebAppEvent.SecureStorageGet -> host.onSecureStorageGet(event.reqId, event.key)
            is WebAppEvent.SecureStorageRemove -> host.onSecureStorageDelete(event.reqId, event.key)
            is WebAppEvent.BiometryGetInfo -> host.onBiometryGetInfo()
            is WebAppEvent.BiometryRequestAccess -> host.onBiometryRequestAccess(event.reason)
            is WebAppEvent.BiometryRequestAuth -> host.onBiometryRequestAuth(event.reason)
            is WebAppEvent.BiometryUpdateToken -> host.onBiometryUpdateToken(event.token)
            is WebAppEvent.BiometryOpenSettings -> host.onBiometryOpenSettings()
            is WebAppEvent.ShareToStory -> host.onShareToStory(
                event.mediaUrl,
                event.text,
                event.widgetLink?.let { JSONObject(it) })

            is WebAppEvent.SetEmojiStatus -> host.onSetEmojiStatus(event.customEmojiId, event.duration)
            is WebAppEvent.RequestEmojiStatusAccess -> host.onRequestEmojiStatusAccess()
            is WebAppEvent.AddToHomeScreen -> host.onAddToHomeScreen()
            is WebAppEvent.CheckHomeScreen -> host.onCheckHomeScreen()
            is WebAppEvent.RequestLocation -> host.onRequestLocation()
            is WebAppEvent.CheckLocation -> host.onCheckLocation()
            is WebAppEvent.OpenLocationSettings -> host.onOpenLocationSettings()
            is WebAppEvent.VerifyAge -> host.onVerifyAge(event.age)
            is WebAppEvent.RequestSafeArea -> dispatchToWebView("safe_area_changed", lastSafeArea)
            is WebAppEvent.RequestContentSafeArea -> dispatchToWebView("content_safe_area_changed", lastContentSafeArea)
            is WebAppEvent.HideKeyboard -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(webView.windowToken, 0)
            }
        }
    }

    fun dispatchToWebView(eventType: String, eventData: JSONObject?) {
        Log.d(TAG, "dispatchToWebView: $eventType | Data: $eventData")

        val data = eventData?.toString()
        val quotedEvent = JSONObject.quote(eventType)

        val script = """
        if (window.Telegram?.WebView?.receiveEvent) {
            window.Telegram.WebView.receiveEvent($quotedEvent, $data);
        }
    """.trimIndent()

        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating JS for event $eventType", e)
            }
        }
    }

    private fun updateViewport() {
        val density = context.resources.displayMetrics.density
        val height = (webView.height / density).toInt()
        val data = JSONObject().apply {
            put("height", height)
            put("is_state_stable", true)
            put("is_expanded", true)
        }
        dispatchToWebView("viewport_changed", data)

        val script = """
            document.documentElement.style.setProperty('--tg-viewport-height', '${height}px');
            document.documentElement.style.setProperty('--tg-viewport-stable-height', '${height}px');
        """.trimIndent()
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating JS for updating viewport", e)
            }
        }
    }

    private fun injectCSSVars(themeParamsJson: String) {
        try {
            val json = JSONObject(themeParamsJson)
            val sb = StringBuilder("(function() {")
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)
                if (value.isNotEmpty() && value != "null") {
                    val cssKey = key.replace("_", "-")
                    sb.append("document.documentElement.style.setProperty('--tg-theme-$cssKey', '$value');")
                }
            }
            sb.append("})();")
            webView.post {
                try {
                    webView.evaluateJavascript(sb.toString(), null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error evaluating JS for injecting CSS vars", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CSS Inject Error", e)
        }
    }

    private fun getSensorDelay(refreshRate: Long): Int {
        if (refreshRate >= 160) return SensorManager.SENSOR_DELAY_NORMAL
        if (refreshRate >= 60) return SensorManager.SENSOR_DELAY_UI
        return SensorManager.SENSOR_DELAY_GAME
    }

    private fun startSensor(type: Int, refreshMs: Long, eventName: String) {
        val clampedRefreshMs = refreshMs.coerceIn(20, 1000)

        stopSensors(eventName, type)

        val listener = object : SensorEventListener {
            private var lastUpdateTimestamp = 0L

            override fun onSensorChanged(event: SensorEvent) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastUpdateTimestamp < clampedRefreshMs) {
                    return
                }

                val params = JSONObject()
                when (type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        params.put("x", -event.values[0])
                        params.put("y", -event.values[1])
                        params.put("z", -event.values[2])
                    }

                    Sensor.TYPE_GYROSCOPE -> {
                        params.put("x", event.values[0])
                        params.put("y", event.values[1])
                        params.put("z", event.values[2])
                    }
                }
                dispatchToWebView("${eventName}_changed", params)

                lastUpdateTimestamp = currentTime

            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val sensor = sensorManager.getDefaultSensor(type)
        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, getSensorDelay(clampedRefreshMs))
            activeSensors[type] = listener
            dispatchToWebView("${eventName}_started", null)
        } else {
            dispatchToWebView("${eventName}_failed", JSONObject().put("error", "UNSUPPORTED"))
        }
    }

    private fun startDeviceOrientation(refreshMs: Long, needAbsolute: Boolean) {
        val clampedRefreshMs = refreshMs.coerceIn(20, 1000)

        val sensorTypes = if (needAbsolute) {
            if (sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) {
                intArrayOf(Sensor.TYPE_ROTATION_VECTOR)
            } else {
                intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD)
            }
        } else {
            intArrayOf(Sensor.TYPE_GAME_ROTATION_VECTOR)
        }

        stopSensors(
            "device_orientation",
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_MAGNETIC_FIELD
        )

        val listener = object : SensorEventListener {
            private var lastUpdateTimestamp = 0L

            private val rotationMatrix = FloatArray(9)
            private val inclinationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)
            private val truncatedVector = FloatArray(4)

            private var gravityValues: FloatArray? = null
            private var magneticValues: FloatArray? = null

            override fun onSensorChanged(event: SensorEvent) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUpdateTimestamp < clampedRefreshMs) return

                val success = when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        val values = event.values
                        // Samsung/device-specific safety check for rotation vector length
                        if (values.size > 4) {
                            values.copyInto(truncatedVector, 0, 0, 4)
                            SensorManager.getRotationMatrixFromVector(
                                rotationMatrix,
                                truncatedVector
                            )
                        } else {
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                        }
                        true
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        gravityValues = event.values.clone()
                        tryComputeMatrix()
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magneticValues = event.values.clone()
                        tryComputeMatrix()
                    }

                    else -> false
                }

                if (success) {
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    lastUpdateTimestamp = currentTime

                    val params = JSONObject().apply {
                        put("absolute", needAbsolute)
                        put("alpha", -orientation[0].toDouble())
                        put("beta", -orientation[1].toDouble())
                        put("gamma", orientation[2].toDouble())
                    }
                    dispatchToWebView("device_orientation_changed", params)
                }
            }

            private fun tryComputeMatrix(): Boolean {
                val g = gravityValues ?: return false
                val m = magneticValues ?: return false
                return SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, g, m)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        var startedCount = 0
        sensorTypes.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                if (sensorManager.registerListener(
                        listener,
                        sensor,
                        getSensorDelay(clampedRefreshMs)
                    )
                ) {
                    activeSensors[type] = listener
                    startedCount++
                }
            }
        }

        if (startedCount > 0) {
            dispatchToWebView("device_orientation_started", null)
        } else {
            dispatchToWebView("device_orientation_failed", JSONObject().put("error", "UNSUPPORTED"))
        }
    }

    private fun stopSensors(eventName: String, vararg types: Int) {
        var shouldSendStoppedEvent = false

        types.forEach { type ->
            activeSensors.remove(type)?.let {
                sensorManager.unregisterListener(it)
                shouldSendStoppedEvent = true
            }
        }

        if (shouldSendStoppedEvent) {
            dispatchToWebView("${eventName}_stopped", null)
        }
    }

    fun destroy() {
        activeSensors.values.forEach { sensorManager.unregisterListener(it) }
        activeSensors.clear()
    }

    private fun handleHaptics(event: WebAppEvent.TriggerHapticFeedback) {
        val effect: VibrationEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (event.type) {
                "impact" -> {
                    when (event.impactStyle) {
                        "light" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        "medium" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        "heavy" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                        "rigid" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                        "soft" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    }
                }

                "notification" -> {
                    when (event.notificationType) {
                        "error" -> VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
                        "success" -> VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
                        "warning" -> VibrationEffect.createWaveform(longArrayOf(0, 50, 100, 50), -1)
                        else -> null
                    }
                }
                "selection_change" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                else -> null
            }
        } else {
            null
        }

        if (effect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    private fun safeJson(jsonStr: String?): JSONObject {
        if (jsonStr.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun parseColor(color: String): Int = color.toColorInt() or -0x1000000

    private fun ThemeParams.toJson(): String =
        JSONObject().apply {
            colorScheme?.let { put("color_scheme", it) }
            put("bg_color", backgroundColor)
            put("secondary_bg_color", secondaryBackgroundColor)
            put("header_bg_color", headerBackgroundColor)
            put("bottom_bar_bg_color", bottomBarBackgroundColor)
            put("section_bg_color", sectionBackgroundColor)
            put("section_separator_color", sectionSeparatorColor)
            put("text_color", textColor)
            put("accent_text_color", accentTextColor)
            put("section_header_text_color", sectionHeaderTextColor)
            put("subtitle_text_color", subtitleTextColor)
            put("destructive_text_color", destructiveTextColor)
            put("hint_color", hintColor)
            put("link_color", linkColor)
            put("button_color", buttonColor)
            put("button_text_color", buttonTextColor)
        }.toString()
}
