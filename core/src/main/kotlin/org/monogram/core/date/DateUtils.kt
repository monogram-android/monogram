package org.monogram.core.date

import java.util.Date

/**
 * Map timestamp (in seconds) to [Date]
 **/
fun Int.toDate() = Date(this.toLong() * 1000L)