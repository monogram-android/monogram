package org.monogram.core.common

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * API/crash logging. Never write secrets (api_hash, auth keys, codes, passwords, bodies).
 */
object AppLog {
    private const val TAG = "monogram"
    private const val MAX_CRASH_BYTES = 256 * 1024
    @Volatile
    private var crashFile: File? = null

    fun init(cacheDir: File) {
        val file = File(cacheDir, "crash.log")
        crashFile = file
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeCrash(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun api(op: String, detail: String) {
        runCatching { Log.i("$TAG.api", "$op ${redact(detail)}") }
    }

    fun warn(op: String, detail: String) {
        runCatching { Log.w("$TAG.api", "$op ${redact(detail)}") }
    }

    fun writeCrash(thread: String, error: Throwable) {
        val body = buildString {
            append(Date())
            append(" thread=").append(thread).append('\n')
            append(redact(stackTrace(error)))
        }
        Log.e("$TAG.crash", body)
        runCatching {
            val file = crashFile ?: return@runCatching
            file.appendText(body + "\n\n")
            if (file.length() > MAX_CRASH_BYTES) {
                file.writeText(file.readText().takeLast(MAX_CRASH_BYTES))
            }
        }
    }

    fun readCrashLog(): String? = runCatching {
        crashFile?.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clearCrashLog() {
        runCatching { crashFile?.takeIf { it.isFile }?.delete() }
    }

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private val REDACT_PATTERNS = listOf(
        Regex("(?i)(api[_-]?hash\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(password\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(phone_code\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(auth[_-]?key\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(fcm[_-]?token\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(push[_-]?token\\s*[=:]\\s*)\\S+"),
    )

    private val SESSION_PATH = Regex("(?i)[\\w./-]*(?:session|mtproto)[\\w./-]*\\.(?:json|tmp)")

    internal fun redact(text: String): String {
        var out = text
        for (pattern in REDACT_PATTERNS) {
            out = pattern.replace(out, "$1[redacted]")
        }
        return SESSION_PATH.replace(out, "[session-path]")
    }
}
