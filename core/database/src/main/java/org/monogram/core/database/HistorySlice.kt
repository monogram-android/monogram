package org.monogram.core.database

import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.models.Message

/**
 * Local history for one request. [rows] are whatever is already stored, including a
 * partial offline window. [covered] is true only when that id span has been proven,
 * never because the row count happens to look full.
 */
data class HistorySlice(
    val rows: List<Message>,
    val covered: Boolean,
    val holes: List<LongRange>,
    val confirmedStart: Boolean,
    val confirmedEnd: Boolean,
    val fresh: Boolean,
)

fun historySliceOf(
    rows: List<Message>,
    coverageInitialized: Boolean,
    holes: List<MessageHoleEntity>,
    requestedStart: Int,
    requestedEnd: Int,
): HistorySlice {
    val intersecting = holes.filter { hole ->
        requestedStart <= requestedEnd &&
                hole.endId >= requestedStart &&
                hole.startId <= requestedEnd
    }
    val confirmedStart = coverageInitialized && holes.none { it.startId <= 1 && it.endId >= 1 }
    val confirmedEnd = coverageInitialized && holes.none { it.endId == Int.MAX_VALUE }
    return HistorySlice(
        rows = rows,
        covered = coverageInitialized && intersecting.isEmpty() && requestedStart <= requestedEnd,
        holes = intersecting.map { it.startId.toLong()..it.endId.toLong() },
        confirmedStart = confirmedStart,
        confirmedEnd = confirmedEnd,
        fresh = coverageInitialized,
    )
}
