package org.monogram.feature.dialog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

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

internal fun panelBucketForSide(side: Int): PanelBucket? =
    PanelBucket.entries.firstOrNull { panelBucketSide(it) == side }

/** A partial prefix is not a cycle. Only a finished clip may repeat. */
internal fun panelClipMayLoop(clip: PanelFrameClip): Boolean =
    clip.complete && clip.frames.size > 1

/**
 * Source frame for [elapsedMs] at [fps]. Presentation may be slower than the
 * source; callers skip missed frames instead of advancing a fixed step.
 */
internal fun sourceFrameIndex(elapsedMs: Long, frameCount: Int, fps: Float): Int {
    if (frameCount <= 0 || elapsedMs <= 0L || fps <= 0f || fps.isNaN()) return 0
    val index = (elapsedMs.toDouble() * fps.toDouble() / 1000.0).toLong()
    if (index <= 0L) return 0
    return (index % frameCount.toLong()).toInt()
}

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

internal const val PanelSettleMs = 200L

internal fun panelEpoch(tab: String, content: String): String = "$tab\u0000$content"

internal fun panelLoops(
    index: Int,
    band: IntRange,
    scrolling: Boolean,
    settled: Boolean,
    animationEnabled: Boolean,
): Boolean = animationEnabled && settled && !scrolling && index in band

/** Sheet, lifecycle, and both scroll axes. Settled is never true on the first sample. */
internal fun panelAdmitsWork(
    visible: Boolean,
    started: Boolean,
    gridScrolling: Boolean,
    stripScrolling: Boolean,
    settled: Boolean,
): Boolean = visible && started && !gridScrolling && !stripScrolling && settled

internal fun panelNativeAdmitted(ready: Boolean): Boolean = ready

internal fun panelAnimationAdmitted(
    ready: Boolean,
    animations: Boolean,
    visible: Boolean,
    lease: Boolean,
): Boolean = ready && animations && visible && lease

internal class PanelSettleClock(private val settleMs: Long = PanelSettleMs) {
    private var epoch: Any? = null
    private var idleSinceMs: Long? = null
    private var settled: Boolean = false

    fun advance(
        panelEpoch: Any,
        visible: Boolean,
        started: Boolean,
        gridScrolling: Boolean,
        stripScrolling: Boolean,
        nowMs: Long,
    ): Boolean {
        if (epoch != panelEpoch) {
            epoch = panelEpoch
            settled = false
            idleSinceMs = null
        }
        val holding = visible && started && !gridScrolling && !stripScrolling
        if (!holding) {
            settled = false
            idleSinceMs = null
        } else if (!settled) {
            val since = idleSinceMs ?: nowMs.also { idleSinceMs = it }
            if (nowMs - since >= settleMs) settled = true
        }
        return panelAdmitsWork(visible, started, gridScrolling, stripScrolling, settled)
    }
}

internal enum class PickerPoster { CachedTransparent, CachedStill, ColdStill, None }

internal fun pickerPoster(
    tgs: Boolean,
    alphaVideo: Boolean,
    hasTransparentPoster: Boolean,
    hasCachedStill: Boolean,
    admitColdExtraction: Boolean,
): PickerPoster = when {
    tgs && hasTransparentPoster -> PickerPoster.CachedTransparent
    tgs && hasCachedStill -> PickerPoster.CachedStill
    tgs -> PickerPoster.None
    hasTransparentPoster -> PickerPoster.CachedTransparent
    alphaVideo -> PickerPoster.None
    hasCachedStill -> PickerPoster.CachedStill
    admitColdExtraction -> PickerPoster.ColdStill
    else -> PickerPoster.None
}

internal fun savedGifPoster(
    tgs: Boolean,
    webm: Boolean,
    knownAlpha: Boolean?,
    hasCachedPoster: Boolean,
    posterIsPng: Boolean,
    admitPoster: Boolean,
): PickerPoster {
    val alphaVideo = webm && knownAlpha == true
    val alphaUnresolved = webm && knownAlpha == null
    return pickerPoster(
        tgs = tgs,
        alphaVideo = alphaVideo,
        hasTransparentPoster = hasCachedPoster && alphaVideo && posterIsPng,
        hasCachedStill = hasCachedPoster && !alphaVideo,
        admitColdExtraction = admitPoster && !tgs && !alphaVideo && !alphaUnresolved,
    )
}

internal fun posterRequestKey(identity: String, maxSizePx: Int): String = "$identity@$maxSizePx"

