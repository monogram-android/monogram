package org.monogram.feature.dialog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PanelFrameClip(
    val width: Int,
    val height: Int,
    val delayMs: Int,
    val frames: List<ByteArray>,
)

internal object PanelFrameCache {
    private val MAGIC =
        byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
    private const val MAX_FRAMES = 90
    private const val MAX_SIDE = 512

    fun file(dir: File, documentId: Long, bucket: PanelBucket, tag: PanelTag): File =
        File(dir, panelCacheName(documentId, bucket, tag) + ".frames")

    fun write(dest: File, clip: PanelFrameClip) {
        if (!storeable(clip)) return
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.outputStream().buffered().use { stream ->
            val data = DataOutputStream(stream)
            data.write(MAGIC)
            data.writeInt(clip.width)
            data.writeInt(clip.height)
            data.writeInt(clip.delayMs.coerceAtLeast(1))
            data.writeInt(clip.frames.size)
            val packed = ByteArrayOutputStream()
            DeflaterOutputStream(packed, Deflater(Deflater.BEST_SPEED)).use { deflater ->
                clip.frames.forEach { frame -> deflater.write(frame) }
            }
            val bytes = packed.toByteArray()
            data.writeInt(bytes.size)
            data.write(bytes)
            data.flush()
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    fun read(source: File): PanelFrameClip? {
        if (!source.isFile || source.length() < 24L) return null
        return runCatching {
            source.inputStream().buffered().use { stream ->
                val data = DataInputStream(stream)
                val magic = ByteArray(4)
                data.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return null
                val width = data.readInt()
                val height = data.readInt()
                val delay = data.readInt()
                val count = data.readInt()
                val packedSize = data.readInt()
                if (!validShape(width, height, count, packedSize)) return null
                val packed = ByteArray(packedSize)
                data.readFully(packed)
                val frameBytes = width * height * 4
                val raw = InflaterInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
                if (raw.size != frameBytes * count) return null
                val frames = ArrayList<ByteArray>(count)
                for (index in 0 until count) {
                    val start = index * frameBytes
                    frames += raw.copyOfRange(start, start + frameBytes)
                }
                PanelFrameClip(width, height, delay, frames)
            }
        }.getOrNull()
    }

    private fun storeable(clip: PanelFrameClip): Boolean {
        if (!validShape(clip.width, clip.height, clip.frames.size, packedSize = 1)) return false
        val frameBytes = clip.width * clip.height * 4
        return clip.frames.all { it.size == frameBytes }
    }

    private fun validShape(width: Int, height: Int, count: Int, packedSize: Int): Boolean {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return false
        if (count !in 1..MAX_FRAMES || packedSize <= 0) return false
        val area = width.toLong() * height.toLong()
        return area <= MAX_SIDE.toLong() * MAX_SIDE.toLong()
    }
}

internal fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || maxSide <= 0) return 1 to 1
    val longest = max(width, height)
    if (longest <= maxSide) return width to height
    val scale = maxSide.toFloat() / longest.toFloat()
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

internal fun scaleRgba(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
    if (sw == dw && sh == dh) return src
    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return ByteArray(0)
    val out = ByteArray(dw * dh * 4)
    for (y in 0 until dh) {
        val sy = y * sh / dh
        for (x in 0 until dw) {
            val sx = x * sw / dw
            val s = (sy * sw + sx) * 4
            val d = (y * dw + x) * 4
            if (s + 3 >= src.size) continue
            out[d] = src[s]
            out[d + 1] = src[s + 1]
            out[d + 2] = src[s + 2]
            out[d + 3] = src[s + 3]
        }
    }
    return out
}
