package org.monogram.feature.dialog

internal enum class PanelBucket {
    TabStrip,
    KeyboardEmoji,
    KeyboardSticker,
}

internal enum class PanelTag {
    Tgs,
    Webm,
    WebmAlpha,
    Webp,
    Gif,
}

internal data class GridPlacement(
    val index: Int,
    val row: Int,
    val column: Int,
)

internal fun panelCacheName(documentId: Long, bucket: PanelBucket, tag: PanelTag): String =
    "${documentId}_${bucket.name}_${tag.name}"

internal fun panelBucketSide(bucket: PanelBucket): Int = when (bucket) {
    PanelBucket.TabStrip -> 96
    PanelBucket.KeyboardEmoji -> 168
    PanelBucket.KeyboardSticker -> 216
}

internal fun routesToVpx(tag: PanelTag): Boolean =
    tag == PanelTag.Webm || tag == PanelTag.WebmAlpha

internal fun vpxContextCost(tag: PanelTag): Int = when (tag) {
    PanelTag.WebmAlpha -> 2
    PanelTag.Webm -> 1
    else -> 0
}

internal fun panelNeedsNativeDecoder(frameCount: Int): Boolean = frameCount <= 0

internal fun panelFrameLimit(looping: Boolean): Int = if (looping) 16 else 1

internal fun panelPlaybackBand(
    cells: List<GridPlacement>,
    itemCount: Int,
    extraRows: Int = 1,
): IntRange {
    if (cells.isEmpty() || itemCount <= 0) return IntRange.EMPTY
    val columns = (cells.maxOf { it.column } + 1).coerceAtLeast(1)
    val minIndex = cells.minOf { it.index }.coerceIn(0, itemCount - 1)
    val maxIndex = cells.maxOf { it.index }.coerceIn(0, itemCount - 1)
    val end = (maxIndex + columns * extraRows.coerceAtLeast(0)).coerceAtMost(itemCount - 1)
    return minIndex..end
}

internal fun panelLoops(
    index: Int,
    band: IntRange,
    scrolling: Boolean,
    settled: Boolean,
    animationEnabled: Boolean,
): Boolean = animationEnabled && settled && !scrolling && index in band

internal fun animationScaleAllowsPlayback(scale: Float): Boolean = scale > 0f

internal class PanelBudgetLedger(
    private val maxTgs: Int = 4,
    private val maxVpx: Int = 4,
    private val maxLoops: Int = 6,
) {
    private var tgs = 0
    private var vpx = 0
    private var loops = 0
    private var nextTicket = 1
    private val held = HashMap<Long, Hold>()

    val liveTgs: Int get() = tgs
    val liveVpx: Int get() = vpx
    val liveLoops: Int get() = loops

    fun tryLoop(documentId: Long, tag: PanelTag): Int? {
        val existing = held[documentId]
        if (existing != null) return if (existing.tag == tag) existing.ticket else null
        if (tag != PanelTag.Tgs && !routesToVpx(tag)) return null
        if (loops >= maxLoops) return null
        val vpxCost = vpxContextCost(tag)
        val tgsCost = if (tag == PanelTag.Tgs) 1 else 0
        if (tgs + tgsCost > maxTgs || vpx + vpxCost > maxVpx) return null
        val ticket = nextTicket++
        held[documentId] = Hold(tag, ticket)
        tgs += tgsCost
        vpx += vpxCost
        loops += 1
        return ticket
    }

    fun tryAnimate(documentId: Long): Int? {
        held[documentId]?.let { return it.ticket }
        if (loops >= maxLoops) return null
        val ticket = nextTicket++
        held[documentId] = Hold(tag = null, ticket = ticket)
        loops += 1
        return ticket
    }

    fun stopLoop(documentId: Long, ticket: Int) = release(documentId, ticket)

    fun stopAnimate(documentId: Long, ticket: Int) = release(documentId, ticket)

    fun clear() {
        held.clear()
        tgs = 0
        vpx = 0
        loops = 0
    }

    private fun release(documentId: Long, ticket: Int) {
        val hold = held[documentId] ?: return
        if (hold.ticket != ticket) return
        held.remove(documentId)
        if (hold.tag == PanelTag.Tgs) tgs = (tgs - 1).coerceAtLeast(0)
        vpx = (vpx - vpxContextCost(hold.tag ?: PanelTag.Webp)).coerceAtLeast(0)
        loops = (loops - 1).coerceAtLeast(0)
    }

    private data class Hold(val tag: PanelTag?, val ticket: Int)
}

internal enum class GifCellPlayer { Video, Tgs, Still, Image }

internal fun gifCellPlayer(
    webm: Boolean,
    mp4: Boolean,
    gzip: Boolean,
    playable: Boolean,
): GifCellPlayer = when {
    (webm || mp4) && playable -> GifCellPlayer.Video
    gzip && playable -> GifCellPlayer.Tgs
    webm || mp4 -> GifCellPlayer.Still
    else -> GifCellPlayer.Image
}

internal fun webmHasAlpha(alphaMode: Boolean, blockAdditional: Boolean): Boolean =
    alphaMode || blockAdditional
