package org.monogram.core.models

import java.time.Instant
import java.time.ZoneId

sealed class LastSeen {
    data object Online : LastSeen()
    data object Recently : LastSeen()
    data object LastWeek : LastSeen()
    data object LastMonth : LastSeen()
    data object JustNow : LastSeen()
    data class MinutesAgo(val minutes: Int) : LastSeen()
    data class HoursAgo(val hours: Int) : LastSeen()
    data class TodayAt(val at: Long) : LastSeen()
    data class YesterdayAt(val at: Long) : LastSeen()
    data class At(val at: Long) : LastSeen()

    companion object {
        fun resolve(
            status: String?,
            statusAt: Long?,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): LastSeen? {
            val nowSeconds = nowMillis / 1000L
            return when (status) {
                "online" -> {
                    if (statusAt != null && statusAt < nowSeconds) {
                        fromWasOnline(statusAt, nowMillis, zone)
                    } else {
                        Online
                    }
                }
                "recently" -> Recently
                "last_week" -> LastWeek
                "last_month" -> LastMonth
                "offline" -> statusAt?.let { fromWasOnline(it, nowMillis, zone) } ?: Recently
                else -> null
            }
        }

        /**
         * Online heartbeats keep the label "online" but move the lease deadline, and that
         * deadline decides when the label or dot expires, so a later `expires` must reach
         * the state. Replayed or older deadlines are ignored.
         */
        fun affectsUi(
            previousStatus: String?,
            previousAt: Long?,
            nextStatus: String?,
            nextAt: Long?,
        ): Boolean {
            if (previousStatus != nextStatus) return true
            return when (nextStatus) {
                "online" -> nextAt != null && (previousAt == null || nextAt > previousAt)
                else -> previousAt != nextAt
            }
        }

        internal fun fromWasOnline(
            wasOnline: Long,
            nowMillis: Long,
            zone: ZoneId,
        ): LastSeen {
            val nowSeconds = nowMillis / 1000L
            val elapsed = (nowSeconds - wasOnline).coerceAtLeast(0)
            return when {
                elapsed < 60 -> JustNow
                elapsed < 60 * 60 -> MinutesAgo((elapsed / 60).toInt().coerceAtLeast(1))
                elapsed < 4 * 60 * 60 -> HoursAgo((elapsed / 3600).toInt().coerceAtLeast(1))
                else -> {
                    val then = Instant.ofEpochSecond(wasOnline).atZone(zone).toLocalDate()
                    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                    when (then) {
                        today -> TodayAt(wasOnline)
                        today.minusDays(1) -> YesterdayAt(wasOnline)
                        else -> At(wasOnline)
                    }
                }
            }
        }
    }
}
