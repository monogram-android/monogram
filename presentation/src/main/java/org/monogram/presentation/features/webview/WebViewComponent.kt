package org.monogram.presentation.features.webview

interface WebViewComponent {
    val url: String
    fun onDismiss()
}