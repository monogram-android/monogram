package org.monogram.mtproto.updates

data class MtProtoUpdateState(
    val cursor: MtProtoUpdateCursor,
    val channelPts: Map<Long, Int> = emptyMap(),
) {
    init {
        require(channelPts.keys.all { it > 0 }) { "Channel IDs must be positive" }
        require(channelPts.values.all { it >= 0 }) { "Channel pts must not be negative" }
    }
}
