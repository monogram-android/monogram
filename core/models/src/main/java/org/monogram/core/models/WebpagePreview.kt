package org.monogram.core.models

data class WebpagePreview(
    val url: String,
    val title: String? = null,
    val siteName: String? = null,
    val description: String? = null,
    val embedUrl: String? = null,
    val type: String? = null,
    val hasInstantView: Boolean = false,
    val hash: Int = 0,
) {
    val offersInstantView: Boolean
        get() = InstantViewPages.offersInstantView(type, hasInstantView)

    val hasContent: Boolean
        get() = !title.isNullOrBlank() || !siteName.isNullOrBlank() ||
            !description.isNullOrBlank() || hasInstantView
}

object WebpagePreviews {
    fun parse(raw: String?): WebpagePreview? {
        if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
        val root = CompactJson.parse(raw) as? Map<*, *> ?: return null
        val url = root["u"] as? String ?: return null
        if (url.isBlank()) return null
        return WebpagePreview(
            url = url,
            title = root["t"] as? String,
            siteName = root["s"] as? String,
            description = root["d"] as? String,
            embedUrl = root["e"] as? String,
            type = root["y"] as? String,
            hasInstantView = jsonTruthy(root["iv"]),
            hash = when (val value = root["h"]) {
                is Int -> value
                is Long -> value.toInt()
                is Double -> value.toInt()
                is String -> value.toIntOrNull() ?: 0
                else -> 0
            },
        )
    }
}

private fun jsonTruthy(value: Any?): Boolean = when (value) {
    true -> true
    is Number -> value.toInt() != 0
    is String -> value.equals("true", ignoreCase = true) || value == "1"
    else -> false
}
