package org.monogram.core.common

import org.monogram.core.common.telegram.TelegramError

sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(
        val message: String,
        val cause: Throwable? = null,
        val telegram: TelegramError? = null,
    ) : Outcome<Nothing>() {
        val telegramError: TelegramError
            get() = telegram ?: TelegramError.parse(message)
    }
}

inline fun <T> Outcome<T>.getOrElse(fallback: (Outcome.Err) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fallback(this)
}

fun <T> Outcome<T>.getOrNull(): T? = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> null
}
