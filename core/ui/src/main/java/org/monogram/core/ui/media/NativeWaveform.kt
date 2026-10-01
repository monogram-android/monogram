package org.monogram.core.ui.media

import uniffi.monogram_mtproto.addWaveformPcm
import uniffi.monogram_mtproto.createWaveform
import uniffi.monogram_mtproto.destroyWaveform
import uniffi.monogram_mtproto.finishWaveform

internal object NativeWaveform {
    fun create(durationUs: Long): Long = createWaveform(durationUs.toULong()).toLong()

    fun add(handle: Long, samples: ShortArray, sampleRate: Int, channels: Int, timestampUs: Long) {
        addWaveformPcm(handle.toULong(), samples.toList(), sampleRate.toUInt(), channels.toUInt(), timestampUs.coerceAtLeast(0L).toULong())
    }

    fun finish(handle: Long): List<Float> = finishWaveform(handle.toULong())

    fun destroy(handle: Long) {
        destroyWaveform(handle.toULong())
    }
}
