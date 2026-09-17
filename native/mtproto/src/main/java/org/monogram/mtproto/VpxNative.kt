package org.monogram.mtproto

import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.VpxFrame
import uniffi.monogram_mtproto.createVpxDecoder
import uniffi.monogram_mtproto.decodeVpxPacket
import uniffi.monogram_mtproto.destroyVpxDecoder
import uniffi.monogram_mtproto.uniffiEnsureInitialized

/** Slim libvpx VP9 decode for video stickers. */
object VpxNative {
    init {
        runCatching {
            MtprotoNativeLoader.loadOrStub()
            uniffiEnsureInitialized()
        }
    }

    @Throws(MtprotoException::class)
    fun create(): Long = createVpxDecoder().toLong()

    fun destroy(handle: Long) {
        runCatching { destroyVpxDecoder(handle.toULong()) }
    }

    @Throws(MtprotoException::class)
    fun decode(handle: Long, packet: ByteArray): VpxFrame? =
        decodeVpxPacket(handle.toULong(), packet)
}
