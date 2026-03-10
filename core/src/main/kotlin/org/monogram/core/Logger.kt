package org.monogram.core

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

data class LogEvent(
    val level: LogLevel,
    val message: String,
    val tag: String? = null,
    val throwable: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any?> = emptyMap()
)

interface Logger {
    val isDebugEnabled: Boolean
    fun log(event: LogEvent)
}


inline fun Logger.debug(
    tag: String? = null,
    throwable: Throwable? = null,
    metadata: Map<String, Any?> = emptyMap(),
    message: () -> String
) {
    if (!isDebugEnabled) return

    log(
        LogEvent(
            level = LogLevel.DEBUG,
            message = message(),
            tag = tag,
            throwable = throwable,
            metadata = metadata
        )
    )
}

inline fun Logger.error(
    tag: String? = null,
    throwable: Throwable? = null,
    metadata: Map<String, Any?> = emptyMap(),
    message: () -> String
) {
    log(
        LogEvent(
            level = LogLevel.ERROR,
            message = message(),
            tag = tag,
            throwable = throwable,
            metadata = metadata
        )
    )
}