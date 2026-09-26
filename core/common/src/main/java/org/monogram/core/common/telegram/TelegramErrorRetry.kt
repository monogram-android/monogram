package org.monogram.core.common.telegram

import kotlinx.coroutines.delay
import org.monogram.core.common.Outcome

/**
 * Retry short flood waits (1..5s) a few times.
 * Longer waits are returned to the UI so the user can see the countdown.
 */
suspend fun <T> retryShortFlood(
    block: suspend () -> Outcome<T>,
): Outcome<T> {
    var result = block()
    repeat(6) {
        val wait = (result as? Outcome.Err)?.telegramError?.takeIf { it.canAutoRetry }
            ?.retryAfterSeconds
            ?: return result
        delay((wait + 1).coerceAtLeast(2) * 1_000L)
        result = block()
    }
    return result
}
