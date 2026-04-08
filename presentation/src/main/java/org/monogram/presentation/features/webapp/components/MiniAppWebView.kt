package org.monogram.presentation.features.webapp.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.presentation.core.util.coRunCatching
import org.monogram.presentation.features.webapp.TelegramWebAppHost
import org.monogram.presentation.features.webapp.TelegramWebviewProxy
import androidx.compose.ui.graphics.Color as ComposeColor

@Composable
fun MiniAppWebView(
    url: String,
    acceptLanguage: String,
    themeParams: ThemeParams,
    backgroundColor: ComposeColor?,
    host: TelegramWebAppHost,
    onUserInteraction: () -> Unit,
    onWebViewCreated: (WebView, TelegramWebviewProxy) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var lastLoadedUrl by remember { mutableStateOf(url) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                @SuppressLint("SetJavaScriptEnabled")
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36 Telegram-Android/12.3.1 (Android; ${Build.MODEL}; SDK ${Build.VERSION.SDK_INT}; AVERAGE)"
                }

                setBackgroundColor(
                    backgroundColor?.toArgb() ?: themeParams.backgroundColor?.toColorInt() ?: Color.TRANSPARENT
                )
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onUserInteraction()
                    }
                    false
                }

                val bridge = TelegramWebviewProxy(
                    context = ctx,
                    webView = this,
                    themeParams = themeParams,
                    host = host
                )

                onWebViewCreated(this, bridge)
                addJavascriptInterface(bridge, "TelegramWebviewProxy")

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoadingChanged(false)
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.proceed()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        if (uri.scheme == "tg" || !listOf("http", "https").contains(uri.scheme)) {
                            coRunCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        uri
                                    )
                                )
                            }
                            return true
                        }
                        return false
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                if (url.isNotEmpty()) {
                    loadUrl(url, mapOf("Accept-Language" to acceptLanguage))
                }
            }
        },
        modifier = modifier.then(Modifier.fillMaxSize()),
        update = { view ->
            view.setBackgroundColor(
                backgroundColor?.toArgb() ?: themeParams.backgroundColor?.toColorInt() ?: Color.TRANSPARENT
            )
            if (url.isNotEmpty() && url != lastLoadedUrl) {
                view.loadUrl(url, mapOf("Accept-Language" to acceptLanguage))
                lastLoadedUrl = url
            }
        }
    )
}
