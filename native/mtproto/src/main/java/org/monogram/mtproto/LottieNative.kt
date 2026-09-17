package org.monogram.mtproto

import uniffi.monogram_mtproto.LottieSize
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.createLottie
import uniffi.monogram_mtproto.destroyLottie
import uniffi.monogram_mtproto.lottieFrameCount
import uniffi.monogram_mtproto.lottieFrameRate
import uniffi.monogram_mtproto.lottieSize
import uniffi.monogram_mtproto.renderLottieFrame
import uniffi.monogram_mtproto.uniffiEnsureInitialized

/** UniFFI tlottie playback for TGS. */
object LottieNative {
    init {
        runCatching {
            MtprotoNativeLoader.loadOrStub()
            uniffiEnsureInitialized()
        }
    }

    @Throws(MtprotoException::class)
    fun create(data: ByteArray): Long = createLottie(data).toLong()

    fun destroy(handle: Long) {
        runCatching { destroyLottie(handle.toULong()) }
    }

    @Throws(MtprotoException::class)
    fun frameCount(handle: Long): Int = lottieFrameCount(handle.toULong()).toInt()

    @Throws(MtprotoException::class)
    fun frameRate(handle: Long): Float = lottieFrameRate(handle.toULong())

    @Throws(MtprotoException::class)
    fun size(handle: Long): LottieSize = lottieSize(handle.toULong())

    @Throws(MtprotoException::class)
    fun renderFrame(handle: Long, frame: Float, width: Int, height: Int): ByteArray =
        renderLottieFrame(handle.toULong(), frame, width.toUInt(), height.toUInt())
}
