package org.monogram.mtproto.transport

/** Receives raw MTProto transport byte counts as frames are written and read. */
fun interface MtProtoTrafficListener {
    /**
     * Called after each transport frame. Exactly one of [sentBytes] / [receivedBytes]
     * is non-zero per call; both may be reported independently across calls.
     */
    fun onTraffic(sentBytes: Int, receivedBytes: Int)
}