internal fun savedGifConstructsPlayer(route: GifCellPlayer): Boolean =
    route == GifCellPlayer.Video || route == GifCellPlayer.Tgs

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
    private val animateWaiters = HashMap<Long, Int>()
    private val pendingAnimate = HashMap<Long, Int>()

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
        held[documentId] = Hold(tag, ticket, generation = ticket, place = 0)
        tgs += tgsCost
        vpx += vpxCost
        loops += 1
        return ticket
    }

    fun tryAnimate(documentId: Long, place: Int = 0): Int? {
        pendingAnimate.remove(documentId)
        animateWaiters.remove(documentId)
        val existing = held[documentId]
        if (existing != null) {
            if (existing.tag != null) return existing.ticket
            val ticket = nextTicket++
            held[documentId] = existing.copy(ticket = ticket, generation = ticket, place = place)
            return ticket
        }
        if (loops >= maxLoops) return null
        val ticket = nextTicket++
        held[documentId] = Hold(tag = null, ticket = ticket, generation = ticket, place = place)
        loops += 1
        return ticket
    }

    fun watchAnimate(documentId: Long, place: Int) {
        if (held.containsKey(documentId)) return
        animateWaiters[documentId] = place
    }

    fun unwatchAnimate(documentId: Long) {
        animateWaiters.remove(documentId)
    }

    fun placeAnimateWaiter(documentId: Long, place: Int) {
        if (documentId in animateWaiters) animateWaiters[documentId] = place
    }

    fun takePendingAnimate(documentId: Long): Int? = pendingAnimate.remove(documentId)

    fun leaseGeneration(documentId: Long): Int? = held[documentId]?.generation

    fun nearestAnimateWaiter(anchor: Int): Long? =
        animateWaiters.entries.minWithOrNull(
            compareBy<Map.Entry<Long, Int>> { abs(it.value - anchor) }
                .thenBy { it.value }
                .thenBy { it.key },
        )?.key

    fun stopLoop(documentId: Long, ticket: Int) = release(documentId, ticket)

    fun stopAnimate(documentId: Long, ticket: Int): Long? {
        val hold = held[documentId] ?: return null
        if (hold.ticket != ticket || hold.generation != ticket) return null
        val anchor = hold.place
        release(documentId, ticket)
        val next = animateWaiters.entries
            .filter { it.key !in held }
            .minWithOrNull(
                compareBy<Map.Entry<Long, Int>> { abs(it.value - anchor) }
                    .thenBy { it.value }
                    .thenBy { it.key },
            )?.key ?: return null
        val place = animateWaiters.remove(next) ?: return null
        val admitted = nextTicket++
        held[next] = Hold(tag = null, ticket = admitted, generation = admitted, place = place)
        loops += 1
        pendingAnimate[next] = admitted
        return next
    }

    fun clear() {
        held.clear()
        animateWaiters.clear()
        pendingAnimate.clear()
        tgs = 0
        vpx = 0
        loops = 0
    }

    private fun release(documentId: Long, ticket: Int) {
        val hold = held[documentId] ?: return
        if (hold.ticket != ticket || hold.generation != ticket) return
        held.remove(documentId)
        pendingAnimate.remove(documentId)
        if (hold.tag == PanelTag.Tgs) tgs = (tgs - 1).coerceAtLeast(0)
        vpx = (vpx - vpxContextCost(hold.tag ?: PanelTag.Webp)).coerceAtLeast(0)
        loops = (loops - 1).coerceAtLeast(0)
    }

    private data class Hold(
        val tag: PanelTag?,
        val ticket: Int,
        val generation: Int,
        val place: Int,
    )
}

internal enum class GifCellPlayer { Video, Tgs, Still, Image }

internal fun gifCellPlayer(
    webm: Boolean,
    mp4: Boolean,
    gzip: Boolean,
    playable: Boolean,
    lease: Boolean,
): GifCellPlayer = when {
    (webm || mp4) && playable && lease -> GifCellPlayer.Video
    gzip && playable && lease -> GifCellPlayer.Tgs
    gzip || webm || mp4 -> GifCellPlayer.Still
    else -> GifCellPlayer.Image
}

internal fun webmHasAlpha(alphaMode: Boolean, blockAdditional: Boolean): Boolean =
    alphaMode || blockAdditional

/** One cold poster at a time. Same key joins the in-flight load instead of decoding again. */
internal class PosterLoadGate<T>(private val maxEntries: Int = 32) {
    private val gate = Mutex()
    private val slots = Mutex()
    private val memory = LinkedHashMap<String, T>()
    private val inflight = HashMap<String, CompletableDeferred<T?>>()

    fun cached(key: String): T? = synchronized(memory) { memory[key] }

    suspend fun load(key: String, produce: suspend () -> T?): T? {
        synchronized(memory) { memory[key] }?.let { return it }
        val (owner, deferred) = gate.withLock {
            synchronized(memory) { memory[key] }?.let {
                return@withLock false to CompletableDeferred<T?>(
                    it
                )
            }
            val existing = inflight[key]
            if (existing != null) {
                false to existing
            } else {
                val created = CompletableDeferred<T?>()
                inflight[key] = created
                true to created
            }
        }
        if (!owner) return deferred.await()
        try {
            val value = slots.withLock { produce() }
            if (value != null) {
                synchronized(memory) {
                    memory[key] = value
                    while (memory.size > maxEntries) {
                        val eldest = memory.entries.firstOrNull()?.key ?: break
                        memory.remove(eldest)
                    }
                }
            }
            deferred.complete(value)
            return value
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            if (!deferred.isCompleted) deferred.completeExceptionally(error)
            throw error
        } finally {
            gate.withLock {
                if (inflight[key] === deferred) inflight.remove(key)
            }
        }
    }
}
