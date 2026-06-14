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
                val event = TelegramWebviewEventParser.parse(eventType, json)
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
                host.onOpenPopup(
                    event.title,
                    event.message,
                    event.buttons,
                    event.callbackId.orEmpty()
                )
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
            is WebAppEvent.DeviceStorageClear -> host.onDeviceStorageClear(event.reqId)
            is WebAppEvent.SecureStorageSave -> host.onSecureStorageSave(event.reqId, event.key, event.value)
            is WebAppEvent.SecureStorageGet -> host.onSecureStorageGet(event.reqId, event.key)
            is WebAppEvent.SecureStorageRemove -> host.onSecureStorageDelete(event.reqId, event.key)
            is WebAppEvent.SecureStorageRestoreKey -> host.onSecureStorageRestore(
                event.reqId,
                event.key
            )

            is WebAppEvent.SecureStorageClear -> host.onSecureStorageClear(event.reqId)
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
