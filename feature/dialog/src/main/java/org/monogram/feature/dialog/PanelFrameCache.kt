package org.monogram.feature.dialog

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PanelFrameClip(
    val width: Int,
    val height: Int,
    val delayMs: Int,
    val frames: List<ByteArray>,
    val complete: Boolean = false,
    val sourceDurationMs: Long = 0L,
)

internal object PanelFrameCache {
    internal const val PACKED_BYTE_CAP = 32 * 1024 * 1024
    internal const val INFLATED_BYTE_CAP = 512 * 512 * 4 * 90

    private val MAGIC =
        byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), '0'.code.toByte(), '2'.code.toByte())
    private const val MAX_FRAMES = 90
    private const val MAX_SIDE = 512
    private const val HEADER_BYTES = 33L
    private const val PANEL_FRAME_BYTE_CAP = 32L * 1024 * 1024

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
            data.writeBoolean(clip.complete)
            data.writeLong(clip.sourceDurationMs.coerceAtLeast(0L))
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
        dest.parentFile?.let(::trim)
    }

    fun trim(dir: File) {
        trimFrameFiles(
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".frames") }.orEmpty(),
            PANEL_FRAME_BYTE_CAP,
        )
    }

    fun read(source: File): PanelFrameClip? = readBounded(source, requestedSide = null)

    fun read(source: File, requestedSide: Int): PanelFrameClip? {
        if (requestedSide <= 0) return null
        return readBounded(source, requestedSide)
    }

    private fun readBounded(source: File, requestedSide: Int?): PanelFrameClip? {
        if (!source.isFile || source.length() < HEADER_BYTES) return null
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
                val complete = data.readBoolean()
                val sourceDurationMs = data.readLong()
                val packedSize = data.readInt()
                if (sourceDurationMs < 0L) return null
                if (!validShape(width, height, count, packedSize)) return null
                if (requestedSide != null && max(width, height) < requestedSide) return null
                val inflated = width.toLong() * height.toLong() * 4L * count.toLong()
                if (inflated <= 0L || inflated > INFLATED_BYTE_CAP) return null
                if (packedSize.toLong() > source.length() - HEADER_BYTES) return null
                val packed = ByteArray(packedSize)
                data.readFully(packed)
                val raw = ByteArray(inflated.toInt())
                val inflater = Inflater()
                try {
                    inflater.setInput(packed)
                    var offset = 0
                    while (offset < raw.size) {
                        val read = inflater.inflate(raw, offset, raw.size - offset)
                        if (read <= 0) return null
                        offset += read
                    }
                    if (!inflater.finished()) {
                        val extra = inflater.inflate(ByteArray(1))
                        if (extra != 0 || !inflater.finished()) return null
                    }
                } finally {
                    inflater.end()
                }
                val frameBytes = (inflated / count).toInt()
                val frames = ArrayList<ByteArray>(count)
                for (index in 0 until count) {
                    val start = index * frameBytes
                    frames += raw.copyOfRange(start, start + frameBytes)
                }
                PanelFrameClip(width, height, delay, frames, complete, sourceDurationMs)
            }
        }.getOrNull()
    }

    private fun storeable(clip: PanelFrameClip): Boolean {
        if (clip.sourceDurationMs < 0L) return false
        if (!validShape(clip.width, clip.height, clip.frames.size, packedSize = 1)) return false
        val frameBytes = clip.width * clip.height * 4
        return clip.frames.all { it.size == frameBytes }
    }

    private fun validShape(width: Int, height: Int, count: Int, packedSize: Int): Boolean {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return false
        if (count !in 1..MAX_FRAMES || packedSize <= 0 || packedSize > PACKED_BYTE_CAP) return false
        val inflated = width.toLong() * height.toLong() * 4L * count.toLong()
        return inflated in 1..INFLATED_BYTE_CAP.toLong()
    }
}

internal fun trimFrameFiles(files: List<File>, capBytes: Long) {
    var total = files.sumOf { it.length() }
    if (total <= capBytes) return
    for (file in files.sortedBy { it.lastModified() }) {
        if (total <= capBytes) return
        val length = file.length()
        if (file.delete()) total -= length
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
