package org.monogram.presentation.features.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.monogram.domain.models.webapp.OSMReverseResponse
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppPopupButton
import org.monogram.domain.repository.BotPreferencesProvider
import org.monogram.domain.repository.LocationRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.domain.repository.WebAppRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.core.util.toHex
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.coroutines.resume

@Stable
class MiniAppState(
    val context: Context,
    val botUserId: Long,
    val botName: String,
    val botAvatarPath: String?,
    val webAppRepository: WebAppRepository,
    val locationRepository: LocationRepository,
    val botPreferences: BotPreferencesProvider,
    val userRepository: UserRepository,
    initialThemeParams: ThemeParams,
    val onDismiss: () -> Unit
) {
    var themeParams by mutableStateOf(initialThemeParams)
        private set

    var webView: WebView? by mutableStateOf(null)
    var progress by mutableIntStateOf(0)
    var isLoading by mutableStateOf(true)

    var topBarColor by mutableStateOf(initialThemeParams.headerBackgroundColor?.let { Color(it.toColorInt()) })
    var topBarTextColor by mutableStateOf(initialThemeParams.textColor?.let { Color(it.toColorInt()) })
    var accentColor by mutableStateOf(initialThemeParams.accentTextColor?.let { Color(it.toColorInt()) })
    var backgroundColor by mutableStateOf(initialThemeParams.backgroundColor?.let { Color(it.toColorInt()) })
    var bottomBarColor by mutableStateOf(initialThemeParams.bottomBarBackgroundColor?.let { Color(it.toColorInt()) })

    var isExpanded by mutableStateOf(false)
    var isFullscreen by mutableStateOf(false)

    var mainButtonState by mutableStateOf(MainButtonState())
    var secondaryButtonState by mutableStateOf(SecondaryButtonState())
    var isBackButtonVisible by mutableStateOf(false)
    var isSettingsButtonVisible by mutableStateOf(false)
    var isClosingConfirmationEnabled by mutableStateOf(false)
    var showClosingConfirmation by mutableStateOf(false)

    var isInitializing by mutableStateOf(botUserId != 0L)
    var launchId by mutableLongStateOf(0L)
    var currentUrl by mutableStateOf("")

    var activeInvoiceSlug by mutableStateOf<String?>(null)
    var activePopup by mutableStateOf<PopupState?>(null)
    var activeQrText by mutableStateOf<String?>(null)
    var activeCustomMethod by mutableStateOf<CustomMethodRequest?>(null)

    var showMenu by mutableStateOf(false)

    var hasCameraPermission by mutableStateOf(
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    )

    var telegramProxy: TelegramWebviewProxy? by mutableStateOf(null)
    var showPermissionRequest by mutableStateOf<PermissionRequest?>(null)
    var onRequestSystemLocationPermission: (() -> Unit)? = null

    var isPermissionsVisible by mutableStateOf(false)
    var botPermissions by mutableStateOf<Map<String, Boolean>>(emptyMap())

    var isTOSVisible by mutableStateOf(!botPreferences.getWebappPermission(botUserId, "tos_accepted"))

    val biometricManager = BiometricManager.from(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var pendingRequestedContact: String? = null
    private var lastUserInteractionMs: Long = 0L

    fun markUserInteraction() {
        lastUserInteractionMs = System.currentTimeMillis()
    }

    fun updateThemeParams(newParams: ThemeParams) {
        val oldParams = themeParams
        themeParams = newParams

        if (topBarColor == oldParams.headerBackgroundColor?.let { Color(it.toColorInt()) }) {
            topBarColor = newParams.headerBackgroundColor?.let { Color(it.toColorInt()) }
        }
        if (topBarTextColor == oldParams.textColor?.let { Color(it.toColorInt()) }) {
            topBarTextColor = newParams.textColor?.let { Color(it.toColorInt()) }
        }
        if (accentColor == oldParams.accentTextColor?.let { Color(it.toColorInt()) }) {
            accentColor = newParams.accentTextColor?.let { Color(it.toColorInt()) }
        }
        if (backgroundColor == oldParams.backgroundColor?.let { Color(it.toColorInt()) }) {
            backgroundColor = newParams.backgroundColor?.let { Color(it.toColorInt()) }
        }
        if (bottomBarColor == oldParams.bottomBarBackgroundColor?.let { Color(it.toColorInt()) }) {
            bottomBarColor = newParams.bottomBarBackgroundColor?.let { Color(it.toColorInt()) }
        }
    }

    fun handleClose() {
        if (isClosingConfirmationEnabled) {
            showClosingConfirmation = true
        } else {
            if (launchId != 0L) {
                scope.launch {
                    webAppRepository.closeWebApp(launchId)
                }
            }
            onDismiss()
        }
    }

    private fun getLastKnownLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = try {
                locationManager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
            if (l != null && (bestLocation == null || l.accuracy < bestLocation.accuracy)) {
                bestLocation = l
            }
        }
        return bestLocation
    }

    private fun savePermission(permission: String, granted: Boolean) {
        botPreferences.setWebappPermission(botUserId, permission, granted)
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return botPreferences.getWebappPermission(botUserId, permission)
    }

    private fun isPermissionRequested(permission: String): Boolean {
        return botPreferences.isWebappPermissionRequested(botUserId, permission)
    }

    fun handleLocationRequest() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendLocationToWebview(null, "permission_denied")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            sendLocationToWebview(null, "disabled")
            return
        }

        scope.launch {
            val location = getCurrentLocation(locationManager)

            if (location != null) {
                val osmResponse = locationRepository.reverseGeocode(location.latitude, location.longitude)
                sendLocationToWebview(location, "success", osmResponse)
            } else {
                sendLocationToWebview(null, "timeout")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(locationManager: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null

            for (provider in providers) {
                if (provider == LocationManager.GPS_PROVIDER && !hasFine) continue
                if (provider == LocationManager.NETWORK_PROVIDER && !hasFine && !hasCoarse) continue

                val l = try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
                if (l != null && (bestLocation == null || l.time > bestLocation.time)) {
                    bestLocation = l
                }
            }

            if (bestLocation != null && System.currentTimeMillis() - bestLocation.time < 60000) {
                cont.resume(bestLocation)
                return@suspendCancellableCoroutine
            }

            val provider = if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else if ((hasFine || hasCoarse) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                null
            }

            if (provider == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) {
                        cont.resume(location)
                    }
                    locationManager.removeUpdates(this)
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }

            cont.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }
        }

    private fun sendLocationToWebview(location: Location?, status: String, osmData: OSMReverseResponse? = null) {
        val json = JSONObject()

        val isAvailable = location != null
        json.put("available", isAvailable)
        json.put("status", status)

        if (location != null) {
            json.put("latitude", location.latitude)
            json.put("longitude", location.longitude)

            if (location.hasAccuracy()) {
                json.put("horizontal_accuracy", location.accuracy.toDouble())
                json.put("accuracy", location.accuracy.toDouble())
            } else {
                json.put("horizontal_accuracy", JSONObject.NULL)
                json.put("accuracy", JSONObject.NULL)
            }

            if (location.hasAltitude()) {
                json.put("altitude", location.altitude)
            } else {
                json.put("altitude", JSONObject.NULL)
            }

            if (location.hasBearing()) {
                json.put("course", location.bearing.toDouble())
            } else {
                json.put("course", JSONObject.NULL)
            }

            if (location.hasSpeed()) {
                json.put("speed", location.speed.toDouble())
            } else {
                json.put("speed", JSONObject.NULL)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (location.hasVerticalAccuracy()) {
                    json.put("vertical_accuracy", location.verticalAccuracyMeters.toDouble())
                } else {
                    json.put("vertical_accuracy", JSONObject.NULL)
                }

                if (location.hasBearingAccuracy()) {
                    json.put("course_accuracy", location.bearingAccuracyDegrees.toDouble())
                } else {
                    json.put("course_accuracy", JSONObject.NULL)
                }

                if (location.hasSpeedAccuracy()) {
                    json.put("speed_accuracy", location.speedAccuracyMetersPerSecond.toDouble())
                } else {
                    json.put("speed_accuracy", JSONObject.NULL)
                }
            } else {
                json.put("vertical_accuracy", JSONObject.NULL)
                json.put("course_accuracy", JSONObject.NULL)
                json.put("speed_accuracy", JSONObject.NULL)
            }

            val addr = osmData?.address
            val city = addr?.city ?: addr?.town ?: addr?.village ?: addr?.suburb ?: addr?.county

            json.put("city", city ?: JSONObject.NULL)
            json.put("country", addr?.country ?: JSONObject.NULL)
            json.put("country_code", addr?.country_code ?: JSONObject.NULL)
        }

        telegramProxy?.dispatchToWebView("location_requested", json)
    }

    fun createHost(secureStorage: Any?) = object : TelegramWebAppHost {
        override fun onOpenLink(url: String, tryBrowser: Boolean, tryInstantView: Boolean) {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (tryInstantView) {
                    putExtra("android.support.customtabs.extra.EXTRA_ENABLE_INSTANT_APPS", true)
                }
                if (tryBrowser) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            coRunCatching { context.startActivity(intent) }
        }

        override fun onOpenTgLink(path: String) {
            val uri = if (path.startsWith("tg://")) path.toUri() else "tg://$path".toUri()
            coRunCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }

        override fun onOpenInvoice(slug: String) {
            activeInvoiceSlug = slug
        }

        override fun onClose(returnBack: Boolean) {
            handleClose()
        }

        override fun onExpand() {
            isExpanded = true
        }

        override fun onRequestFullscreen() {
            isExpanded = true
            isFullscreen = true
        }

        override fun onExitFullscreen() {
            isFullscreen = false
        }

        override fun onToggleOrientationLock(locked: Boolean) {
            (context as? Activity)?.requestedOrientation = if (locked) {
                ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        override fun onAddToHomeScreen() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                if (shortcutManager.isRequestPinShortcutSupported) {
                    val shortcutId = "webapp_$botUserId"
                    val alreadyAdded = shortcutManager.pinnedShortcuts.any { it.id == shortcutId }
                    if (alreadyAdded) {
                        telegramProxy?.dispatchToWebView("home_screen_added", JSONObject().put("status", "added"))
                        return
                    }

                    val intent = Intent(context, context.javaClass).apply {
                        action = Intent.ACTION_VIEW
                        data = "tg://resolve?domain=$botName&startapp=true".toUri()
                    }
                    val icon = if (botAvatarPath != null) {
                        val bitmap = BitmapFactory.decodeFile(botAvatarPath)
                        if (bitmap != null) Icon.createWithBitmap(bitmap) else Icon.createWithResource(
                            context,
                            R.drawable.outline_web_24
                        )
                    } else {
                        Icon.createWithResource(context, R.drawable.outline_web_24)
                    }
                    val pinShortcutInfo = ShortcutInfo.Builder(context, shortcutId)
                        .setShortLabel(botName)
                        .setIcon(icon)
                        .setIntent(intent)
                        .build()

                    shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                    telegramProxy?.dispatchToWebView("home_screen_added", JSONObject().put("status", "added"))
                } else {
                    telegramProxy?.dispatchToWebView("home_screen_added", JSONObject().put("status", "unsupported"))
                }
            } else {
                telegramProxy?.dispatchToWebView("home_screen_added", JSONObject().put("status", "unsupported"))
            }
        }

        override fun onCheckHomeScreen() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                val shortcutId = "webapp_$botUserId"
                val exists = shortcutManager.pinnedShortcuts.any { it.id == shortcutId }
                telegramProxy?.dispatchToWebView(
                    "home_screen_checked",
                    JSONObject().put("status", if (exists) "added" else "missed")
                )
            } else {
                telegramProxy?.dispatchToWebView("home_screen_checked", JSONObject().put("status", "unsupported"))
            }
        }

        override fun onShareToStory(mediaUrl: String, text: String?, widgetLink: JSONObject?) {}

        override fun onOpenPopup(
            title: String?,
            message: String,
            buttons: List<WebAppPopupButton>,
            callbackId: String
        ) {
            activePopup = PopupState(
                title,
                message,
                buttons.map { PopupButton(it.id, it.type ?: "default", it.text, it.isDestructive) },
                callbackId
            )
        }

        override fun onOpenScanQrPopup(text: String?) {
            activeQrText = text ?: ""
        }

        override fun onCloseScanQrPopup() {
            activeQrText = null
        }

        override fun onRequestPhone() {
            activeCustomMethod = CustomMethodRequest(
                reqId = "req_phone",
                method = "web_app_request_phone",
                params = "",
                title = "Share Contact",
                message = "Allow $botName to access your phone number?",
                onConfirm = {
                    scope.launch {
                        val me = userRepository.getMe()
                        val contactJson = JSONObject().apply {
                            put("phone_number", me.phoneNumber ?: "")
                            put("first_name", me.firstName)
                            put("user_id", me.id)
                        }.toString()
                        pendingRequestedContact = "contact=" + URLEncoder.encode(contactJson, "UTF-8")

                        telegramProxy?.dispatchToWebView(
                            "phone_requested",
                            JSONObject().put("status", "sent")
                        )
                        activeCustomMethod = null
                    }
                },
                onCancel = {
                    telegramProxy?.dispatchToWebView(
                        "phone_requested",
                        JSONObject().put("status", "cancelled")
                    )
                    activeCustomMethod = null
                }
            )
        }

        override fun onRequestWriteAccess() {
            activeCustomMethod = CustomMethodRequest(
                reqId = "req_write_access",
                method = "web_app_request_write_access",
                params = "",
                title = "Allow Messages",
                message = "Allow $botName to send you messages?",
                onConfirm = {
                    telegramProxy?.dispatchToWebView("write_access_requested", JSONObject().put("status", "allowed"))
                    activeCustomMethod = null
                },
                onCancel = {
                    telegramProxy?.dispatchToWebView("write_access_requested", JSONObject().put("status", "cancelled"))
                    activeCustomMethod = null
                }
            )
        }

        override fun onSetupMainButton(
            isVisible: Boolean,
            isActive: Boolean,
            text: String,
            color: Int?,
            textColor: Int?,
            isProgressVisible: Boolean,
            hasShineEffect: Boolean,
            iconCustomEmojiId: String?
        ) {
            mainButtonState = mainButtonState.copy(
                isVisible = isVisible,
                isActive = isActive,
                text = text,
                color = color?.let { Color(it) },
                textColor = textColor?.let { Color(it) },
                isProgressVisible = isProgressVisible,
                hasShineEffect = hasShineEffect,
                iconCustomEmojiId = iconCustomEmojiId
            )
        }

        override fun onSetupSecondaryButton(
            isVisible: Boolean,
            isActive: Boolean,
            text: String,
            color: Int?,
            textColor: Int?,
            isProgressVisible: Boolean,
            hasShineEffect: Boolean,
            position: String,
            iconCustomEmojiId: String?
        ) {
            secondaryButtonState = secondaryButtonState.copy(
                isVisible = isVisible,
                isActive = isActive,
                text = text,
                color = color?.let { Color(it) },
                textColor = textColor?.let { Color(it) },
                isProgressVisible = isProgressVisible,
                hasShineEffect = hasShineEffect,
                position = position,
                iconCustomEmojiId = iconCustomEmojiId
            )
        }

        override fun onSetBackButtonVisible(visible: Boolean) {
            isBackButtonVisible = visible
        }

        override fun onSetSettingsButtonVisible(visible: Boolean) {
            isSettingsButtonVisible = visible
        }

        override fun onSetBackgroundColor(color: Int) {
            backgroundColor = Color(color)
        }

        override fun onSetHeaderColor(colorKey: String?, customColor: Int?) {
            when (colorKey) {
                "secondary_bg_color" -> themeParams.secondaryBackgroundColor?.let {
                    topBarColor = Color(it.toColorInt())
                }

                "bg_color" -> themeParams.backgroundColor?.let { topBarColor = Color(it.toColorInt()) }
                else -> customColor?.let { topBarColor = Color(it) }
            }
        }

        override fun onResetHeaderColor() {
            topBarColor = themeParams.headerBackgroundColor?.let { Color(it.toColorInt()) }
        }

        override fun onSetBottomBarColor(color: Int) {
            bottomBarColor = Color(color)
        }

        override fun onResetBottomBarColor() {
            bottomBarColor = themeParams.bottomBarBackgroundColor?.let { Color(it.toColorInt()) }
        }

        override fun onSetupClosingBehavior(needConfirmation: Boolean) {
            isClosingConfirmationEnabled = needConfirmation
        }

        override fun onSetupSwipeBehavior(allowVertical: Boolean) {}

        override fun onSwitchInlineQuery(query: String, chatTypes: List<String>) {}

        override fun onSendWebViewData(data: String) {
            if (launchId != 0L) {
                scope.launch {
                    webAppRepository.sendWebAppResult(launchId, data)
                }
                onDismiss()
            }
        }

        override fun onReadClipboard(reqId: String) {
            val canReadClipboard = System.currentTimeMillis() - lastUserInteractionMs <= 10_000
            if (!canReadClipboard) {
                telegramProxy?.dispatchToWebView("clipboard_text_received", JSONObject().put("req_id", reqId))
                return
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                telegramProxy?.dispatchToWebView(
                    "clipboard_text_received",
                    JSONObject().put("req_id", reqId).put("data", text)
                )
            } else {
                telegramProxy?.dispatchToWebView("clipboard_text_received", JSONObject().put("req_id", reqId))
            }
        }

        override fun onInvokeCustomMethod(reqId: String, method: String, params: String) {
            val paramsJson = try {
                JSONObject(params)
            } catch (e: Exception) {
                JSONObject()
            }
            val storage = secureStorage as? MiniAppSecureStorage

            fun dispatchResult(result: Any?) {
                telegramProxy?.dispatchToWebView(
                    "custom_method_invoked",
                    JSONObject().put("req_id", reqId).put("result", result ?: JSONObject.NULL)
                )
            }

            fun dispatchError(error: String) {
                telegramProxy?.dispatchToWebView(
                    "custom_method_invoked",
                    JSONObject().put("req_id", reqId).put("error", error)
                )
            }

            fun getKeysList(json: JSONObject, key: String): List<String> {
                val arr = json.optJSONArray(key) ?: return emptyList()
                return List(arr.length()) { arr.getString(it) }
            }

            when (method) {
                "getRequestedContact" -> {
                    if (pendingRequestedContact != null) {
                        dispatchResult(pendingRequestedContact)
                        pendingRequestedContact = null
                    } else {
                        dispatchResult("")
                    }
                }

                // Device Storage
                "saveDeviceStorageValue" -> {
                    val key = paramsJson.optString("key")
                    val value = paramsJson.optString("value")
                    if (key.isNotEmpty()) {
                        botPreferences.saveWebappData(key, value)
                        dispatchResult(true)
                    } else {
                        dispatchError("INVALID_PARAMS")
                    }
                }

                "getDeviceStorageValue" -> dispatchResult(botPreferences.getWebappData(paramsJson.optString("key")))
                "getDeviceStorageValues" -> {
                    val keys = getKeysList(paramsJson, "keys")
                    dispatchResult(JSONObject(botPreferences.getWebappData(keys)))
                }

                "deleteDeviceStorageValue" -> {
                    botPreferences.deleteWebappData(paramsJson.optString("key"))
                    dispatchResult(true)
                }

                "deleteDeviceStorageValues" -> {
                    botPreferences.deleteWebappData(getKeysList(paramsJson, "keys"))
                    dispatchResult(true)
                }

                "getDeviceStorageKeys" -> {
                    val keys = botPreferences.getWebappDataKeys().filter { !it.startsWith("cloud_") }
                    dispatchResult(JSONArray(keys))
                }

                // Secure Storage
                "saveSecureStorageValue" -> {
                    val key = paramsJson.optString("key")
                    val value = paramsJson.optString("value")
                    if (key.isNotEmpty() && storage != null) {
                        storage.save(key, value)
                        dispatchResult(true)
                    } else {
                        dispatchError("UNAVAILABLE")
                    }
                }

                "getSecureStorageValue" -> dispatchResult(storage?.get(paramsJson.optString("key")))
                "getSecureStorageValues" -> {
                    val keys = getKeysList(paramsJson, "keys")
                    dispatchResult(storage?.get(keys)?.let { JSONObject(it) })
                }

                "deleteSecureStorageValue" -> {
                    storage?.delete(paramsJson.optString("key"))
                    dispatchResult(true)
                }

                "deleteSecureStorageValues" -> {
                    storage?.delete(getKeysList(paramsJson, "keys"))
                    dispatchResult(true)
                }

                "getSecureStorageKeys" -> dispatchResult(storage?.getKeys()?.let { JSONArray(it) })

                // Cloud Storage
                "saveStorageValue" -> {
                    val key = "cloud_${botUserId}_${paramsJson.optString("key")}"
                    botPreferences.saveWebappData(key, paramsJson.optString("value"))
                    dispatchResult(true)
                }

                "getStorageValue" -> dispatchResult(
                    botPreferences.getWebappData(
                        "cloud_${botUserId}_${
                            paramsJson.optString(
                                "key"
                            )
                        }"
                    )
                )

                "getStorageValues" -> {
                    val keys = getKeysList(paramsJson, "keys").map { "cloud_${botUserId}_$it" }
                    val data = botPreferences.getWebappData(keys).mapKeys { it.key.removePrefix("cloud_${botUserId}_") }
                    dispatchResult(JSONObject(data))
                }

                "deleteStorageValue" -> {
                    botPreferences.deleteWebappData("cloud_${botUserId}_${paramsJson.optString("key")}")
                    dispatchResult(true)
                }

                "deleteStorageValues" -> {
                    val keys = getKeysList(paramsJson, "keys").map { "cloud_${botUserId}_$it" }
                    botPreferences.deleteWebappData(keys)
                    dispatchResult(true)
                }

                "getStorageKeys" -> {
                    val prefix = "cloud_${botUserId}_"
                    val keys = botPreferences.getWebappDataKeys().filter { it.startsWith(prefix) }
                        .map { it.removePrefix(prefix) }
                    dispatchResult(JSONArray(keys))
                }

                else -> dispatchError("Method not implemented")
            }
        }

        override fun onSendPreparedMessage(id: String) {}

        override fun onFileDownloadRequested(url: String, fileName: String) {
            activeCustomMethod = CustomMethodRequest(
                reqId = "req_file_download",
                method = "web_app_request_file_download",
                params = "",
                title = "Download File",
                message = "Download ${if (fileName.isBlank()) "this file" else fileName}?",
                onConfirm = {
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    if (downloadManager == null) {
                        telegramProxy?.dispatchToWebView(
                            "file_download_requested",
                            JSONObject().put("status", "failed")
                        )
                        activeCustomMethod = null
                    } else {
                        val resolvedFileName = if (fileName.isBlank()) {
                            URLUtil.guessFileName(url, null, null)
                        } else {
                            fileName
                        }

                        coRunCatching {
                            val request = DownloadManager.Request(url.toUri())
                                .setTitle(resolvedFileName)
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, resolvedFileName)

                            downloadManager.enqueue(request)
                            telegramProxy?.dispatchToWebView(
                                "file_download_requested",
                                JSONObject().put("status", "downloading")
                            )
                        }.onFailure {
                            telegramProxy?.dispatchToWebView(
                                "file_download_requested",
                                JSONObject().put("status", "failed")
                            )
                        }
                    }

                    activeCustomMethod = null
                },
                onCancel = {
                    telegramProxy?.dispatchToWebView(
                        "file_download_requested",
                        JSONObject().put("status", "cancelled")
                    )
                    activeCustomMethod = null
                }
            )
        }

        override fun onOpenLocationSettings() {
            onShowPermissions()
        }

        override fun onSetEmojiStatus(customEmojiId: Long, duration: Int) {
            telegramProxy?.dispatchToWebView("emoji_status_set", JSONObject().put("status", "unsupported"))
        }

        override fun onRequestEmojiStatusAccess() {
            telegramProxy?.dispatchToWebView("emoji_status_access_requested", JSONObject().put("status", "unsupported"))
        }

        override fun onVerifyAge(age: Double) {}

        override fun onDeviceStorageSave(reqId: String, key: String, value: String) {
            if (key.isEmpty()) {
                telegramProxy?.dispatchToWebView(
                    "device_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "KEY_INVALID")
                )
                return
            }

            botPreferences.saveWebappData(key, value)
            telegramProxy?.dispatchToWebView("device_storage_key_saved", JSONObject().put("req_id", reqId))
        }

        override fun onDeviceStorageGet(reqId: String, key: String) {
            if (key.isEmpty()) {
                telegramProxy?.dispatchToWebView(
                    "device_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "KEY_INVALID")
                )
                return
            }

            val value = botPreferences.getWebappData(key)
            telegramProxy?.dispatchToWebView(
                "device_storage_key_received",
                JSONObject().put("req_id", reqId).put("value", value)
            )
        }

        override fun onDeviceStorageDelete(reqId: String, key: String) {
            if (key.isEmpty()) {
                telegramProxy?.dispatchToWebView(
                    "device_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "KEY_INVALID")
                )
                return
            }

            botPreferences.deleteWebappData(key)
            telegramProxy?.dispatchToWebView("device_storage_key_removed", JSONObject().put("req_id", reqId))
        }

        override fun onSecureStorageSave(reqId: String, key: String, value: String) {
            val storage = secureStorage as? MiniAppSecureStorage
            if (key.isEmpty()) {
                telegramProxy?.dispatchToWebView(
                    "secure_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "KEY_INVALID")
                )
                return
            }

            if (storage == null) {
                telegramProxy?.dispatchToWebView(
                    "secure_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "UNAVAILABLE")
                )
                return
            }

            storage.save(key, value)
            telegramProxy?.dispatchToWebView("secure_storage_key_saved", JSONObject().put("req_id", reqId))
        }

        override fun onSecureStorageGet(reqId: String, key: String) {
            val storage = secureStorage as? MiniAppSecureStorage
            if (key.isEmpty()) {
                telegramProxy?.dispatchToWebView(
                    "secure_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "KEY_INVALID")
                )
                return
            }

            if (storage == null) {
                telegramProxy?.dispatchToWebView(
                    "secure_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "UNAVAILABLE")
                )
                return
            }

            val value = storage.get(key)
            telegramProxy?.dispatchToWebView(
                "secure_storage_key_received",
                JSONObject().put("req_id", reqId).put("value", value)
            )
        }

        override fun onSecureStorageDelete(reqId: String, key: String) {
            val storage = secureStorage as? MiniAppSecureStorage
            if (key.isEmpty()) {
                telegramProxy?.dispatchToWebView(
                    "secure_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "KEY_INVALID")
                )
                return
            }

            if (storage == null) {
                telegramProxy?.dispatchToWebView(
                    "secure_storage_failed",
                    JSONObject().put("req_id", reqId).put("error", "UNAVAILABLE")
                )
                return
            }

            storage.delete(key)
            telegramProxy?.dispatchToWebView("secure_storage_key_removed", JSONObject().put("req_id", reqId))
        }

        override fun onBiometryGetInfo() {
            val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val isAvailable =
                canAuthenticate != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE && canAuthenticate != BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
            var deviceId = botPreferences.getWebappBiometryDeviceId(botUserId)
            if (deviceId == null) {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                deviceId = bytes.toHex()
                botPreferences.saveWebappBiometryDeviceId(botUserId, deviceId)
            }
            val info = JSONObject().apply {
                put("available", isAvailable)
                put("access_requested", botPreferences.isWebappBiometryAccessRequested())
                put("access_granted", isPermissionGranted("biometry"))
                put("device_can_authenticate", canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS)
                put("device_has_biometrics", isAvailable)
                put("type", "fingerprint")
                put("token_saved", (secureStorage as? MiniAppSecureStorage)?.get("biometry_token") != null)
                put("device_id", deviceId)
            }
            telegramProxy?.dispatchToWebView("biometry_info_received", info)
        }

        override fun onBiometryRequestAccess(reason: String?) {
            botPreferences.setWebappBiometryAccessRequested(true)
            savePermission("biometry", true)
            onBiometryGetInfo()
        }

        override fun onBiometryRequestAuth(reason: String?) {
            val fragmentActivity = context as? FragmentActivity ?: run {
                telegramProxy?.dispatchToWebView("biometry_auth_requested", JSONObject().put("status", "failed"))
                return
            }
            val executor = ContextCompat.getMainExecutor(context)
            val biometricPrompt =
                BiometricPrompt(fragmentActivity, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val token = (secureStorage as? MiniAppSecureStorage)?.get("biometry_token")
                        telegramProxy?.dispatchToWebView("biometry_auth_requested", JSONObject().apply {
                            put("status", "authorized")
                            if (token != null) put("token", token)
                        })
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        telegramProxy?.dispatchToWebView(
                            "biometry_auth_requested",
                            JSONObject().put("status", "failed")
                        )
                    }

                    override fun onAuthenticationFailed() {}
                })
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Biometric Authentication")
                .setSubtitle(reason ?: "Authenticate to continue")
                .setNegativeButtonText("Cancel")
                .build()
            biometricPrompt.authenticate(promptInfo)
        }

        override fun onBiometryUpdateToken(token: String) {
            (secureStorage as? MiniAppSecureStorage)?.save("biometry_token", token)
            telegramProxy?.dispatchToWebView("biometry_token_updated", JSONObject().put("status", "updated"))
        }

        override fun onBiometryOpenSettings() {
            onShowPermissions()
        }

        override fun onRequestLocation() {
            if (isPermissionGranted("location")) {
                checkSystemLocationAndHandle()
                return
            }
            showPermissionRequest = PermissionRequest(
                message = "Allow this bot to access your location?",
                onGranted = {
                    savePermission("location", true)
                    checkSystemLocationAndHandle()
                },
                onDenied = {
                    savePermission("location", false)
                    telegramProxy?.dispatchToWebView("location_requested", JSONObject().put("status", "cancelled"))
                },
                onDismiss = {
                    telegramProxy?.dispatchToWebView("location_requested", JSONObject().put("status", "cancelled"))
                }
            )
        }

        private fun checkSystemLocationAndHandle() {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                handleLocationRequest()
            } else {
                onRequestSystemLocationPermission?.invoke()
            }
        }

        override fun onCheckLocation() {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isSupported = locationManager.allProviders.isNotEmpty()

            val hasSystemPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
            val isInternalGranted = isPermissionGranted("location")
            val isRequested = isPermissionRequested("location")

            val json = JSONObject().apply {
                put("available", isSupported)
                put("access_requested", isRequested)
                put("access_granted", hasSystemPermission && isInternalGranted)
            }
            telegramProxy?.dispatchToWebView("location_checked", json)
        }
    }

    fun onShowPermissions() {
        val permissions = mapOf(
            "Location" to botPreferences.getWebappPermission(botUserId, "location"),
            "Biometry" to botPreferences.getWebappPermission(botUserId, "biometry"),
            "Terms of Service" to botPreferences.getWebappPermission(botUserId, "tos_accepted")
        )
        botPermissions = permissions
        isPermissionsVisible = true
    }

    fun onDismissPermissions() {
        isPermissionsVisible = false
    }

    fun onTogglePermission(permission: String) {
        val key = when (permission) {
            "Location" -> "location"
            "Biometry" -> "biometry"
            "Terms of Service" -> "tos_accepted"
            else -> return
        }
        val current = botPreferences.getWebappPermission(botUserId, key)
        botPreferences.setWebappPermission(botUserId, key, !current)

        if (key == "tos_accepted" && current) {
            isTOSVisible = true
            isPermissionsVisible = false
        } else {
            onShowPermissions()
        }
    }

    fun onAcceptTOS() {
        botPreferences.setWebappPermission(botUserId, "tos_accepted", true)
        isTOSVisible = false
    }
}

interface MiniAppSecureStorage {
    fun save(key: String, value: String)
    fun get(key: String): String?
    fun get(keys: List<String>): Map<String, String?>
    fun delete(key: String)
    fun delete(keys: List<String>)
    fun getKeys(): List<String>
}

@Composable
fun rememberMiniAppState(
    context: Context,
    botUserId: Long,
    botName: String,
    botAvatarPath: String?,
    webAppRepository: WebAppRepository,
    locationRepository: LocationRepository,
    botPreferences: BotPreferencesProvider,
    userRepository: UserRepository,
    themeParams: ThemeParams,
    onDismiss: () -> Unit
) = remember(botUserId) {
    MiniAppState(
        context,
        botUserId,
        botName,
        botAvatarPath,
        webAppRepository,
        locationRepository,
        botPreferences,
        userRepository,
        themeParams,
        onDismiss
    )
}
