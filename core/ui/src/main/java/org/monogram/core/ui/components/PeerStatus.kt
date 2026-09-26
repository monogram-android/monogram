package org.monogram.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.monogram.core.models.LastSeen
import org.monogram.core.ui.R
import java.text.DateFormat
import java.util.Date

/** Wire value of `userStatusOnline`; presence elsewhere is already last-seen. */
private const val WireOnline = "online"

fun formatPeerStatus(
    status: String?,
    statusAt: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): LastSeen? = LastSeen.resolve(status, statusAt, nowMillis)

/**
 * True only while the `online` lease is still valid.
 *
 * Telegram sends `online` with a future `expires`, so [statusAt] is a deadline, not a
 * last-seen moment: once it passes the peer is last-seen until a fresher status arrives.
 */
fun isPeerOnline(
    status: String?,
    statusAt: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean = formatPeerStatus(status, statusAt, nowMillis) == LastSeen.Online

/**
 * Presence wall clock for the surrounding composition.
 *
 * `online` is a lease, so a label or dot built from [statusAt] has to be re-evaluated when
 * the lease runs out instead of waiting for an unrelated recomposition. Non-online statuses
 * keep the composition-time clock: their buckets only need to be current when something
 * else changes the screen.
 */
@Composable
fun rememberPeerStatusNow(status: String?, statusAt: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status, statusAt) {
        now = System.currentTimeMillis()
        if (status != WireOnline) return@LaunchedEffect
        val remaining = onlineLeaseRemaining(statusAt)
        if (remaining > 0) delay(remaining)
        now = System.currentTimeMillis()
    }
    return now
}

/**
 * Invokes [onExpired] when an `online` lease runs out so the caller can re-request the
 * status. The effect is keyed by the status pair, so an unanswered refresh cannot loop, and
 * a lease that is already stale does nothing: opening a peer already re-reads its profile.
 */
@Composable
fun OnlineLeaseExpiry(status: String?, statusAt: Long?, onExpired: () -> Unit) {
    val current by rememberUpdatedState(onExpired)
    LaunchedEffect(status, statusAt) {
        if (status != WireOnline) return@LaunchedEffect
        val remaining = onlineLeaseRemaining(statusAt)
        if (remaining <= 1L) return@LaunchedEffect
        delay(remaining)
        current()
    }
}

private fun onlineLeaseRemaining(statusAt: Long?): Long {
    val expiresAtMillis = statusAt?.times(1000L) ?: return 0L
    return (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 1L
}

@Composable
fun peerStatusLabel(status: String?, statusAt: Long?): String? =
    peerStatusLabel(status, statusAt, System.currentTimeMillis())

@Composable
fun peerStatusLabel(status: String?, statusAt: Long?, nowMillis: Long): String? {
    return when (val kind = formatPeerStatus(status, statusAt, nowMillis)) {
        null -> null
        LastSeen.Online -> stringResource(R.string.status_online)
        LastSeen.Recently -> stringResource(R.string.status_last_seen_recently)
        LastSeen.LastWeek -> stringResource(R.string.status_last_seen_week)
        LastSeen.LastMonth -> stringResource(R.string.status_last_seen_month)
        LastSeen.JustNow -> stringResource(R.string.status_last_seen_just_now)
        is LastSeen.MinutesAgo -> pluralStringResource(
            R.plurals.status_last_seen_minutes,
            kind.minutes,
            kind.minutes,
        )
        is LastSeen.HoursAgo -> pluralStringResource(
            R.plurals.status_last_seen_hours,
            kind.hours,
            kind.hours,
        )
        is LastSeen.TodayAt -> stringResource(
            R.string.status_last_seen_today,
            formatStatusTime(kind.at),
        )
        is LastSeen.YesterdayAt -> stringResource(
            R.string.status_last_seen_yesterday,
            formatStatusTime(kind.at),
        )
        is LastSeen.At -> stringResource(
            R.string.status_last_seen_at,
            formatStatusDateTime(kind.at),
        )
    }
}

private fun formatStatusTime(at: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at * 1000L))

private fun formatStatusDateTime(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at * 1000L))
