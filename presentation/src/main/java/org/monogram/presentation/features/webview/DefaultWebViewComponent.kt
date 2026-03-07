package org.monogram.presentation.features.webview

import org.monogram.presentation.root.AppComponentContext

class DefaultWebViewComponent(
    context: AppComponentContext,
    override val url: String,
    private val onDismiss: () -> Unit
) : WebViewComponent, AppComponentContext by context {
    override fun onDismiss() = onDismiss.invoke()
}