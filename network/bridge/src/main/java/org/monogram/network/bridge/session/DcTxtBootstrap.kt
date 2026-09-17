package org.monogram.network.bridge.session

import org.monogram.core.common.AppLog
import org.monogram.core.models.CompactJson
import org.monogram.core.models.jsonList
import org.monogram.core.models.jsonString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object DcTxtBootstrap {
    private const val GOOGLE =
        "https://dns.google/resolve?name=apv3.stel.com&type=TXT"
    private const val MOZILLA =
        "https://mozilla.cloudflare-dns.com/dns-query?name=apv3.stel.com&type=TXT"
    internal const val DOH_TTL_MS = 60L * 60L * 1000L

    fun refreshSidecar(sessionPath: String) {
        val dir = File(sessionPath).parent ?: "."
        val file = File(dir, "dc_txt.parts")
        val now = System.currentTimeMillis()
        if (sidecarFresh(file.lastModified(), now, DOH_TTL_MS) && file.isFile) {
            AppLog.api("dns txt", "parts=cached sidecar=${file.name}")
            return
        }
        val parts = fetchParts(GOOGLE) ?: fetchParts(MOZILLA) ?: return
        if (parts.size < 2) return
        runCatching {
            file.writeText(parts.take(2).joinToString("\n"))
            AppLog.api("dns txt", "parts=${parts.size} sidecar=${file.name}")
        }
    }

    fun logNativeStatus(sessionPath: String) {
        val file = File(File(sessionPath).parent ?: ".", "dc_txt.status")
        val line = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        if (line.isNotEmpty()) {
            AppLog.api("dns txt", line)
        }
    }

    internal fun sidecarFresh(lastModifiedMs: Long, nowMs: Long, ttlMs: Long): Boolean {
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
        return nowMs - lastModifiedMs < ttlMs
    }

    internal fun parseAnswerParts(json: String): List<String> {
        // CompactJson keeps this JVM-testable (org.json is stubbed empty on this module).
        val answer =
            (CompactJson.parse(json) as? Map<*, *>)?.jsonList("Answer") ?: return emptyList()
        return answer.mapNotNull { row ->
            (row as? Map<*, *>)?.jsonString("data")?.takeIf { it.isNotEmpty() }
        }
    }

    private fun fetchParts(url: String): List<String>? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            parseAnswerParts(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.takeIf { it.size >= 2 }
}