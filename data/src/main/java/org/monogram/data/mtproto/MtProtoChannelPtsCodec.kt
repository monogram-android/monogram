package org.monogram.data.mtproto

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal object MtProtoChannelPtsCodec {
    private val serializer = ListSerializer(ChannelPtsRecord.serializer())

    fun encode(channelPts: Map<Long, Int>): String = Json.encodeToString(
        serializer,
        channelPts.entries
            .sortedBy(Map.Entry<Long, Int>::key)
            .map { ChannelPtsRecord(it.key, it.value) },
    )

    fun decode(value: String?): Map<Long, Int> {
        if (value == null) return emptyMap()
        val records = Json.decodeFromString(serializer, value)
        require(records.all { it.channelId > 0 }) { "Channel IDs must be positive" }
        require(records.all { it.pts >= 0 }) { "Channel pts must not be negative" }
        require(records.map(ChannelPtsRecord::channelId).distinct().size == records.size) {
            "Duplicate channel cursor"
        }
        return records.associate { it.channelId to it.pts }
    }

    @Serializable
    private data class ChannelPtsRecord(
        val channelId: Long,
        val pts: Int,
    )
}
