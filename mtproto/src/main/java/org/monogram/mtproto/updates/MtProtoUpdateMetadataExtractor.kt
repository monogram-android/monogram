package org.monogram.mtproto.updates

import org.monogram.mtproto.tl.generated.cloud.layer223.*

data class MtProtoUpdateEnvelopeMetadata(
    val global: List<MtProtoUpdateOrdering>,
    val channels: List<MtProtoChannelUpdateOrdering>,
    val envelope: MtProtoUpdateOrdering?,
)

sealed interface MtProtoUpdateMetadataResult {
    data class Ordered(val metadata: MtProtoUpdateEnvelopeMetadata) : MtProtoUpdateMetadataResult
    data class Unsupported(val constructorId: UInt) : MtProtoUpdateMetadataResult
    data object RecoveryRequired : MtProtoUpdateMetadataResult
}

/** Extracts only counters proven by the generated TL fields; unknown updates fail closed. */
object MtProtoUpdateMetadataExtractor {
    fun extract(envelope: Updates_faf6aaa3d5): MtProtoUpdateMetadataResult = when (envelope) {
        UpdatesTooLong -> MtProtoUpdateMetadataResult.RecoveryRequired
        is Updates_02c952992b -> extractList(envelope.updates, envelope.date, envelope.seq, null)
        is UpdatesCombined -> extractList(envelope.updates, envelope.date, envelope.seq, envelope.seqStart)
        is UpdateShort -> when (val inner = extractUpdate(inner = envelope.update)) {
            is MtProtoUpdateMetadataResult.Ordered -> inner.withDate(envelope.date)
            else -> inner
        }
        is UpdateShortMessage -> MtProtoUpdateMetadataResult.Ordered(
            MtProtoUpdateEnvelopeMetadata(
                global = listOf(MtProtoUpdateOrdering(envelope.pts, envelope.ptsCount, date = envelope.date)),
                channels = emptyList(),
                envelope = null,
            )
        )
        is UpdateShortChatMessage -> MtProtoUpdateMetadataResult.Ordered(
            MtProtoUpdateEnvelopeMetadata(
                global = listOf(MtProtoUpdateOrdering(envelope.pts, envelope.ptsCount, date = envelope.date)),
                channels = emptyList(),
                envelope = null,
            )
        )
        is UpdateShortSentMessage -> MtProtoUpdateMetadataResult.Ordered(
            MtProtoUpdateEnvelopeMetadata(
                global = listOf(MtProtoUpdateOrdering(envelope.pts, envelope.ptsCount, date = envelope.date)),
                channels = emptyList(),
                envelope = null,
            )
        )
        else -> MtProtoUpdateMetadataResult.Unsupported(envelope.constructorId)
    }

    private fun extractList(
        updates: List<Update>,
        date: Int,
        seq: Int,
        seqStart: Int?,
    ): MtProtoUpdateMetadataResult {
        val extracted = updates.map(::extractUpdate)
        val unsupported = extracted.filterIsInstance<MtProtoUpdateMetadataResult.Unsupported>().firstOrNull()
        if (unsupported != null) return unsupported
        val ordered = extracted.filterIsInstance<MtProtoUpdateMetadataResult.Ordered>()
        return MtProtoUpdateMetadataResult.Ordered(
            MtProtoUpdateEnvelopeMetadata(
                global = ordered.flatMap { it.metadata.global },
                channels = ordered.flatMap { it.metadata.channels },
                envelope = MtProtoUpdateOrdering(date = date, seqStart = seqStart ?: seq, seq = seq),
            )
        )
    }

    private fun extractUpdate(inner: Update): MtProtoUpdateMetadataResult = when (inner) {
        is UpdateNewMessage -> global(inner.pts, inner.ptsCount)
        is UpdateEditMessage -> global(inner.pts, inner.ptsCount)
        is UpdateDeleteMessages -> global(inner.pts, inner.ptsCount)
        is UpdateReadHistoryInbox -> global(inner.pts, inner.ptsCount)
        is UpdateReadHistoryOutbox -> global(inner.pts, inner.ptsCount)
        is UpdateReadMessagesContents -> global(inner.pts, inner.ptsCount, inner.date)
        is UpdateWebPage -> global(inner.pts, inner.ptsCount)
        is UpdateNewEncryptedMessage -> global(qts = inner.qts, qtsCount = 1)
        is UpdateNewChannelMessage -> channelFromMessage(inner.message, inner.pts, inner.ptsCount)
        is UpdateEditChannelMessage -> channelFromMessage(inner.message, inner.pts, inner.ptsCount)
        is UpdateDeleteChannelMessages -> channel(inner.channelId, inner.pts, inner.ptsCount)
        is UpdatePinnedChannelMessages -> channel(inner.channelId, inner.pts, inner.ptsCount)
        is UpdateChannelWebPage -> channel(inner.channelId, inner.pts, inner.ptsCount)
        is UpdateReadChannelInbox -> channel(inner.channelId, inner.pts, 1)
        else -> MtProtoUpdateMetadataResult.Unsupported(inner.constructorId)
    }

    private fun channelFromMessage(message: Message_73e57f95e4, pts: Int, ptsCount: Int) =
        (message as? Message_7b7ecf54a3)?.peerId.let { peer ->
            (peer as? PeerChannel)?.let { channel(it.channelId, pts, ptsCount) }
                ?: MtProtoUpdateMetadataResult.Unsupported(message.constructorId)
        }

    private fun global(
        pts: Int? = null,
        ptsCount: Int = 0,
        date: Int? = null,
        qts: Int? = null,
        qtsCount: Int = 0,
    ) = MtProtoUpdateMetadataResult.Ordered(
        MtProtoUpdateEnvelopeMetadata(
            global = listOf(MtProtoUpdateOrdering(pts, ptsCount, qts, qtsCount, date)),
            channels = emptyList(),
            envelope = null,
        )
    )

    private fun channel(channelId: Long, pts: Int, ptsCount: Int) = MtProtoUpdateMetadataResult.Ordered(
        MtProtoUpdateEnvelopeMetadata(
            global = emptyList(),
            channels = listOf(MtProtoChannelUpdateOrdering(channelId, pts, ptsCount)),
            envelope = null,
        )
    )

    private fun MtProtoUpdateMetadataResult.Ordered.withDate(date: Int) =
        copy(metadata = metadata.copy(global = metadata.global.map { it.copy(date = date) }))
}
