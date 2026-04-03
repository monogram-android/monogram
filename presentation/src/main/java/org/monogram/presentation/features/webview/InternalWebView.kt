@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.monogram.presentation.R
import org.monogram.presentation.features.webview.components.CertificateSheet
import org.monogram.presentation.features.webview.components.FindInPageBar
import org.monogram.presentation.features.webview.components.OptionsSheet
import org.monogram.presentation.features.webview.components.WebViewTopBar
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalWebView(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val loadingText = stringResource(R.string.webview_loading)
    val defaultTitle = stringResource(R.string.webview_default_title)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(loadingText) }
    var currentUrl by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isDesktopMode by remember { mutableStateOf(false) }
    var isAdBlockEnabled by remember { mutableStateOf(true) }
    var textZoom by remember { mutableIntStateOf(100) }

    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var activeMatchIndex by remember { mutableIntStateOf(0) }
    var matchCount by remember { mutableIntStateOf(0) }

    var isSecure by remember { mutableStateOf(url.startsWith("https")) }
    var sslCertificate by remember { mutableStateOf<SslCertificate?>(null) }
    var showCertificateSheet by remember { mutableStateOf(false) }
    val certificateSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val displayUrl = remember(currentUrl) {
        currentUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }

    val adBlockKeywords = remember {
        listOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adsystem.com", "adservice.com", "analytics", "/ads/", "/banners/", "tracker",
            "metrika", "sentry", "crashlytics", "app-measurement.com",
            "amplitude.com", "mixpanel.com", "facebook.com/tr", "adfox.ru",
            "ad.mail.ru", "track.mail.ru", "tns-counter.ru", "hotjar.com", "inspectlet.com"
        )
    }

    val defaultUserAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    LaunchedEffect(isDesktopMode, webView) {
        webView?.let {
            val newAgent = if (isDesktopMode) desktopUserAgent else defaultUserAgent
            if (it.settings.userAgentString != newAgent) {
                it.settings.userAgentString = newAgent
                it.reload()
            }
        }
    }

    LaunchedEffect(textZoom) {
        webView?.settings?.textZoom = textZoom
    }

    DisposableEffect(webView) {
        val listener = WebView.FindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                matchCount = numberOfMatches
                activeMatchIndex = activeMatchOrdinal
            }
        }
        webView?.setFindListener(listener)
        onDispose {
            webView?.setFindListener(null)
        }
    }

    LaunchedEffect(findQuery) {
        if (findQuery.isNotEmpty()) {
            webView?.findAllAsync(findQuery)
        } else {
            webView?.clearMatches()
            matchCount = 0
            activeMatchIndex = 0
        }
    }

    val dismissBottomSheet: () -> Unit = {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                showBottomSheet = false
            }
        }
    }

    val dismissCertificateSheet: () -> Unit = {
        scope.launch {
            certificateSheetState.hide()
        }.invokeOnCompletion {
            if (!certificateSheetState.isVisible) {
                showCertificateSheet = false
            }
        }
    }

    BackHandler {
        if (showBottomSheet) {
            dismissBottomSheet()
        } else if (showCertificateSheet) {
            dismissCertificateSheet()
        } else if (showFindInPage) {
            showFindInPage = false
            webView?.clearMatches()
        } else if (canGoBack) {
            webView?.goBack()
        } else {
            onDismiss()
        }
    }

    if (showCertificateSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCertificateSheet = false },
            sheetState = certificateSheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            CertificateSheet(
                isSecure = isSecure,
                sslCertificate = sslCertificate,
                onDismiss = dismissCertificateSheet
            )
        }
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = showFindInPage,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                            fadeOut(animationSpec = tween(300))
                },
                label = "TopBarAnimation"
            ) { isFinding ->
                if (isFinding) {
                    FindInPageBar(
                        query = findQuery,
                        onQueryChange = { findQuery = it },
                        matchCount = matchCount,
                        activeMatchIndex = activeMatchIndex,
                        webView = webView,
                        onClose = {
                            showFindInPage = false
                            webView?.clearMatches()
                        }
                    )
                } else {
                    WebViewTopBar(
                        title = title,
                        displayUrl = displayUrl,
                        isSecure = isSecure,
                        onDismiss = onDismiss,
                        onMoreOptions = { showBottomSheet = true },
                        onCertificateClick = { showCertificateSheet = true }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Fix for input focus/keyboard issue
                        isFocusable = true
                        isFocusableInTouchMode = true
                        @SuppressLint("ClickableViewAccessibility")
                        setOnTouchListener { v, _ ->
                            if (!v.hasFocus()) {
                                v.requestFocus()
                            }
                            false
                        }

                        @SuppressLint("SetJavaScriptEnabled")
                        settings.apply {
                            javaScriptEnabled = true
                            userAgentString = if (isDesktopMode) desktopUserAgent else defaultUserAgent
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = true
                            }
                            setSupportZoom(true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let { currentUrl = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                url?.let {
                                    currentUrl = it
                                    isSecure = it.startsWith("https") && view?.certificate != null
                                    sslCertificate = view?.certificate
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val url = uri.toString()
                                val context = view?.context ?: return false

                                if (url.startsWith("intent://")) {
                                    try {
                                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                        if (intent != null) {
                                            val packageManager = context.packageManager
                                            val info = packageManager.resolveActivity(
                                                intent,
                                                PackageManager.MATCH_DEFAULT_ONLY
                                            )

                                            if (info != null) {
                                                context.startActivity(intent)
                                                return true
                                            }

                                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                            if (!fallbackUrl.isNullOrEmpty()) {
                                                view.loadUrl(fallbackUrl)
                                                return true
                                            }

                                            val packagename = intent.`package`
                                            if (!packagename.isNullOrEmpty()) {
                                                val marketIntent = Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("market://details?id=$packagename")
                                                )
                                                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(marketIntent)
                                                return true
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Log error
                                    }
                                    return true
                                }

                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    return try {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                        true
                                    } catch (e: Exception) {
                                        true
                                    }
                                }

                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, uri)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                                        val packageManager = context.packageManager
                                        val resolveInfo =
                                            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

                                        if (resolveInfo != null) {
                                            val packageName = resolveInfo.activityInfo.packageName

                                            val isBrowser =
                                                packageName == "com.android.chrome" || packageName == "com.google.android.browser"
                                            val isMyPackage = packageName == context.packageName

                                            if (!isBrowser && !isMyPackage) {
                                                context.startActivity(intent)
                                                return true
                                            }
                                        }
                                    } catch (e: Exception) {
                                    }
                                }

                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (isAdBlockEnabled) {
                                    val requestUrl = request?.url?.toString()?.lowercase()
                                    if (requestUrl != null && adBlockKeywords.any { requestUrl.contains(it) }) {
                                        return WebResourceResponse(
                                            "text/plain",
                                            "UTF-8",
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView?, webTitle: String?) {
                                title = webTitle ?: defaultTitle
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = { view -> view.destroy() },
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
            )

            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(animationSpec = tween(300)) + expandVertically(),
                exit = fadeOut(animationSpec = tween(500)) + shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                OptionsSheet(
                    webView = webView,
                    currentUrl = currentUrl,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isDesktopMode = isDesktopMode,
                    isAdBlockEnabled = isAdBlockEnabled,
                    textZoom = textZoom,
                    onDesktopModeChange = { isDesktopMode = it },
                    onAdBlockChange = {
                        isAdBlockEnabled = it
                        webView?.reload()
                    },
                    onTextZoomChange = { textZoom = it },
                    onFindInPage = { showFindInPage = true },
                    onDismiss = dismissBottomSheet
                )
            }
        }
    }
}