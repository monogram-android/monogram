package org.monogram.app.di

import android.util.Log
import org.monogram.core.LogEvent
import org.monogram.core.LogLevel
import org.monogram.core.Logger
import org.monogram.data.BuildConfig

class LoggerImpl(
    override val isDebugEnabled: Boolean = BuildConfig.DEBUG,
    private val minLevel: LogLevel = defaultMinLevel(),
    private val defaultTag: String = "App"
) : Logger {

    companion object {
        private const val MAX_LOG_LENGTH = 4000

        private fun defaultMinLevel(): LogLevel {
            return if (BuildConfig.DEBUG) {
                LogLevel.VERBOSE
            } else {
                LogLevel.INFO
            }
        }
    }

    override fun log(event: LogEvent) {
        if (!shouldLog(event.level)) return

        val tag = event.tag ?: defaultTag
        val message = buildMessage(event)

        if (message.length <= MAX_LOG_LENGTH) {
            printLog(event.level, tag, message)
        } else {
            chunkedLog(event.level, tag, message)
        }
    }

    private fun shouldLog(level: LogLevel): Boolean {
        if (!isDebugEnabled && level == LogLevel.DEBUG) return false
        return level.ordinal >= minLevel.ordinal
    }

    private fun buildMessage(event: LogEvent): String {
        val builder = StringBuilder()

        builder.append("[${event.level}] ")

        builder.append(event.message)

        if (event.metadata.isNotEmpty()) {
            builder.append("\nmetadata=")
            builder.append(event.metadata.entries.joinToString(
                prefix = "{",
                postfix = "}"
            ) { "${it.key}=${it.value}" })
        }

        event.throwable?.let {
            builder.append("\n")
            builder.append(Log.getStackTraceString(it))
        }

        return builder.toString()
    }

    private fun chunkedLog(
        level: LogLevel,
        tag: String,
        message: String
    ) {
        var start = 0
        while (start < message.length) {
            val end = (start + MAX_LOG_LENGTH).coerceAtMost(message.length)
            printLog(level, tag, message.substring(start, end))
            start = end
        }
    }

    private fun printLog(
        level: LogLevel,
        tag: String,
        message: String
    ) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message)
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}