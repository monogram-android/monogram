package org.monogram.feature.dialog.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val ALBUM_FLAG_LEFT = 1
internal const val ALBUM_FLAG_RIGHT = 2
internal const val ALBUM_FLAG_TOP = 4
internal const val ALBUM_FLAG_BOTTOM = 8

/** Telegram mosaic cell in an 800-wide layout space. */
internal data class AlbumCell(
    val index: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val flags: Int,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class AlbumMosaicPlan(
    val width: Float,
    val height: Float,
    val cells: List<AlbumCell>,
) {
    val aspect: Float
        get() = if (height <= 0f) 1f else width / height
}

/** Packed Compose Constraints cannot represent a side larger than this. */
internal const val MOSAIC_CONSTRAINT_MAX = 262143

internal const val ALBUM_BUBBLE_MAX_DP = 324

/** Telegram targets ~4:3; never let a 10-item 1:1 mosaic eat the screen. */
internal const val MOSAIC_MAX_HEIGHT_RATIO = 4f / 3f

/** Pixel size for a mosaic. Unbounded maxWidth must not become Int.MAX_VALUE. */
internal fun albumMosaicPixelSize(
    maxWidth: Int,
    minWidth: Int,
    hasBoundedWidth: Boolean,
    aspect: Float,
    fallbackWidth: Int,
): Pair<Int, Int> {
    val safeAspect = if (aspect.isFinite() && aspect > 0.05f) aspect else 1f
    val width = when {
        hasBoundedWidth -> maxWidth.coerceIn(1, MOSAIC_CONSTRAINT_MAX)
        minWidth in 1..MOSAIC_CONSTRAINT_MAX -> minWidth
        else -> fallbackWidth.coerceIn(1, MOSAIC_CONSTRAINT_MAX)
    }
    val uncapped = (width / safeAspect).roundToInt()
    val maxHeight = (width * MOSAIC_MAX_HEIGHT_RATIO).roundToInt()
        .coerceIn(1, MOSAIC_CONSTRAINT_MAX)
    val height = uncapped.coerceIn(1, maxHeight)
    return width to height
}

private const val MAX_W = 800f
private const val MAX_H = 814f
private const val MIN_W = 120f
private const val MIN_H = 120f

private data class AlbumItem(val ratio: Float, val letter: Char)

/**
 * Grouped message album mosaic layout calculation.
 * Ratios are width/height; cells are in send order (oldest first).
 */
internal fun layoutAlbum(ratios: List<Float>): AlbumMosaicPlan {
    if (ratios.isEmpty()) {
        return AlbumMosaicPlan(MAX_W, MAX_W, emptyList())
    }
    val count = ratios.size
    var forceCalc = count > 4
    val items = ArrayList<AlbumItem>(count)
    for (raw in ratios) {
        var ratio = raw.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (ratio > 2f) {
            ratio = 2f
            forceCalc = true
        } else if (ratio < 2f / 3f) {
            ratio = 2f / 3f
            forceCalc = true
        }
        val letter = when {
            ratio > 1.2f -> 'w'
            ratio < 0.8f -> 'n'
            else -> 'q'
        }
        items += AlbumItem(ratio, letter)
    }
    if (count == 1) {
        val height = min(MAX_W / items[0].ratio, MAX_H)
        return AlbumMosaicPlan(
            width = MAX_W,
            height = height,
            cells = listOf(
                cell(
                    0, 0f, 0f, MAX_W, height,
                    ALBUM_FLAG_LEFT or ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM,
                ),
            ),
        )
    }
    if (!forceCalc && count in 2..4) {
        return layoutSmallAlbum(items)
    }
    return layoutAttemptAlbum(items.map { it.ratio })
}

private fun layoutSmallAlbum(pos: List<AlbumItem>): AlbumMosaicPlan {
    val count = pos.size
    val proportions = pos.joinToString("") { it.letter.toString() }
    val average = pos.sumOf { it.ratio.toDouble() }.toFloat() / count
    val maxAspect = MAX_W / MAX_H

    if (count == 2) {
        val a = pos[0]
        val b = pos[1]
        if (proportions == "ww" && average > 1.4f * maxAspect && abs(a.ratio - b.ratio) < 0.2f) {
            val h = min(MAX_W / a.ratio, min(MAX_W / b.ratio, MAX_H / 2f))
            return plan(
                MAX_W,
                h * 2f,
                cell(0, 0f, 0f, MAX_W, h, ALBUM_FLAG_LEFT or ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP),
                cell(1, 0f, h, MAX_W, h * 2f, ALBUM_FLAG_LEFT or ALBUM_FLAG_RIGHT or ALBUM_FLAG_BOTTOM),
            )
        }
        if (proportions == "ww" || proportions == "qq") {
            val width = MAX_W / 2f
            val height = min(width / a.ratio, min(width / b.ratio, MAX_H))
            return plan(
                MAX_W,
                height,
                cell(0, 0f, 0f, width, height, ALBUM_FLAG_LEFT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM),
                cell(1, width, 0f, MAX_W, height, ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM),
            )
        }
        val second = max(0.4f * MAX_W, MAX_W / a.ratio / (1f / a.ratio + 1f / b.ratio))
        var first = MAX_W - second
        var right = second
        if (first < MIN_W) {
            val diff = MIN_W - first
            first = MIN_W
            right -= diff
        }
        val height = min(MAX_H, min(first / a.ratio, right / b.ratio))
        return plan(
            MAX_W,
            height,
            cell(0, 0f, 0f, first, height, ALBUM_FLAG_LEFT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM),
            cell(1, first, 0f, MAX_W, height, ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM),
        )
    }

    if (count == 3) {
        val a = pos[0]
        val b = pos[1]
        val c = pos[2]
        if (proportions[0] == 'n') {
            val thirdH = min(MAX_H * 0.5f, b.ratio * MAX_W / (c.ratio + b.ratio))
            val secondH = MAX_H - thirdH
            val rightW = max(
                MIN_W,
                min(MAX_W * 0.5f, min(thirdH * c.ratio, secondH * b.ratio)),
            )
            val leftW = min(MAX_H * a.ratio, MAX_W - rightW)
            return plan(
                MAX_W,
                MAX_H,
                cell(0, 0f, 0f, leftW, MAX_H, ALBUM_FLAG_LEFT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM),
                cell(1, leftW, 0f, MAX_W, secondH, ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP),
                cell(2, leftW, secondH, MAX_W, MAX_H, ALBUM_FLAG_RIGHT or ALBUM_FLAG_BOTTOM),
            )
        }
        val firstH = min(MAX_W / a.ratio, MAX_H * 0.66f)
        val width = MAX_W / 2f
        var secondH = min(MAX_H - firstH, min(width / b.ratio, width / c.ratio))
        if (secondH < MIN_H) secondH = MIN_H
        return plan(
            MAX_W,
            firstH + secondH,
            cell(0, 0f, 0f, MAX_W, firstH, ALBUM_FLAG_LEFT or ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP),
            cell(1, 0f, firstH, width, firstH + secondH, ALBUM_FLAG_LEFT or ALBUM_FLAG_BOTTOM),
            cell(2, width, firstH, MAX_W, firstH + secondH, ALBUM_FLAG_RIGHT or ALBUM_FLAG_BOTTOM),
        )
    }

    val a = pos[0]
    val b = pos[1]
    val c = pos[2]
    val d = pos[3]
    if (proportions[0] == 'w') {
        val h0 = min(MAX_W / a.ratio, MAX_H * 0.66f)
        var h = MAX_W / (b.ratio + c.ratio + d.ratio)
        var w0 = max(MIN_W, min(MAX_W * 0.4f, h * b.ratio))
        var w2 = max(max(MIN_W, MAX_W * 0.33f), h * d.ratio)
        var w1 = MAX_W - w0 - w2
        if (w1 < 58f) {
            val diff = 58f - w1
            w1 = 58f
            w0 -= diff / 2f
            w2 -= diff - diff / 2f
        }
        h = min(MAX_H - h0, h)
        if (h < MIN_H) h = MIN_H
        return plan(
            MAX_W,
            h0 + h,
            cell(0, 0f, 0f, MAX_W, h0, ALBUM_FLAG_LEFT or ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP),
            cell(1, 0f, h0, w0, h0 + h, ALBUM_FLAG_LEFT or ALBUM_FLAG_BOTTOM),
            cell(2, w0, h0, w0 + w1, h0 + h, ALBUM_FLAG_BOTTOM),
            cell(3, w0 + w1, h0, MAX_W, h0 + h, ALBUM_FLAG_RIGHT or ALBUM_FLAG_BOTTOM),
        )
    }
    val w = max(
        MIN_W,
        MAX_H / (1f / b.ratio + 1f / c.ratio + 1f / d.ratio),
    )
    val topFrac = min(0.33f, max(MIN_H, w / b.ratio) / MAX_H)
    val midFrac = min(0.33f, max(MIN_H, w / c.ratio) / MAX_H)
    val h0 = topFrac * MAX_H
    val h1 = midFrac * MAX_H
    val h2 = (1f - topFrac - midFrac) * MAX_H
    val w0 = min(MAX_H * a.ratio, MAX_W - w)
    return plan(
        MAX_W,
        MAX_H,
        cell(0, 0f, 0f, w0, MAX_H, ALBUM_FLAG_LEFT or ALBUM_FLAG_TOP or ALBUM_FLAG_BOTTOM),
        cell(1, w0, 0f, MAX_W, h0, ALBUM_FLAG_RIGHT or ALBUM_FLAG_TOP),
        cell(2, w0, h0, MAX_W, h0 + h1, ALBUM_FLAG_RIGHT),
        cell(3, w0, h0 + h1, MAX_W, h0 + h1 + h2, ALBUM_FLAG_RIGHT or ALBUM_FLAG_BOTTOM),
    )
}

private data class LineAttempt(val counts: IntArray, val heights: FloatArray)

private fun layoutAttemptAlbum(ratios: List<Float>): AlbumMosaicPlan {
    val count = ratios.size
    val average = ratios.average().toFloat()
    val cropped = FloatArray(count) { index ->
        val raw = if (average > 1.1f) max(1f, ratios[index]) else min(1f, ratios[index])
        raw.coerceIn(2f / 3f, 1.7f)
    }
    val attempts = ArrayList<LineAttempt>()
    for (first in 1 until count) {
        val second = count - first
        if (first <= 3 && second <= 3) {
            attempts += LineAttempt(
                intArrayOf(first, second),
                floatArrayOf(
                    multiHeight(cropped, 0, first),
                    multiHeight(cropped, first, count),
                ),
            )
        }
    }
    for (first in 1 until count - 1) {
        for (second in 1 until count - first) {
            val third = count - first - second
            if (first > 3 || second > 3 || third > 3) continue
            attempts += LineAttempt(
                intArrayOf(first, second, third),
                floatArrayOf(
                    multiHeight(cropped, 0, first),
                    multiHeight(cropped, first, first + second),
                    multiHeight(cropped, first + second, count),
                ),
            )
        }
    }
    for (first in 1 until count - 2) {
        for (second in 1 until count - first - 1) {
            for (third in 1 until count - first - second) {
                val fourth = count - first - second - third
                if (first > 3 || second > 3 || third > 3 || fourth > 3) continue
                attempts += LineAttempt(
                    intArrayOf(first, second, third, fourth),
                    floatArrayOf(
                        multiHeight(cropped, 0, first),
                        multiHeight(cropped, first, first + second),
                        multiHeight(cropped, first + second, first + second + third),
                        multiHeight(cropped, first + second + third, count),
                    ),
                )
            }
        }
    }
    val targetHeight = MAX_W / 3f * 4f
    var optimal: LineAttempt? = null
    var optimalDiff = Float.MAX_VALUE
    for (attempt in attempts) {
        var height = 0f
        var minLine = Float.MAX_VALUE
        for (lineHeight in attempt.heights) {
            height += lineHeight
            if (lineHeight < minLine) minLine = lineHeight
        }
        var diff = abs(height - targetHeight)
        if (attempt.counts.size > 1) {
            for (i in 0 until attempt.counts.lastIndex) {
                if (attempt.counts[i] > attempt.counts[i + 1]) {
                    diff *= 1.5f
                    break
                }
            }
        }
        if (minLine < MIN_W) diff *= 1.5f
        if (diff < optimalDiff) {
            optimal = attempt
            optimalDiff = diff
        }
    }
    val chosen = optimal ?: return evenGrid(count)
    val cells = ArrayList<AlbumCell>(count)
    var index = 0
    var y = 0f
    for (line in chosen.counts.indices) {
        val n = chosen.counts[line]
        val lineHeight = chosen.heights[line]
        var x = 0f
        for (k in 0 until n) {
            val ratio = cropped[index]
            var width = ratio * lineHeight
            if (k == n - 1) width = MAX_W - x
            var flags = 0
            if (line == 0) flags = flags or ALBUM_FLAG_TOP
            if (line == chosen.counts.lastIndex) flags = flags or ALBUM_FLAG_BOTTOM
            if (k == 0) flags = flags or ALBUM_FLAG_LEFT
            if (k == n - 1) flags = flags or ALBUM_FLAG_RIGHT
            cells += cell(index, x, y, x + width, y + lineHeight, flags)
            x += width
            index += 1
        }
        y += lineHeight
    }
    return AlbumMosaicPlan(MAX_W, y.coerceAtLeast(1f), cells)
}

private fun evenGrid(count: Int): AlbumMosaicPlan {
    val columns = if (count <= 2) count else 2
    val rows = (count + columns - 1) / columns
    val cellW = MAX_W / columns
    val cellH = cellW
    val cells = List(count) { index ->
        val col = index % columns
        val row = index / columns
        var flags = 0
        if (col == 0) flags = flags or ALBUM_FLAG_LEFT
        if (col == columns - 1 || index == count - 1) flags = flags or ALBUM_FLAG_RIGHT
        if (row == 0) flags = flags or ALBUM_FLAG_TOP
        if (row == rows - 1) flags = flags or ALBUM_FLAG_BOTTOM
        cell(
            index,
            col * cellW,
            row * cellH,
            (col + 1) * cellW,
            (row + 1) * cellH,
            flags,
        )
    }
    return AlbumMosaicPlan(MAX_W, rows * cellH, cells)
}

private fun multiHeight(ratios: FloatArray, start: Int, end: Int): Float {
    var sum = 0f
    for (i in start until end) sum += ratios[i]
    return if (sum <= 0f) MAX_H else MAX_W / sum
}

private fun cell(
    index: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    flags: Int,
): AlbumCell = AlbumCell(index, left, top, right, bottom, flags)

private fun plan(width: Float, height: Float, vararg cells: AlbumCell): AlbumMosaicPlan =
    AlbumMosaicPlan(width, height, cells.toList())
