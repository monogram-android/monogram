package org.monogram.presentation.features.webapp.components

import org.monogram.presentation.core.util.coRunCatching
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.graphics.toColorInt
import androidx.compose.ui.viewinterop.AndroidView
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.presentation.features.webapp.TelegramWebAppHost
import org.monogram.presentation.features.webapp.TelegramWebviewProxy

@Composable
fun MiniAppWebView(
    url: String,
    acceptLanguage: String,
    themeParams: ThemeParams,
    host: TelegramWebAppHost,
    onWebViewCreated: (WebView, TelegramWebviewProxy) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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

                setBackgroundColor(themeParams.backgroundColor?.toColorInt() ?: Color.TRANSPARENT)

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
            view.setBackgroundColor(themeParams.backgroundColor?.toColorInt() ?: Color.TRANSPARENT)
            if (url.isNotEmpty() && view.url != url) {
                view.loadUrl(url, mapOf("Accept-Language" to acceptLanguage))
            }
        }
    )
}
