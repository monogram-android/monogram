package org.monogram.core.ui.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val WAVEFORM_BINS = 64
private const val MAX_FILE_BYTES = 64L * 1024 * 1024
private const val MAX_PCM_BYTES = 128L * 1024 * 1024
private const val MAX_DURATION_US = 30L * 60 * 1_000_000
private const val MAX_DECODE_MS = 20_000L
private const val CODEC_TIMEOUT_US = 10_000L

private data class WaveformFile(val path: String, val modified: Long, val length: Long)

private val waveformMutex = Mutex()
private val decodeMutex = Mutex()
private val waveformScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val waveformInFlight = mutableMapOf<WaveformFile, Deferred<List<Float>>>()
private val waveformCache = object : LinkedHashMap<WaveformFile, List<Float>>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WaveformFile, List<Float>>): Boolean = size > 128
}

suspend fun voiceWaveform(file: File): List<Float> = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val identity = WaveformFile(file.absolutePath, file.lastModified(), file.length())
    if (!file.isFile || identity.length !in 1..MAX_FILE_BYTES) return@withContext emptyList()
    val deferred = waveformMutex.withLock {
        waveformCache[identity]?.let { return@withContext it }
        waveformInFlight[identity] ?: waveformScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val amplitudes = decodeMutex.withLock { decodeVoiceWaveform(file) }
                waveformMutex.withLock {
                    if (file.lastModified() == identity.modified && file.length() == identity.length) {
                        waveformCache.keys.removeAll { it.path == identity.path && it != identity }
                        waveformCache[identity] = amplitudes
                        amplitudes
                    } else emptyList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                waveformMutex.withLock { waveformCache[identity] = emptyList() }
                emptyList()
            } finally {
                waveformMutex.withLock { waveformInFlight.remove(identity) }
            }
        }.also { waveformInFlight[identity] = it }
    }
    deferred.await()
}

fun preloadVoiceWaveform(file: File) {
    if (file.isFile) waveformScope.async { runCatching { voiceWaveform(file) } }
}

suspend fun preloadVoiceWaveforms(files: List<File>) {
    files.distinctBy { it.absolutePath }.take(24).forEach { voiceWaveform(it) }
}

private suspend fun decodeVoiceWaveform(file: File): List<Float> {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    var nativeHandle = 0L
    try {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return emptyList()
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            return emptyList()
        }
        if (durationUs !in 1..MAX_DURATION_US) return emptyList()
        extractor.selectTrack(track)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val codec = MediaCodec.createDecoderByType(mime)
        decoder = codec
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        nativeHandle = runCatching { NativeWaveform.create(durationUs) }.getOrDefault(0L)
        val info = MediaCodec.BufferInfo()
        val context = currentCoroutineContext()
        val startedAt = SystemClock.elapsedRealtime()
        var inputEnded = false
        var outputEnded = false
        var decodedBytes = 0L
        var hasSamples = false

        while (!outputEnded) {
            context.ensureActive()
            if (SystemClock.elapsedRealtime() - startedAt >= MAX_DECODE_MS) return emptyList()
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: return emptyList()
                    input.clear()
                    val bytes = extractor.readSampleData(input, 0)
                    if (bytes < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, bytes, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                else -> if (outputIndex >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> return emptyList()
                            }
                            if (sampleRate !in 1..384_000 || channels !in 1..8) return emptyList()
                            decodedBytes += info.size
                            if (decodedBytes > MAX_PCM_BYTES) return emptyList()
                            val frameBytes = channels * bytesPerSample
                            if (info.size % frameBytes != 0) return emptyList()
                            val output = codec.getOutputBuffer(outputIndex) ?: return emptyList()
                            output.limit(info.offset + info.size)
                            output.position(info.offset)
                            output.order(ByteOrder.nativeOrder())
                            val pcm = ShortArray(info.size / frameBytes * channels)
                            for (index in pcm.indices) {
                                if (index % 4096 == 0) context.ensureActive()
                                pcm[index] = if (bytesPerSample == 2) {
                                    output.short
                                } else {
                                    (output.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                            }
                            if (nativeHandle == 0L) return emptyList()
                            NativeWaveform.add(nativeHandle, pcm, sampleRate, channels, info.presentationTimeUs)
                            hasSamples = true
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
        val waveform = if (hasSamples && nativeHandle != 0L) NativeWaveform.finish(nativeHandle) else emptyList()
        return Collections.unmodifiableList(waveform)
    } finally {
        if (nativeHandle != 0L) NativeWaveform.destroy(nativeHandle)
        try {
            decoder?.release()
        } finally {
            extractor.release()
        }
    }
}
