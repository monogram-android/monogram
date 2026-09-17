package org.monogram.feature.dialog.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.monogram.core.models.InstantViewBlock
import org.monogram.network.http.MediaRepository

@Composable
internal fun InstantViewEmbed(
    block: InstantViewBlock.Embed,
    scale: Float,
    query: String,
    mediaRepository: MediaRepository?,
    onOpenUrl: (String) -> Unit,
) {
    val html = block.html
    val url = block.url
    val ratio = if ((block.width ?: 0) > 0 && (block.height ?: 0) > 0) {
        block.width!!.toFloat() / block.height!!
    } else 16f / 9f
    val hasEmbed = !html.isNullOrBlank() || !url.isNullOrBlank()
    Column {
        if (!hasEmbed) {
            block.posterCacheKey?.let { InstantViewPhoto(it, block.width ?: 0, block.height ?: 0, mediaRepository) }
        } else {
            Box {
                block.posterCacheKey?.let {
                    InstantViewPhoto(it, block.width ?: 0, block.height ?: 0, mediaRepository)
                }
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val next = request?.url?.toString().orEmpty()
                                    return url == null || !next.startsWith(url)
                                }
                            }
                            when {
                                !html.isNullOrBlank() -> loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
                                !url.isNullOrBlank() -> loadUrl(url)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .aspectRatio(ratio.coerceIn(0.6f, 2.2f))
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
        block.caption?.let { InstantViewCaption(it, scale, query, onOpenUrl) }
    }
}
