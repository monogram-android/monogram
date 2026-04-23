package org.monogram.data.mapper.message

import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.data.mapper.SenderNameResolver
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.ServiceEmphasis
import org.monogram.domain.models.ServiceKind
import org.monogram.domain.repository.StringProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ServiceMessageFormatter(
    private val stringProvider: StringProvider,
    private val cache: ChatCache
) {
    fun format(content: TdApi.MessageContent, context: ContentMappingContext): MessageContent.Service? {
        val senderName = context.senderName.ifBlank { stringProvider.getString("unknown_user") }
        return when (content) {
            is TdApi.MessageCall -> service(
                text = formatCall(content),
                kind = ServiceKind.CALL,
                emphasis = callEmphasis(content)
            )
            is TdApi.MessageGroupCall -> service(
                text = formatGroupCall(content),
                kind = ServiceKind.CALL,
                emphasis = if (content.wasMissed) ServiceEmphasis.WARNING else ServiceEmphasis.NEUTRAL
            )
            is TdApi.MessageVideoChatScheduled -> service(
                stringProvider.getString("service_message_video_chat_scheduled", formatDateTime(content.startDate)),
                ServiceKind.CALL
            )

            is TdApi.MessageVideoChatStarted -> service(
                stringProvider.getString("service_message_video_chat_started"),
                ServiceKind.CALL
            )

            is TdApi.MessageVideoChatEnded -> service(
                stringProvider.getString("service_message_video_chat_ended", formatDuration(content.duration)),
                ServiceKind.CALL
            )

            is TdApi.MessageInviteVideoChatParticipants -> service(formatInvitedUsers(content.userIds), ServiceKind.CALL)

            is TdApi.MessagePollOptionAdded -> service(
                stringProvider.getString("service_message_poll_option_added", content.text.text),
                ServiceKind.SYSTEM
            )

            is TdApi.MessagePollOptionDeleted -> service(
                stringProvider.getString("service_message_poll_option_deleted", content.text.text),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageBasicGroupChatCreate -> service(
                stringProvider.getString("service_message_basic_group_created", content.title),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageSupergroupChatCreate -> service(
                stringProvider.getString("service_message_supergroup_created", content.title),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatChangeTitle -> service(
                stringProvider.getString("service_message_chat_title_changed", senderName, content.title),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatChangePhoto -> service(
                stringProvider.getString("service_message_chat_photo_changed", senderName),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatDeletePhoto -> service(
                stringProvider.getString("service_message_chat_photo_removed", senderName),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatOwnerLeft -> service(
                if (content.newOwnerUserId != 0L) {
                    stringProvider.getString(
                        "service_message_chat_owner_left_with_new_owner",
                        resolveUserName(content.newOwnerUserId)
                    )
                } else {
                    stringProvider.getString("service_message_chat_owner_left")
                },
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatOwnerChanged -> service(
                if (content.newOwnerUserId != 0L) {
                    stringProvider.getString(
                        "service_message_chat_owner_changed_to",
                        resolveUserName(content.newOwnerUserId)
                    )
                } else {
                    stringProvider.getString("service_message_chat_owner_changed")
                },
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatHasProtectedContentToggled -> service(
                stringProvider.getString(
                    if (content.newHasProtectedContent) {
                        "service_message_protected_content_enabled"
                    } else {
                        "service_message_protected_content_disabled"
                    }
                ),
                ServiceKind.SECURITY
            )

            is TdApi.MessageChatHasProtectedContentDisableRequested -> service(
                stringProvider.getString(
                    if (content.isExpired) {
                        "service_message_protected_content_disable_expired"
                    } else {
                        "service_message_protected_content_disable_requested"
                    }
                ),
                ServiceKind.SECURITY
            )

            is TdApi.MessageChatAddMembers -> service(formatAddedMembers(content, context, senderName), ServiceKind.MEMBERSHIP)

            is TdApi.MessageChatJoinByLink -> service(
                stringProvider.getString("service_message_joined_by_link", senderName),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatJoinByRequest -> service(
                stringProvider.getString("service_message_joined_by_request", senderName),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatDeleteMember -> service(formatDeleteMember(content, context, senderName), ServiceKind.MEMBERSHIP)

            is TdApi.MessageChatUpgradeTo -> service(
                stringProvider.getString("service_message_chat_upgraded", senderName),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageChatUpgradeFrom -> service(
                stringProvider.getString("service_message_group_migrated"),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessagePinMessage -> service(
                text = stringProvider.getString("service_message_pinned", senderName),
                kind = ServiceKind.SYSTEM,
                subtitle = content.messageId.takeIf { it != 0L }?.let { describeMessageForPin(context.chatId, it) }
            )

            is TdApi.MessageScreenshotTaken -> service(
                stringProvider.getString("service_message_screenshot_taken", senderName),
                ServiceKind.SECURITY,
                emphasis = ServiceEmphasis.WARNING
            )

            is TdApi.MessageChatSetBackground -> service(
                stringProvider.getString(
                    if (content.onlyForSelf) {
                        "service_message_background_changed_for_self"
                    } else {
                        "service_message_background_changed"
                    }
                ),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageChatSetTheme -> service(formatChatTheme(content.theme), ServiceKind.SYSTEM)

            is TdApi.MessageChatSetMessageAutoDeleteTime -> service(formatAutoDelete(content), ServiceKind.SECURITY)

            is TdApi.MessageChatBoost -> service(
                stringProvider.getString("service_message_chat_boost", content.boostCount),
                ServiceKind.GIFT
            )

            is TdApi.MessageForumTopicCreated -> service(
                stringProvider.getString("service_message_topic_created", senderName, content.name),
                ServiceKind.FORUM
            )

            is TdApi.MessageForumTopicEdited -> service(formatForumTopicEdited(content, senderName), ServiceKind.FORUM)

            is TdApi.MessageForumTopicIsClosedToggled -> service(
                stringProvider.getString(
                    if (content.isClosed) {
                        "service_message_topic_closed"
                    } else {
                        "service_message_topic_reopened"
                    },
                    senderName
                ),
                ServiceKind.FORUM
            )

            is TdApi.MessageForumTopicIsHiddenToggled -> service(
                stringProvider.getString(
                    if (content.isHidden) {
                        "service_message_topic_hidden"
                    } else {
                        "service_message_topic_visible"
                    },
                    senderName
                ),
                ServiceKind.FORUM
            )

            is TdApi.MessageSuggestProfilePhoto -> service(
                stringProvider.getString("service_message_profile_photo_suggested", senderName),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageSuggestBirthdate -> service(
                stringProvider.getString("service_message_birthdate_suggested", senderName),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageCustomServiceAction -> service(content.text, ServiceKind.SYSTEM)

            is TdApi.MessageGameScore -> service(
                stringProvider.getString("service_message_game_score", content.score),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageManagedBotCreated -> service(
                stringProvider.getString("service_message_managed_bot_created"),
                ServiceKind.BOT
            )

            is TdApi.MessagePaymentSuccessful -> service(
                formatPaymentSuccessful(content),
                ServiceKind.PAYMENT,
                emphasis = ServiceEmphasis.SUCCESS
            )

            is TdApi.MessagePaymentSuccessfulBot -> service(
                formatPaymentSuccessfulBot(content),
                ServiceKind.PAYMENT,
                emphasis = ServiceEmphasis.SUCCESS
            )

            is TdApi.MessagePaymentRefunded -> service(
                formatPaymentRefunded(content),
                ServiceKind.PAYMENT,
                emphasis = ServiceEmphasis.WARNING
            )

            is TdApi.MessageGiftedPremium -> service(formatGiftedPremium(content), ServiceKind.GIFT)

            is TdApi.MessagePremiumGiftCode -> service(formatPremiumGiftCode(content), ServiceKind.GIFT)

            is TdApi.MessageGiveawayCreated -> service(
                stringProvider.getString(
                    if (content.starCount > 0) {
                        "service_message_stars_giveaway_created"
                    } else {
                        "service_message_giveaway_created"
                    }
                ),
                ServiceKind.GIFT
            )

            is TdApi.MessageGiveaway -> service(formatGiveaway(content), ServiceKind.GIFT)

            is TdApi.MessageGiveawayCompleted -> service(formatGiveawayCompleted(content), ServiceKind.GIFT)

            is TdApi.MessageGiveawayWinners -> service(formatGiveawayWinners(content), ServiceKind.GIFT)

            is TdApi.MessageGiftedStars -> service(formatGiftedStars(content), ServiceKind.GIFT)

            is TdApi.MessageGiftedTon -> service(formatGiftedTon(content), ServiceKind.GIFT)

            is TdApi.MessageGiveawayPrizeStars -> service(formatGiveawayPrizeStars(content), ServiceKind.GIFT)

            is TdApi.MessageGift -> service(formatGift(content), ServiceKind.GIFT)

            is TdApi.MessageUpgradedGift -> service(
                stringProvider.getString(
                    if (content.wasTransferred) "service_message_gift_upgraded_transferred" else "service_message_gift_upgraded"
                ),
                ServiceKind.GIFT
            )

            is TdApi.MessageRefundedUpgradedGift -> service(
                stringProvider.getString("service_message_gift_refunded"),
                ServiceKind.GIFT
            )

            is TdApi.MessageUpgradedGiftPurchaseOffer -> service(formatGiftPurchaseOffer(content), ServiceKind.GIFT)

            is TdApi.MessageUpgradedGiftPurchaseOfferRejected -> service(
                formatGiftPurchaseOfferRejected(content),
                ServiceKind.GIFT,
                emphasis = ServiceEmphasis.WARNING
            )

            is TdApi.MessagePaidMessagesRefunded -> service(
                stringProvider.getString(
                    "service_message_paid_messages_refunded",
                    content.messageCount,
                    content.starCount
                ),
                ServiceKind.PAYMENT
            )

            is TdApi.MessagePaidMessagePriceChanged -> service(
                stringProvider.getString(
                    "service_message_paid_message_price_changed",
                    content.paidMessageStarCount
                ),
                ServiceKind.PAYMENT
            )

            is TdApi.MessageDirectMessagePriceChanged -> service(
                stringProvider.getString(
                    if (!content.isEnabled) {
                        "service_message_direct_messages_disabled"
                    } else {
                        "service_message_direct_message_price_changed"
                    },
                    content.paidMessageStarCount
                ),
                ServiceKind.PAYMENT
            )

            is TdApi.MessageChecklistTasksDone -> service(
                stringProvider.getString(
                    "service_message_checklist_tasks_updated",
                    content.markedAsDoneTaskIds.size,
                    content.markedAsNotDoneTaskIds.size
                ),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageChecklistTasksAdded -> service(
                stringProvider.getString("service_message_checklist_tasks_added", content.tasks.size),
                ServiceKind.SYSTEM
            )

            is TdApi.MessageSuggestedPostApprovalFailed -> service(
                stringProvider.getString(
                    "service_message_suggested_post_approval_failed_with_price",
                    formatSuggestedPrice(content.price)
                ),
                ServiceKind.PAYMENT,
                emphasis = ServiceEmphasis.WARNING
            )

            is TdApi.MessageSuggestedPostApproved -> service(formatSuggestedPostApproved(content), ServiceKind.SYSTEM)

            is TdApi.MessageSuggestedPostDeclined -> service(
                if (content.comment.isNotBlank()) {
                    stringProvider.getString(
                        "service_message_suggested_post_declined_with_comment",
                        sanitizeInline(content.comment)
                    )
                } else {
                    stringProvider.getString("service_message_suggested_post_declined")
                },
                ServiceKind.SYSTEM,
                emphasis = ServiceEmphasis.WARNING
            )

            is TdApi.MessageSuggestedPostPaid -> service(formatSuggestedPostPaid(content), ServiceKind.PAYMENT)

            is TdApi.MessageSuggestedPostRefunded -> service(
                formatSuggestedPostRefunded(content),
                ServiceKind.PAYMENT,
                emphasis = ServiceEmphasis.WARNING
            )

            is TdApi.MessageContactRegistered -> service(
                stringProvider.getString("service_message_contact_registered", senderName),
                ServiceKind.MEMBERSHIP
            )

            is TdApi.MessageUsersShared -> service(formatSharedUsers(content), ServiceKind.BOT)

            is TdApi.MessageChatShared -> service(formatSharedChat(content), ServiceKind.BOT)

            is TdApi.MessageBotWriteAccessAllowed -> service(formatBotWriteAccessReason(content.reason), ServiceKind.BOT)

            is TdApi.MessageWebAppDataSent -> service(
                stringProvider.getString(
                    "service_message_web_app_data_sent",
                    content.buttonText.takeIf { it.isNotBlank() } ?: stringProvider.getString("mini_app_default_name")
                ),
                ServiceKind.BOT
            )

            is TdApi.MessageWebAppDataReceived -> service(
                stringProvider.getString(
                    "service_message_web_app_data_received_with_size",
                    content.buttonText.takeIf { it.isNotBlank() } ?: stringProvider.getString("mini_app_default_name"),
                    content.data.length
                ),
                ServiceKind.BOT
            )

            is TdApi.MessagePassportDataSent -> service(
                stringProvider.getString("service_message_passport_data_sent"),
                ServiceKind.SECURITY
            )

            is TdApi.MessagePassportDataReceived -> service(
                stringProvider.getString("service_message_passport_data_received"),
                ServiceKind.SECURITY
            )

            is TdApi.MessageProximityAlertTriggered -> service(
                stringProvider.getString("service_message_proximity_alert", content.distance),
                ServiceKind.SECURITY
            )

            else -> null
        }
    }

    private fun service(
        text: String,
        kind: ServiceKind,
        subtitle: String? = null,
        emphasis: ServiceEmphasis = ServiceEmphasis.NEUTRAL
    ) = MessageContent.Service(
        text = text,
        kind = kind,
        subtitle = subtitle,
        emphasis = emphasis
    )

    private fun formatAddedMembers(
        content: TdApi.MessageChatAddMembers,
        context: ContentMappingContext,
        senderName: String
    ): String {
        val memberIds = content.memberUserIds.toList()
        if (memberIds.isEmpty()) {
            return stringProvider.getString("service_message_members_added", senderName, 0)
        }

        if (memberIds.size == 1) {
            val memberId = memberIds.first()
            val memberName = resolveUserName(memberId)
            return if (memberId == context.senderId) {
                stringProvider.getString("service_message_member_joined", memberName)
            } else {
                stringProvider.getString("service_message_member_added_named", senderName, memberName)
            }
        }

        val names = memberIds.map(::resolveUserName)
        val firstTwo = names.take(2)
        return if (names.size == 2) {
            stringProvider.getString(
                "service_message_members_added_two_named",
                senderName,
                firstTwo[0],
                firstTwo[1]
            )
        } else {
            val remaining = names.size - 2
            stringProvider.getString(
                "service_message_members_added_many_named",
                senderName,
                firstTwo[0],
                firstTwo[1],
                remaining
            )
        }
    }

    private fun formatDeleteMember(
        content: TdApi.MessageChatDeleteMember,
        context: ContentMappingContext,
        senderName: String
    ): String {
        val removedUserId = content.userId
        if (removedUserId == 0L) {
            return stringProvider.getString("service_message_member_removed", senderName)
        }

        val removedName = resolveUserName(removedUserId)
        return if (removedUserId == context.senderId) {
            stringProvider.getString("service_message_member_left_named", removedName)
        } else {
            stringProvider.getString("service_message_member_removed_named", senderName, removedName)
        }
    }

    private fun describeMessageForPin(chatId: Long, messageId: Long): String {
        val pinned = cache.getMessage(chatId, messageId)
            ?: return stringProvider.getString("chat_mapper_message")
        return when (val pinnedContent = pinned.content) {
            is TdApi.MessageText -> sanitizeInline(pinnedContent.text.text)
                .ifBlank { stringProvider.getString("chat_mapper_message") }

            is TdApi.MessagePhoto -> sanitizeInline(pinnedContent.caption.text)
                .ifBlank { stringProvider.getString("chat_mapper_photo") }

            is TdApi.MessageVideo -> sanitizeInline(pinnedContent.caption.text)
                .ifBlank { stringProvider.getString("chat_mapper_video") }

            is TdApi.MessageVoiceNote -> stringProvider.getString("chat_mapper_voice")
            is TdApi.MessageVideoNote -> stringProvider.getString("chat_mapper_video_note")
            is TdApi.MessageAnimation -> sanitizeInline(pinnedContent.caption.text)
                .ifBlank { stringProvider.getString("chat_mapper_gif") }

            is TdApi.MessageSticker -> {
                val emoji = pinnedContent.sticker.emoji.orEmpty().ifBlank { "" }
                if (emoji.isBlank()) {
                    stringProvider.getString("chat_mapper_sticker")
                } else {
                    stringProvider.getString("message_type_sticker_format", emoji)
                }
            }

            is TdApi.MessageDocument -> sanitizeInline(pinnedContent.caption.text)
                .ifBlank {
                    pinnedContent.document.fileName.takeIf { it.isNotBlank() }
                        ?: stringProvider.getString("chat_mapper_document")
                }

            is TdApi.MessageAudio -> sanitizeInline(pinnedContent.caption.text)
                .ifBlank {
                    pinnedContent.audio.title.takeIf { it.isNotBlank() }
                        ?: stringProvider.getString("chat_mapper_audio")
                }

            is TdApi.MessagePoll -> stringProvider.getString(
                "message_type_poll_format",
                sanitizeInline(pinnedContent.poll.question.text)
            )

            is TdApi.MessageContact -> stringProvider.getString("chat_mapper_contact")
            is TdApi.MessageLocation -> stringProvider.getString("chat_mapper_location")
            is TdApi.MessageGame -> stringProvider.getString("chat_mapper_game")
            is TdApi.MessageInvoice -> stringProvider.getString("chat_mapper_invoice")
            is TdApi.MessageStory -> stringProvider.getString("chat_mapper_story")
            else -> stringProvider.getString("chat_mapper_message")
        }
    }

    private fun sanitizeInline(raw: String): String {
        return raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(64)
    }

    private fun resolveUserName(userId: Long): String {
        val user = cache.getUser(userId)
        if (user != null) {
            return SenderNameResolver.fromParts(
                firstName = user.firstName,
                lastName = user.lastName,
                fallback = stringProvider.getString("unknown_user")
            )
        }
        return stringProvider.getString("unknown_user")
    }

    private fun resolveChatName(chatId: Long): String {
        if (chatId == 0L) return ""
        val chat = cache.getChat(chatId) ?: return ""
        return chat.title.takeIf { it.isNotBlank() } ?: ""
    }

    private fun resolveMessageSenderName(sender: TdApi.MessageSender?): String {
        return when (sender) {
            is TdApi.MessageSenderUser -> resolveUserName(sender.userId)
            is TdApi.MessageSenderChat -> resolveChatName(sender.chatId)
            else -> ""
        }
    }

    private fun formatCall(content: TdApi.MessageCall): String {
        val duration = formatDuration(content.duration)
        return when (content.discardReason) {
            is TdApi.CallDiscardReasonMissed -> stringProvider.getString(
                if (content.isVideo) "service_message_video_call_missed" else "service_message_call_missed"
            )

            is TdApi.CallDiscardReasonDeclined -> stringProvider.getString(
                if (content.isVideo) "service_message_video_call_declined" else "service_message_call_declined"
            )

            is TdApi.CallDiscardReasonDisconnected -> stringProvider.getString(
                if (content.isVideo) "service_message_video_call_disconnected" else "service_message_call_disconnected"
            )

            is TdApi.CallDiscardReasonHungUp -> stringProvider.getString(
                if (content.isVideo) "service_message_video_call_hung_up" else "service_message_call_hung_up"
            )

            is TdApi.CallDiscardReasonUpgradeToGroupCall -> stringProvider.getString("service_message_call_upgraded_to_group")

            is TdApi.CallDiscardReasonEmpty -> stringProvider.getString(
                if (content.isVideo) "service_message_video_call" else "service_message_call"
            )

            else -> stringProvider.getString(
                if (content.isVideo) "service_message_video_call_ended" else "service_message_call_ended",
                duration
            )
        }
    }

    private fun callEmphasis(content: TdApi.MessageCall): ServiceEmphasis {
        return when (content.discardReason) {
            is TdApi.CallDiscardReasonMissed,
            is TdApi.CallDiscardReasonDeclined,
            is TdApi.CallDiscardReasonDisconnected -> ServiceEmphasis.WARNING
            else -> ServiceEmphasis.NEUTRAL
        }
    }

    private fun formatGroupCall(content: TdApi.MessageGroupCall): String {
        return when {
            content.wasMissed -> stringProvider.getString(
                if (content.isVideo) "service_message_group_video_call_missed" else "service_message_group_call_missed"
            )

            content.isActive -> stringProvider.getString(
                if (content.isVideo) "service_message_group_video_call_started" else "service_message_group_call_started"
            )

            content.duration > 0 -> stringProvider.getString(
                if (content.isVideo) "service_message_group_video_call_ended" else "service_message_group_call_ended",
                formatDuration(content.duration)
            )

            else -> stringProvider.getString(
                if (content.isVideo) "service_message_group_video_call" else "service_message_group_call"
            )
        }
    }

    private fun formatDateTime(timestamp: Int): String {
        if (timestamp <= 0) return stringProvider.getString("service_message_unknown_time")
        return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp.toLong() * 1000))
    }

    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainder = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, remainder)
    }

    private fun formatInvitedUsers(userIds: LongArray): String {
        if (userIds.isEmpty()) {
            return stringProvider.getString("service_message_video_chat_invited", 0)
        }
        val names = userIds.map(::resolveUserName)
        return when (names.size) {
            1 -> stringProvider.getString("service_message_video_chat_invited_one_named", names[0])
            2 -> stringProvider.getString("service_message_video_chat_invited_two_named", names[0], names[1])
            else -> stringProvider.getString(
                "service_message_video_chat_invited_many_named",
                names[0],
                names[1],
                names.size - 2
            )
        }
    }

    private fun formatForumTopicEdited(content: TdApi.MessageForumTopicEdited, senderName: String): String {
        return when {
            content.name.isNotBlank() && content.editIconCustomEmojiId -> stringProvider.getString(
                "service_message_topic_edited_name_and_icon",
                senderName,
                content.name
            )

            content.name.isNotBlank() -> stringProvider.getString(
                "service_message_topic_edited_name",
                senderName,
                content.name
            )

            content.editIconCustomEmojiId && content.iconCustomEmojiId != 0L -> stringProvider.getString(
                "service_message_topic_edited_icon",
                senderName
            )

            content.editIconCustomEmojiId -> stringProvider.getString(
                "service_message_topic_edited_icon_removed",
                senderName
            )

            else -> stringProvider.getString("service_message_topic_edited", senderName)
        }
    }

    private fun formatChatTheme(theme: TdApi.ChatTheme?): String {
        return when (theme) {
            null -> stringProvider.getString("service_message_theme_reset")
            is TdApi.ChatThemeEmoji -> {
                val name = theme.name.takeIf { it.isNotBlank() }
                if (name == null) stringProvider.getString("service_message_theme_changed")
                else stringProvider.getString("service_message_theme_changed_to", name)
            }

            is TdApi.ChatThemeGift -> stringProvider.getString("service_message_theme_changed_gift")
            else -> stringProvider.getString("service_message_theme_changed")
        }
    }

    private fun formatAutoDelete(content: TdApi.MessageChatSetMessageAutoDeleteTime): String {
        val base = if (content.messageAutoDeleteTime == 0) {
            stringProvider.getString("service_message_auto_delete_disabled")
        } else {
            stringProvider.getString(
                "service_message_auto_delete_enabled",
                formatAutoDeleteTime(content.messageAutoDeleteTime)
            )
        }
        if (content.fromUserId == 0L) return base
        return stringProvider.getString(
            "service_message_auto_delete_changed_by",
            base,
            resolveUserName(content.fromUserId)
        )
    }

    private fun formatPaymentSuccessful(content: TdApi.MessagePaymentSuccessful): String {
        val amount = formatMoney(content.currency, content.totalAmount)
        return when {
            content.isRecurring && content.isFirstRecurring -> stringProvider.getString(
                "service_message_payment_sent_first_recurring",
                amount
            )

            content.isRecurring -> stringProvider.getString("service_message_payment_sent_recurring", amount)
            content.invoiceName.isNotBlank() -> stringProvider.getString(
                "service_message_payment_sent_with_invoice",
                content.invoiceName,
                amount
            )

            else -> stringProvider.getString("service_message_payment_sent", amount)
        }
    }

    private fun formatPaymentSuccessfulBot(content: TdApi.MessagePaymentSuccessfulBot): String {
        val amount = formatMoney(content.currency, content.totalAmount)
        return if (content.isRecurring) {
            stringProvider.getString("service_message_payment_received_recurring", amount)
        } else {
            stringProvider.getString("service_message_payment_received", amount)
        }
    }

    private fun formatPaymentRefunded(content: TdApi.MessagePaymentRefunded): String {
        val amount = formatMoney(content.currency, content.totalAmount)
        val owner = resolveMessageSenderName(content.ownerId)
        return if (owner.isNotBlank()) {
            stringProvider.getString("service_message_payment_refunded_for", owner, amount)
        } else {
            stringProvider.getString("service_message_payment_refunded", amount)
        }
    }

    private fun formatGiftedPremium(content: TdApi.MessageGiftedPremium): String {
        val duration = formatPremiumDuration(content.monthCount, content.dayCount)
        val gifter = content.gifterUserId.takeIf { it != 0L }?.let(::resolveUserName)
        val receiver = content.receiverUserId.takeIf { it != 0L }?.let(::resolveUserName)
        return when {
            gifter != null && receiver != null -> stringProvider.getString(
                "service_message_premium_gifted_from_to",
                gifter,
                receiver,
                duration
            )

            receiver != null -> stringProvider.getString("service_message_premium_gifted_to", receiver, duration)
            else -> stringProvider.getString("service_message_premium_gifted", duration)
        }
    }

    private fun formatPremiumGiftCode(content: TdApi.MessagePremiumGiftCode): String {
        val duration = formatPremiumDuration(content.monthCount, content.dayCount)
        val creator = resolveMessageSenderName(content.creatorId)
        return when {
            content.isFromGiveaway && content.isUnclaimed -> stringProvider.getString(
                "service_message_premium_gift_code_unclaimed",
                duration
            )

            content.isFromGiveaway -> stringProvider.getString("service_message_premium_gift_code_from_giveaway", duration)
            creator.isNotBlank() -> stringProvider.getString("service_message_premium_gift_code_created_by", creator, duration)
            else -> stringProvider.getString("service_message_premium_gift_code_created")
        }
    }

    private fun formatGiveaway(content: TdApi.MessageGiveaway): String {
        return stringProvider.getString(
            "service_message_giveaway_with_prize",
            content.winnerCount,
            formatGiveawayPrize(content.prize)
        )
    }

    private fun formatGiveawayCompleted(content: TdApi.MessageGiveawayCompleted): String {
        return when {
            content.unclaimedPrizeCount > 0 -> stringProvider.getString(
                "service_message_giveaway_completed_with_unclaimed",
                content.winnerCount,
                content.unclaimedPrizeCount
            )

            content.isStarGiveaway -> stringProvider.getString("service_message_giveaway_completed_stars", content.winnerCount)
            else -> stringProvider.getString("service_message_giveaway_completed", content.winnerCount)
        }
    }

    private fun formatGiveawayWinners(content: TdApi.MessageGiveawayWinners): String {
        val boostedChat = resolveChatName(content.boostedChatId)
        return when {
            content.wasRefunded -> stringProvider.getString("service_message_giveaway_winners_refunded", content.winnerCount)
            boostedChat.isNotBlank() -> stringProvider.getString(
                "service_message_giveaway_winners_in_chat",
                content.winnerCount,
                boostedChat
            )

            else -> stringProvider.getString("service_message_giveaway_winners", content.winnerCount)
        }
    }

    private fun formatGiftedStars(content: TdApi.MessageGiftedStars): String {
        val gifter = content.gifterUserId.takeIf { it != 0L }?.let(::resolveUserName)
        val receiver = content.receiverUserId.takeIf { it != 0L }?.let(::resolveUserName)
        return when {
            gifter != null && receiver != null -> stringProvider.getString(
                "service_message_stars_gifted_from_to",
                gifter,
                receiver,
                content.starCount
            )

            receiver != null -> stringProvider.getString("service_message_stars_gifted_to", receiver, content.starCount)
            else -> stringProvider.getString("service_message_stars_gifted", content.starCount)
        }
    }

    private fun formatGiftedTon(content: TdApi.MessageGiftedTon): String {
        val gifter = content.gifterUserId.takeIf { it != 0L }?.let(::resolveUserName)
        val receiver = content.receiverUserId.takeIf { it != 0L }?.let(::resolveUserName)
        val amount = formatTonAmount(content.tonAmount)
        return when {
            gifter != null && receiver != null -> stringProvider.getString(
                "service_message_ton_gifted_from_to",
                gifter,
                receiver,
                amount
            )

            receiver != null -> stringProvider.getString("service_message_ton_gifted_to", receiver, amount)
            else -> stringProvider.getString("service_message_ton_gifted_amount", amount)
        }
    }

    private fun formatGiveawayPrizeStars(content: TdApi.MessageGiveawayPrizeStars): String {
        val boostedChat = resolveChatName(content.boostedChatId)
        return when {
            boostedChat.isNotBlank() && content.isUnclaimed -> stringProvider.getString(
                "service_message_giveaway_prize_stars_unclaimed_in_chat",
                content.starCount,
                boostedChat
            )

            boostedChat.isNotBlank() -> stringProvider.getString(
                "service_message_giveaway_prize_stars_in_chat",
                content.starCount,
                boostedChat
            )

            else -> stringProvider.getString("service_message_giveaway_prize_stars", content.starCount)
        }
    }

    private fun formatGift(content: TdApi.MessageGift): String {
        return when {
            content.wasRefunded -> stringProvider.getString("service_message_gift_refunded")
            content.wasUpgraded -> stringProvider.getString("service_message_gift_upgraded")
            content.isFromAuction -> stringProvider.getString("service_message_gift_from_auction")
            content.isPrivate -> stringProvider.getString("service_message_gift_private")
            else -> stringProvider.getString("service_message_gift_received")
        }
    }

    private fun formatGiftPurchaseOffer(content: TdApi.MessageUpgradedGiftPurchaseOffer): String {
        return stringProvider.getString(
            "service_message_gift_purchase_offer_with_price",
            formatGiftResalePrice(content.price),
            formatDateTime(content.expirationDate)
        )
    }

    private fun formatGiftPurchaseOfferRejected(content: TdApi.MessageUpgradedGiftPurchaseOfferRejected): String {
        val price = formatGiftResalePrice(content.price)
        return stringProvider.getString(
            if (content.wasExpired) {
                "service_message_gift_purchase_offer_expired_with_price"
            } else {
                "service_message_gift_purchase_offer_rejected_with_price"
            },
            price
        )
    }

    private fun formatSuggestedPostApproved(content: TdApi.MessageSuggestedPostApproved): String {
        val whenSend = formatDateTime(content.sendDate)
        val price = content.price?.let(::formatSuggestedPrice)
        return if (price != null) {
            stringProvider.getString("service_message_suggested_post_approved_with_price", whenSend, price)
        } else {
            stringProvider.getString("service_message_suggested_post_approved", whenSend)
        }
    }

    private fun formatSuggestedPostPaid(content: TdApi.MessageSuggestedPostPaid): String {
        val stars = content.starAmount.starCount
        return if (stars > 0) {
            stringProvider.getString("service_message_suggested_post_paid_stars", stars)
        } else if (content.tonAmount > 0) {
            stringProvider.getString("service_message_suggested_post_paid_ton", formatTonAmount(content.tonAmount))
        } else {
            stringProvider.getString("service_message_suggested_post_paid")
        }
    }

    private fun formatSuggestedPostRefunded(content: TdApi.MessageSuggestedPostRefunded): String {
        val reason = when (content.reason) {
            is TdApi.SuggestedPostRefundReasonPostDeleted -> stringProvider.getString("service_message_suggested_post_refund_reason_deleted")
            is TdApi.SuggestedPostRefundReasonPaymentRefunded -> stringProvider.getString("service_message_suggested_post_refund_reason_payment")
            else -> stringProvider.getString("service_message_suggested_post_refunded")
        }
        return stringProvider.getString("service_message_suggested_post_refunded_with_reason", reason)
    }

    private fun formatSharedUsers(content: TdApi.MessageUsersShared): String {
        val names = content.users.map { shared ->
            SenderNameResolver.fromParts(shared.firstName, shared.lastName, stringProvider.getString("unknown_user"))
        }.filter { it.isNotBlank() }
        return when {
            names.isEmpty() -> stringProvider.getString("service_message_users_shared", content.users.size)
            names.size == 1 -> stringProvider.getString("service_message_users_shared_one_named", names[0])
            names.size == 2 -> stringProvider.getString("service_message_users_shared_two_named", names[0], names[1])
            else -> stringProvider.getString("service_message_users_shared_many_named", names[0], names[1], names.size - 2)
        }
    }

    private fun formatSharedChat(content: TdApi.MessageChatShared): String {
        val title = content.chat.title.takeIf { it.isNotBlank() }
            ?: resolveChatName(content.chat.chatId)
        return if (title.isNotBlank()) {
            stringProvider.getString("service_message_chat_shared_named", title)
        } else {
            stringProvider.getString("service_message_chat_shared")
        }
    }

    private fun formatBotWriteAccessReason(reason: TdApi.BotWriteAccessAllowReason): String {
        return when (reason) {
            is TdApi.BotWriteAccessAllowReasonConnectedWebsite -> stringProvider.getString(
                "service_message_bot_write_access_connected_website",
                reason.domainName
            )

            is TdApi.BotWriteAccessAllowReasonAddedToAttachmentMenu -> stringProvider.getString(
                "service_message_bot_write_access_attachment_menu"
            )

            is TdApi.BotWriteAccessAllowReasonLaunchedWebApp -> {
                val appName = reason.webApp.title.takeIf { it.isNotBlank() }
                    ?: reason.webApp.shortName.takeIf { it.isNotBlank() }
                    ?: stringProvider.getString("mini_app_default_name")
                stringProvider.getString("service_message_bot_write_access_web_app", appName)
            }

            is TdApi.BotWriteAccessAllowReasonAcceptedRequest -> stringProvider.getString(
                "service_message_bot_write_access_accepted_request"
            )

            else -> stringProvider.getString("service_message_bot_write_access_allowed")
        }
    }

    private fun formatMoney(currency: String, amount: Long): String {
        return if (currency.isBlank()) {
            amount.toString()
        } else {
            stringProvider.getString("service_message_money_format", currency, amount)
        }
    }

    private fun formatTonAmount(tonAmountInNano: Long): String {
        if (tonAmountInNano <= 0L) return stringProvider.getString("service_message_ton_format", 0.0)
        val ton = tonAmountInNano / 1_000_000_000.0
        return stringProvider.getString("service_message_ton_format", ton)
    }

    private fun formatSuggestedPrice(price: TdApi.SuggestedPostPrice): String {
        return when (price) {
            is TdApi.SuggestedPostPriceStar -> stringProvider.getString("service_message_stars_format", price.starCount)
            is TdApi.SuggestedPostPriceTon -> stringProvider.getString("service_message_ton_format", price.toncoinCentCount / 100.0)
            else -> stringProvider.getString("service_message_unknown_price")
        }
    }

    private fun formatGiftResalePrice(price: TdApi.GiftResalePrice): String {
        return when (price) {
            is TdApi.GiftResalePriceStar -> stringProvider.getString("service_message_stars_format", price.starCount)
            is TdApi.GiftResalePriceTon -> stringProvider.getString("service_message_ton_format", price.toncoinCentCount / 100.0)
            else -> stringProvider.getString("service_message_unknown_price")
        }
    }

    private fun formatGiveawayPrize(prize: TdApi.GiveawayPrize): String {
        return when (prize) {
            is TdApi.GiveawayPrizeStars -> stringProvider.getString("service_message_stars_format", prize.starCount)
            is TdApi.GiveawayPrizePremium -> stringProvider.getQuantityString(
                "service_message_months",
                prize.monthCount,
                prize.monthCount
            )

            else -> stringProvider.getString("service_message_giveaway_prize_unknown")
        }
    }

    private fun formatPremiumDuration(monthCount: Int, dayCount: Int): String {
        return when {
            monthCount > 0 -> stringProvider.getQuantityString("service_message_months", monthCount, monthCount)
            dayCount > 0 -> stringProvider.getQuantityString("service_message_days", dayCount, dayCount)
            else -> stringProvider.getString("service_message_unknown_duration")
        }
    }

    private fun formatAutoDeleteTime(seconds: Int): String {
        if (seconds <= 0) return stringProvider.getString("service_message_auto_delete_off")
        return when {
            seconds % 86400 == 0 -> {
                val days = seconds / 86400
                stringProvider.getQuantityString("service_message_days", days, days)
            }

            seconds % 3600 == 0 -> {
                val hours = seconds / 3600
                stringProvider.getQuantityString("service_message_hours", hours, hours)
            }

            seconds % 60 == 0 -> {
                val minutes = seconds / 60
                stringProvider.getQuantityString("service_message_minutes", minutes, minutes)
            }

            else -> stringProvider.getQuantityString("service_message_seconds", seconds, seconds)
        }
    }
}
