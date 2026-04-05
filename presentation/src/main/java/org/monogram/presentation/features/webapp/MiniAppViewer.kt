package org.monogram.presentation.features.webapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.compose.koinInject
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.CryptoManager
import org.monogram.presentation.features.webapp.components.*
import java.util.*
import kotlin.math.max

private const val TAG = "MiniAppLog"

@Composable
fun MiniAppViewer(
    chatId: Long,
    botUserId: Long,
    baseUrl: String,
    botName: String,
    botAvatarPath: String? = null,
    webAppRepository: WebAppRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboardManager = LocalClipboard.current
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val locationRepository: LocationRepository = koinInject()
    val botPreferences: BotPreferencesProvider = koinInject()
    val userRepository: UserRepository = koinInject()
    val paymentRepository: PaymentRepository = koinInject()
    val fileRepository: FileRepository = koinInject()
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val currentUser by userRepository.currentUserFlow.collectAsState()
    val webAppLanguage = remember(currentUser?.languageCode) {
        currentUser?.languageCode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().toLanguageTag()
    }

    val themeParams = remember(colorScheme, isDark) {
        ThemeParams(
            colorScheme = if (isDark) "dark" else "light",
            backgroundColor = colorScheme.surface.toArgb().toHex(),
            secondaryBackgroundColor = colorScheme.surfaceVariant.toArgb().toHex(),
            headerBackgroundColor = colorScheme.surface.toArgb().toHex(),
            bottomBarBackgroundColor = colorScheme.surface.toArgb().toHex(),
            sectionBackgroundColor = colorScheme.surface.toArgb().toHex(),
            sectionSeparatorColor = colorScheme.outlineVariant.toArgb().toHex(),
            textColor = colorScheme.onSurface.toArgb().toHex(),
            accentTextColor = colorScheme.primary.toArgb().toHex(),
            sectionHeaderTextColor = colorScheme.primary.toArgb().toHex(),
            subtitleTextColor = colorScheme.onSurfaceVariant.toArgb().toHex(),
            destructiveTextColor = colorScheme.error.toArgb().toHex(),
            hintColor = colorScheme.onSurfaceVariant.toArgb().toHex(),
            linkColor = colorScheme.primary.toArgb().toHex(),
            buttonColor = colorScheme.primary.toArgb().toHex(),
            buttonTextColor = colorScheme.onPrimary.toArgb().toHex(),
        )
    }

    val state = rememberMiniAppState(
        context = context,
        botUserId = botUserId,
        botName = botName,
        botAvatarPath = botAvatarPath,
        webAppRepository = webAppRepository,
        locationRepository = locationRepository,
        botPreferences = botPreferences,
        userRepository = userRepository,
        themeParams = themeParams,
        onDismiss = onDismiss
    )

    LaunchedEffect(themeParams) {
        state.updateThemeParams(themeParams)
        state.telegramProxy?.setThemeParams(themeParams)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (isGranted) {
            state.handleLocationRequest()
        } else {
            state.telegramProxy?.dispatchToWebView("location_requested", JSONObject().put("status", "failed"))
        }
    }

    LaunchedEffect(state) {
        state.onRequestSystemLocationPermission = {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val secureStorage = remember(context) {
        try {
            val sharedPrefs = context.getSharedPreferences("webapp_secure_storage", Context.MODE_PRIVATE)
            val cryptoManager = CryptoManager()
            object : MiniAppSecureStorage {
                override fun save(key: String, value: String) {
                    cryptoManager.encrypt(value)?.let { sharedPrefs.edit().putString(key, it).apply() }
                }

                override fun get(key: String): String? =
                    sharedPrefs.getString(key, null)?.let { cryptoManager.decrypt(it) }

                override fun get(keys: List<String>): Map<String, String?> {
                    return keys.associateWith { key ->
                        sharedPrefs.getString(key, null)?.let { cryptoManager.decrypt(it) }
                    }
                }

                override fun delete(key: String) {
                    sharedPrefs.edit().remove(key).apply()
                }

                override fun delete(keys: List<String>) {
                    val editor = sharedPrefs.edit()
                    keys.forEach { editor.remove(it) }
                    editor.apply()
                }

                override fun getKeys(): List<String> {
                    return sharedPrefs.all.keys.toList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init secure storage", e)
            null
        }
    }

    val window = activity?.window
    if (window != null) {
        SideEffect {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            insetsController.isAppearanceLightStatusBars =
                (state.topBarColor ?: colorScheme.surface).toArgb() > Color.DarkGray.toArgb()
            insetsController.isAppearanceLightNavigationBars =
                (state.bottomBarColor ?: colorScheme.surface).toArgb() > Color.DarkGray.toArgb()
        }
    }

    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val displayCutout = WindowInsets.displayCutout

    LaunchedEffect(state.isFullscreen) {
        snapshotFlow {
            val topPx = if (state.isFullscreen) max(statusBars.getTop(density), displayCutout.getTop(density)) else 0
            val bottomPx =
                if (state.isFullscreen) max(navigationBars.getBottom(density), displayCutout.getBottom(density)) else 0
            val leftPx = if (state.isFullscreen) max(
                statusBars.getLeft(density, LayoutDirection.Ltr),
                displayCutout.getLeft(density, LayoutDirection.Ltr)
            ) else 0
            val rightPx = if (state.isFullscreen) max(
                statusBars.getRight(density, LayoutDirection.Ltr),
                displayCutout.getRight(density, LayoutDirection.Ltr)
            ) else 0

            val safeAreaTopDp = (topPx / density.density).toInt()
            val safeAreaBottomDp = (bottomPx / density.density).toInt()
            val safeAreaLeftDp = (leftPx / density.density).toInt()
            val safeAreaRightDp = (rightPx / density.density).toInt()

            val safeArea = JSONObject().apply {
                put("top", safeAreaTopDp)
                put("bottom", safeAreaBottomDp)
                put("left", safeAreaLeftDp)
                put("right", safeAreaRightDp)
            }

            val contentSafeArea = JSONObject().apply {
                put("top", safeAreaTopDp)
                put("bottom", safeAreaBottomDp)
                put("left", safeAreaLeftDp)
                put("right", safeAreaRightDp)
            }

            safeArea to contentSafeArea
        }.collect { (safeArea, contentSafeArea) ->
            state.telegramProxy?.updateSafeAreas(safeArea, contentSafeArea)
        }
    }

    LaunchedEffect(baseUrl, botUserId, chatId, state.isTOSVisible) {
        if (!state.isTOSVisible) {
            if (botUserId != 0L) {
                state.isInitializing = true
                val result = webAppRepository.openWebApp(chatId, botUserId, baseUrl, themeParams)
                if (result != null) {
                    state.launchId = result.launchId
                    state.currentUrl = result.url
                }
                state.isInitializing = false
            } else {
                state.currentUrl = baseUrl
                state.isInitializing = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            state.telegramProxy?.destroy()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                window.statusBarColor = Color.Transparent.toArgb()
                val isSystemInDarkTheme =
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                insetsController.isAppearanceLightStatusBars = !isSystemInDarkTheme
                window.navigationBarColor = Color.Transparent.toArgb()
                insetsController.isAppearanceLightNavigationBars = !isSystemInDarkTheme
            }
        }
    }

    rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        state.hasCameraPermission = isGranted
        if (!isGranted) {
            state.activeQrText = null
            state.telegramProxy?.dispatchToWebView("scan_qr_popup_closed", JSONObject())
        }
    }

    BackHandler {
        if (state.showMenu) {
            state.showMenu = false
        } else if (state.activeQrText != null) {
            state.activeQrText = null
            state.telegramProxy?.dispatchToWebView("scan_qr_popup_closed", JSONObject())
        } else if (state.isBackButtonVisible) {
            state.telegramProxy?.dispatchToWebView("back_button_pressed", JSONObject())
        } else if (state.webView?.canGoBack() == true) {
            state.webView?.goBack()
        } else if (state.isFullscreen) {
            state.isFullscreen = false
            state.telegramProxy?.dispatchToWebView("fullscreen_changed", JSONObject().put("is_fullscreen", false))
        } else {
            state.handleClose()
        }
    }

    if (state.showClosingConfirmation) {
        MiniAppClosingConfirmationDialog(
            onConfirm = {
                state.showClosingConfirmation = false
                if (state.launchId != 0L) {
                    scope.launch {
                        webAppRepository.closeWebApp(state.launchId)
                    }
                }

                onDismiss()
            },
            onDismiss = { state.showClosingConfirmation = false }
        )
    }

    if (state.activeInvoiceSlug != null) {
        InvoiceDialog(
            slug = state.activeInvoiceSlug!!,
            paymentRepository = paymentRepository,
            fileRepository = fileRepository,
            onDismiss = { status ->
                val slug = state.activeInvoiceSlug
                state.activeInvoiceSlug = null
                state.telegramProxy?.dispatchToWebView(
                    "invoice_closed",
                    JSONObject().put("slug", slug).put("status", status)
                )
            }
        )
    }

    state.activePopup?.let { popupState ->
        MiniAppPopupDialog(
            state = popupState,
            onDismiss = { buttonId ->
                state.activePopup = null
                if (buttonId != null) {
                    state.telegramProxy?.dispatchToWebView("popup_closed", JSONObject().put("button_id", buttonId))
                }
            }
        )
    }

    state.showPermissionRequest?.let { request ->
        MiniAppPermissionDialog(
            request = request,
            onDismiss = { state.showPermissionRequest = null }
        )
    }

    state.activeCustomMethod?.let { request ->
        MiniAppCustomMethodDialog(
            request = request,
            onDismiss = { state.activeCustomMethod = null }
        )
    }

    MiniAppPermissionsBottomSheet(
        isVisible = state.isPermissionsVisible,
        permissions = state.botPermissions,
        onDismiss = { state.onDismissPermissions() },
        onTogglePermission = { state.onTogglePermission(it) }
    )

    MiniAppTOSBottomSheet(
        isVisible = state.isTOSVisible,
        onDismiss = { onDismiss() },
        onAccept = { state.onAcceptTOS() }
    )

    if (!state.isTOSVisible) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = state.backgroundColor ?: MaterialTheme.colorScheme.surface
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!state.isFullscreen) {
                        MiniAppTopBar(
                            headerText = state.headerText,
                            isBackButtonVisible = state.isBackButtonVisible,
                            isSettingsButtonVisible = state.isSettingsButtonVisible,
                            isInitializing = state.isInitializing,
                            topBarColor = state.topBarColor,
                            topBarTextColor = state.topBarTextColor,
                            accentColor = state.accentColor,
                            onBackClick = {
                                state.telegramProxy?.dispatchToWebView(
                                    "back_button_pressed",
                                    JSONObject()
                                )
                            },
                            onCloseClick = { state.handleClose() },
                            onSettingsClick = {
                                state.telegramProxy?.dispatchToWebView(
                                    "settings_button_pressed",
                                    JSONObject()
                                )
                            },
                            onMenuClick = { state.showMenu = !state.showMenu }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            MiniAppLoadingView(
                                isInitializing = state.isInitializing,
                                isLoading = state.isLoading,
                                progress = state.progress,
                                backgroundColor = state.backgroundColor
                            )

                            MiniAppWebView(
                                url = state.currentUrl,
                                acceptLanguage = webAppLanguage,
                                themeParams = state.themeParams,
                                backgroundColor = state.backgroundColor,
                                host = state.createHost(secureStorage),
                                onUserInteraction = { state.markUserInteraction() },
                                onWebViewCreated = { wv, proxy ->
                                    state.webView = wv
                                    state.telegramProxy = proxy
                                },
                                onProgressChanged = { state.progress = it },
                                onLoadingChanged = { state.isLoading = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        MiniAppFullscreenControls(
                            visible = state.isFullscreen,
                            onBackClick = {
                                state.telegramProxy?.dispatchToWebView(
                                    "back_button_pressed",
                                    JSONObject()
                                )
                            },
                            onMenuClick = { state.showMenu = !state.showMenu }
                        )
                    }

                    if (!state.isFullscreen) {
                        MiniAppBottomBar(
                            mainButtonState = state.mainButtonState,
                            secondaryButtonState = state.secondaryButtonState,
                            bottomBarColor = state.bottomBarColor,
                            onMainButtonClick = {
                                state.telegramProxy?.dispatchToWebView(
                                    "main_button_pressed",
                                    JSONObject()
                                )
                            },
                            onSecondaryButtonClick = {
                                state.telegramProxy?.dispatchToWebView(
                                    "secondary_button_pressed",
                                    JSONObject()
                                )
                            }
                        )
                    }
                }

                MiniAppMenu(
                    visible = state.showMenu,
                    isFullscreen = state.isFullscreen,
                    url = state.currentUrl,
                    botUserId = botUserId,
                    botName = botName,
                    botAvatarPath = botAvatarPath,
                    context = context,
                    localClipboard = clipboardManager,
                    onDismiss = { state.showMenu = false },
                    onReload = { state.webView?.reload() }
                )

                MiniAppQrScanner(
                    qrText = state.activeQrText,
                    onCodeDetected = { code ->
                        state.activeQrText = null
                        state.telegramProxy?.dispatchToWebView("qr_text_received", JSONObject().put("data", code))
                    },
                    onBackClicked = {
                        state.activeQrText = null
                        state.telegramProxy?.dispatchToWebView("scan_qr_popup_closed", JSONObject())
                    }
                )
            }
        }
    }
}

private fun Int.toHex(): String = String.format("#%06X", (0xFFFFFF and this))
