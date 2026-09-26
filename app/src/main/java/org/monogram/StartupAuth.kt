package org.monogram

import org.monogram.core.common.Outcome

internal suspend fun resolveStartOnHome(
    sessionAuthorized: suspend () -> Boolean,
    locallyAuthorized: suspend () -> Outcome<Boolean>,
): Boolean {
    if (sessionAuthorized()) return true
    return when (val local = locallyAuthorized()) {
        is Outcome.Ok -> local.value
        is Outcome.Err -> false
    }
}