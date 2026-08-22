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
        // Server invalidated the global state; a full resync is required.
        is UpdatePtsChanged -> MtProtoUpdateMetadataResult.RecoveryRequired
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
        is UpdateFolderPeers -> global(inner.pts, inner.ptsCount)
        // `channelTooLong` hints at channel drift without a mandatory counter; the next genuine
        // channel update triggers pts-gap recovery, which now exists end-to-end.
        is UpdateChannelTooLong ->
            inner.pts?.let { channel(inner.channelId, it, ptsCount = 1) }
                ?: MtProtoUpdateMetadataResult.Ordered(
                    MtProtoUpdateEnvelopeMetadata(emptyList(), emptyList(), null),
                )
        is UpdatePinnedMessages -> global(inner.pts, inner.ptsCount)
        // Bot/business/participant updates carry a qts counter that must advance.
        is UpdateBotBusinessConnect -> globalQts(inner.qts)
        is UpdateBotChatBoost -> globalQts(inner.qts)
        is UpdateBotChatInviteRequester -> globalQts(inner.qts)
        is UpdateBotDeleteBusinessMessage -> globalQts(inner.qts)
        is UpdateBotEditBusinessMessage -> globalQts(inner.qts)
        is UpdateBotNewBusinessMessage -> globalQts(inner.qts)
        is UpdateBotPurchasedPaidMedia -> globalQts(inner.qts)
        is UpdateChannelParticipant -> globalQts(inner.qts)
        is UpdateChatParticipant -> globalQts(inner.qts)
        // Informational updates without counters are staged but never affect ordering.
        inner if inner.constructorId in ORDER_FREE_CONSTRUCTORS ->
            MtProtoUpdateMetadataResult.Ordered(MtProtoUpdateEnvelopeMetadata(emptyList(), emptyList(), null))
        else -> MtProtoUpdateMetadataResult.Unsupported(inner.constructorId)
    }

    private fun globalQts(qts: Int) = global(qts = qts, qtsCount = 1)

    /**
     * Layer-223 constructors that carry no pts/qts/seq counters and no consumed side effects
     * beyond their raw payload (config, langpacks, stickers, typing, bot webhooks, ...).
     * They are durably staged with each envelope and safely ignored for ordering math.
     */
    private val ORDER_FREE_CONSTRUCTORS: Set<UInt> = setOf(
UpdateAttachMenuBots.CONSTRUCTOR_ID,
        UpdateAutoSaveSettings.CONSTRUCTOR_ID,
        UpdateBotCallbackQuery.CONSTRUCTOR_ID,
        UpdateBotCommands.CONSTRUCTOR_ID,
        UpdateBotInlineQuery.CONSTRUCTOR_ID,
        UpdateBotInlineSend.CONSTRUCTOR_ID,
        UpdateBotMenuButton.CONSTRUCTOR_ID,
        UpdateBotPrecheckoutQuery.CONSTRUCTOR_ID,
        UpdateBotShippingQuery.CONSTRUCTOR_ID,
        UpdateBotWebhookJson.CONSTRUCTOR_ID,
        UpdateBotWebhookJsonQuery.CONSTRUCTOR_ID,
        UpdateBusinessBotCallbackQuery.CONSTRUCTOR_ID,
        UpdateChannel.CONSTRUCTOR_ID,
        UpdateChannelAvailableMessages.CONSTRUCTOR_ID,
        UpdateChannelMessageForwards.CONSTRUCTOR_ID,
        UpdateChannelMessageViews.CONSTRUCTOR_ID,
        UpdateChannelReadMessagesContents.CONSTRUCTOR_ID,
        UpdateChannelUserTyping.CONSTRUCTOR_ID,
        UpdateChannelViewForumAsMessages.CONSTRUCTOR_ID,
        UpdateChat.CONSTRUCTOR_ID,
        UpdateChatDefaultBannedRights.CONSTRUCTOR_ID,
        UpdateChatParticipantAdd.CONSTRUCTOR_ID,
        UpdateChatParticipantAdmin.CONSTRUCTOR_ID,
        UpdateChatParticipantDelete.CONSTRUCTOR_ID,
        UpdateChatParticipantRank.CONSTRUCTOR_ID,
        UpdateChatParticipants.CONSTRUCTOR_ID,
        UpdateChatUserTyping.CONSTRUCTOR_ID,
        UpdateConfig.CONSTRUCTOR_ID,
        UpdateContactsReset.CONSTRUCTOR_ID,
        UpdateDcOptions.CONSTRUCTOR_ID,
        UpdateDeleteGroupCallMessages.CONSTRUCTOR_ID,
        UpdateDeleteQuickReply.CONSTRUCTOR_ID,
        UpdateDeleteQuickReplyMessages.CONSTRUCTOR_ID,
        UpdateDeleteScheduledMessages.CONSTRUCTOR_ID,
        UpdateDialogFilter.CONSTRUCTOR_ID,
        UpdateDialogFilterOrder.CONSTRUCTOR_ID,
        UpdateDialogFilters.CONSTRUCTOR_ID,
        UpdateDialogPinned.CONSTRUCTOR_ID,
        UpdateDialogUnreadMark.CONSTRUCTOR_ID,
        UpdateDraftMessage.CONSTRUCTOR_ID,
        UpdateEmojiGameInfo.CONSTRUCTOR_ID,
        UpdateEncryptedChatTyping.CONSTRUCTOR_ID,
        UpdateEncryptedMessagesRead.CONSTRUCTOR_ID,
        UpdateEncryption.CONSTRUCTOR_ID,
        UpdateFavedStickers.CONSTRUCTOR_ID,
        UpdateGeoLiveViewed.CONSTRUCTOR_ID,
        UpdateGroupCall.CONSTRUCTOR_ID,
        UpdateGroupCallChainBlocks.CONSTRUCTOR_ID,
        UpdateGroupCallConnection.CONSTRUCTOR_ID,
        UpdateGroupCallEncryptedMessage.CONSTRUCTOR_ID,
        UpdateGroupCallMessage.CONSTRUCTOR_ID,
        UpdateGroupCallParticipants.CONSTRUCTOR_ID,
        UpdateInlineBotCallbackQuery.CONSTRUCTOR_ID,
        UpdateLangPack.CONSTRUCTOR_ID,
        UpdateLangPackTooLong.CONSTRUCTOR_ID,
        UpdateLoginToken.CONSTRUCTOR_ID,
        UpdateMessageExtendedMedia.CONSTRUCTOR_ID,
        UpdateMessageId.CONSTRUCTOR_ID,
        UpdateMessagePoll.CONSTRUCTOR_ID,
        UpdateMessageReactions.CONSTRUCTOR_ID,
        UpdateMonoForumNoPaidException.CONSTRUCTOR_ID,
        UpdateMoveStickerSetToTop.CONSTRUCTOR_ID,
        UpdateNewAuthorization.CONSTRUCTOR_ID,
        UpdateNewQuickReply.CONSTRUCTOR_ID,
        UpdateNewScheduledMessage.CONSTRUCTOR_ID,
        UpdateNewStickerSet.CONSTRUCTOR_ID,
        UpdateNewStoryReaction.CONSTRUCTOR_ID,
        UpdateNotifySettings.CONSTRUCTOR_ID,
        UpdatePaidReactionPrivacy.CONSTRUCTOR_ID,
        UpdatePeerBlocked.CONSTRUCTOR_ID,
        UpdatePeerHistoryTtl.CONSTRUCTOR_ID,
        UpdatePeerLocated.CONSTRUCTOR_ID,
        UpdatePeerSettings.CONSTRUCTOR_ID,
        UpdatePeerWallpaper.CONSTRUCTOR_ID,
        UpdatePendingJoinRequests.CONSTRUCTOR_ID,
        UpdatePhoneCall.CONSTRUCTOR_ID,
        UpdatePhoneCallSignalingData.CONSTRUCTOR_ID,
        UpdatePinnedDialogs.CONSTRUCTOR_ID,
        UpdatePinnedForumTopic.CONSTRUCTOR_ID,
        UpdatePinnedForumTopics.CONSTRUCTOR_ID,
        UpdatePinnedSavedDialogs.CONSTRUCTOR_ID,
        UpdatePrivacy.CONSTRUCTOR_ID,
        UpdatePtsChanged.CONSTRUCTOR_ID,
        UpdateQuickReplies.CONSTRUCTOR_ID,
        UpdateQuickReplyMessage.CONSTRUCTOR_ID,
        UpdateReadChannelDiscussionInbox.CONSTRUCTOR_ID,
        UpdateReadChannelDiscussionOutbox.CONSTRUCTOR_ID,
        UpdateReadChannelOutbox.CONSTRUCTOR_ID,
        UpdateReadFeaturedEmojiStickers.CONSTRUCTOR_ID,
        UpdateReadFeaturedStickers.CONSTRUCTOR_ID,
        UpdateReadMonoForumInbox.CONSTRUCTOR_ID,
        UpdateReadMonoForumOutbox.CONSTRUCTOR_ID,
        UpdateReadStories.CONSTRUCTOR_ID,
        UpdateRecentEmojiStatuses.CONSTRUCTOR_ID,
        UpdateRecentReactions.CONSTRUCTOR_ID,
        UpdateRecentStickers.CONSTRUCTOR_ID,
        UpdateSavedDialogPinned.CONSTRUCTOR_ID,
        UpdateSavedGifs.CONSTRUCTOR_ID,
        UpdateSavedReactionTags.CONSTRUCTOR_ID,
        UpdateSavedRingtones.CONSTRUCTOR_ID,
        UpdateSentPhoneCode.CONSTRUCTOR_ID,
        UpdateSentStoryReaction.CONSTRUCTOR_ID,
        UpdateServiceNotification.CONSTRUCTOR_ID,
        UpdateSmsJob.CONSTRUCTOR_ID,
        UpdateStarGiftAuctionState.CONSTRUCTOR_ID,
        UpdateStarGiftAuctionUserState.CONSTRUCTOR_ID,
        UpdateStarGiftCraftFail.CONSTRUCTOR_ID,
        UpdateStarsBalance.CONSTRUCTOR_ID,
        UpdateStarsRevenueStatus.CONSTRUCTOR_ID,
        UpdateStickerSets.CONSTRUCTOR_ID,
        UpdateStickerSetsOrder.CONSTRUCTOR_ID,
        UpdateStoriesStealthMode.CONSTRUCTOR_ID,
        UpdateStory.CONSTRUCTOR_ID,
        UpdateStoryId.CONSTRUCTOR_ID,
        UpdateTheme.CONSTRUCTOR_ID,
        UpdateTranscribedAudio.CONSTRUCTOR_ID,
        UpdateUser.CONSTRUCTOR_ID,
        UpdateUserEmojiStatus.CONSTRUCTOR_ID,
        UpdateUserName.CONSTRUCTOR_ID,
        UpdateUserPhone.CONSTRUCTOR_ID,
        UpdateUserStatus.CONSTRUCTOR_ID,
        UpdateUserTyping.CONSTRUCTOR_ID,
        UpdateWebViewResultSent.CONSTRUCTOR_ID
    )

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
