package org.monogram.domain.models

/**
 * Effective account limits resolved from Telegram server configuration. A null value means that
 * the option is unavailable or has not been loaded for the current account.
 */
data class TelegramLimits(
    val messageTextLengthMax: Int? = null,
    val messageCaptionLengthMax: Int? = null,
    val messageReplyQuoteLengthMax: Int? = null,
    val storyCaptionLengthMax: Int? = null,
    val bioLengthMax: Int? = null,
    val businessStartPageTitleLengthMax: Int? = null,
    val businessStartPageMessageLengthMax: Int? = null,
    val forwardedMessageCountMax: Int? = null,

    val richMessageTextLengthMax: Int? = null,
    val richMessageBlockCountMax: Int? = null,
    val richMessageDepthMax: Int? = null,
    val richMessageMediaCountMax: Int? = null,
    val richMessageTableColumnCountMax: Int? = null,

    val checklistTaskCountMax: Int? = null,
    val checklistTaskTextLengthMax: Int? = null,
    val checklistTitleLengthMax: Int? = null,
    val pollAnswerCountMax: Int? = null,
    val pollOpenPeriodMax: Int? = null,

    val chatFolderCountMax: Int? = null,
    val chatFolderChosenChatCountMax: Int? = null,
    val chatFolderInviteLinkCountMax: Int? = null,
    val pinnedChatCountMax: Int? = null,
    val pinnedArchivedChatCountMax: Int? = null,
    val pinnedForumTopicCountMax: Int? = null,
    val pinnedSavedMessagesTopicCountMax: Int? = null,

    val activeStoryCountMax: Int? = null,
    val weeklySentStoryCountMax: Int? = null,
    val monthlySentStoryCountMax: Int? = null,
    val storyLinkAreaCountMax: Int? = null,
    val storySuggestedReactionAreaCountMax: Int? = null,
    val storyStealthModeCooldownPeriod: Int? = null,
    val storyStealthModeFuturePeriod: Int? = null,
    val storyStealthModePastPeriod: Int? = null,
    val storyViewersExpirationDelay: Int? = null,

    val favoriteStickersLimit: Int? = null,
    val savedAnimationCountMax: Int? = null,
    val notificationSoundCountMax: Int? = null,
    val notificationSoundDurationMax: Int? = null,
    val notificationSoundSizeMax: Int? = null,

    val giftTextLengthMax: Int? = null,
    val userNoteTextLengthMax: Int? = null,
    val groupCallMessageTextLengthMax: Int? = null
) {
    companion object {
        const val DEFAULT_MESSAGE_TEXT_LENGTH_MAX = 4096
        const val DEFAULT_MESSAGE_CAPTION_LENGTH_MAX = 1024
        const val DEFAULT_PREMIUM_MESSAGE_TEXT_LENGTH_MAX = 8192
        const val DEFAULT_PREMIUM_MESSAGE_CAPTION_LENGTH_MAX = 4096

        /**
         * Conservative non-Premium defaults used until Telegram returns the account-specific
         * values. They mirror Telegram's documented client defaults; the server value always wins
         * after refresh.
         */
        val DEFAULTS = TelegramLimits(
            messageTextLengthMax = DEFAULT_MESSAGE_TEXT_LENGTH_MAX,
            messageCaptionLengthMax = DEFAULT_MESSAGE_CAPTION_LENGTH_MAX,
            messageReplyQuoteLengthMax = 1024,
            storyCaptionLengthMax = 200,
            bioLengthMax = 70,
            businessStartPageTitleLengthMax = 32,
            businessStartPageMessageLengthMax = 70,
            forwardedMessageCountMax = 100,
            richMessageTextLengthMax = 32768,
            richMessageBlockCountMax = 500,
            richMessageDepthMax = 16,
            richMessageMediaCountMax = 50,
            richMessageTableColumnCountMax = 20,
            checklistTaskCountMax = 30,
            checklistTaskTextLengthMax = 100,
            checklistTitleLengthMax = 255,
            pollAnswerCountMax = 12,
            pollOpenPeriodMax = 730 * 3600,
            chatFolderCountMax = 10,
            chatFolderChosenChatCountMax = 100,
            chatFolderInviteLinkCountMax = 3,
            pinnedChatCountMax = 5,
            pinnedArchivedChatCountMax = 100,
            pinnedForumTopicCountMax = 5,
            pinnedSavedMessagesTopicCountMax = 5,
            activeStoryCountMax = 3,
            weeklySentStoryCountMax = 7,
            monthlySentStoryCountMax = 30,
            storyLinkAreaCountMax = 3,
            storySuggestedReactionAreaCountMax = 1,
            storyStealthModeCooldownPeriod = 3 * 3600,
            storyStealthModeFuturePeriod = 1500,
            storyStealthModePastPeriod = 300,
            storyViewersExpirationDelay = 86400,
            favoriteStickersLimit = 5,
            savedAnimationCountMax = 200,
            notificationSoundCountMax = 100,
            notificationSoundDurationMax = 5,
            notificationSoundSizeMax = 307200,
            giftTextLengthMax = 128,
            userNoteTextLengthMax = 128,
            groupCallMessageTextLengthMax = 128
        )
    }

    fun withOption(name: String, value: Int?): TelegramLimits {
        val effectiveValue = value ?: fallbackValue(name)
        return when (name) {
            TelegramLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX -> copy(messageTextLengthMax = effectiveValue)
            TelegramLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX -> copy(messageCaptionLengthMax = effectiveValue)
            TelegramLimitOptionNames.MESSAGE_REPLY_QUOTE_LENGTH_MAX -> copy(messageReplyQuoteLengthMax = effectiveValue)
            TelegramLimitOptionNames.STORY_CAPTION_LENGTH_MAX -> copy(storyCaptionLengthMax = effectiveValue)
            TelegramLimitOptionNames.BIO_LENGTH_MAX -> copy(bioLengthMax = effectiveValue)
            TelegramLimitOptionNames.BUSINESS_START_PAGE_TITLE_LENGTH_MAX -> copy(
                businessStartPageTitleLengthMax = effectiveValue
            )

            TelegramLimitOptionNames.BUSINESS_START_PAGE_MESSAGE_LENGTH_MAX -> copy(
                businessStartPageMessageLengthMax = effectiveValue
            )

            TelegramLimitOptionNames.FORWARDED_MESSAGE_COUNT_MAX -> copy(forwardedMessageCountMax = effectiveValue)
            TelegramLimitOptionNames.RICH_MESSAGE_TEXT_LENGTH_MAX -> copy(richMessageTextLengthMax = effectiveValue)
            TelegramLimitOptionNames.RICH_MESSAGE_BLOCK_COUNT_MAX -> copy(richMessageBlockCountMax = effectiveValue)
            TelegramLimitOptionNames.RICH_MESSAGE_DEPTH_MAX -> copy(richMessageDepthMax = effectiveValue)
            TelegramLimitOptionNames.RICH_MESSAGE_MEDIA_COUNT_MAX -> copy(richMessageMediaCountMax = effectiveValue)
            TelegramLimitOptionNames.RICH_MESSAGE_TABLE_COLUMN_COUNT_MAX -> copy(
                richMessageTableColumnCountMax = effectiveValue
            )

            TelegramLimitOptionNames.CHECKLIST_TASK_COUNT_MAX -> copy(checklistTaskCountMax = effectiveValue)
            TelegramLimitOptionNames.CHECKLIST_TASK_TEXT_LENGTH_MAX -> copy(checklistTaskTextLengthMax = effectiveValue)
            TelegramLimitOptionNames.CHECKLIST_TITLE_LENGTH_MAX -> copy(checklistTitleLengthMax = effectiveValue)
            TelegramLimitOptionNames.POLL_ANSWER_COUNT_MAX -> copy(pollAnswerCountMax = effectiveValue)
            TelegramLimitOptionNames.POLL_OPEN_PERIOD_MAX -> copy(pollOpenPeriodMax = effectiveValue)
            TelegramLimitOptionNames.CHAT_FOLDER_COUNT_MAX -> copy(chatFolderCountMax = effectiveValue)
            TelegramLimitOptionNames.CHAT_FOLDER_CHOSEN_CHAT_COUNT_MAX -> copy(
                chatFolderChosenChatCountMax = effectiveValue
            )

            TelegramLimitOptionNames.CHAT_FOLDER_INVITE_LINK_COUNT_MAX -> copy(
                chatFolderInviteLinkCountMax = effectiveValue
            )

            TelegramLimitOptionNames.PINNED_CHAT_COUNT_MAX -> copy(pinnedChatCountMax = effectiveValue)
            TelegramLimitOptionNames.PINNED_ARCHIVED_CHAT_COUNT_MAX -> copy(pinnedArchivedChatCountMax = effectiveValue)
            TelegramLimitOptionNames.PINNED_FORUM_TOPIC_COUNT_MAX -> copy(pinnedForumTopicCountMax = effectiveValue)
            TelegramLimitOptionNames.PINNED_SAVED_MESSAGES_TOPIC_COUNT_MAX -> copy(
                pinnedSavedMessagesTopicCountMax = effectiveValue
            )

            TelegramLimitOptionNames.ACTIVE_STORY_COUNT_MAX -> copy(activeStoryCountMax = effectiveValue)
            TelegramLimitOptionNames.WEEKLY_SENT_STORY_COUNT_MAX -> copy(weeklySentStoryCountMax = effectiveValue)
            TelegramLimitOptionNames.MONTHLY_SENT_STORY_COUNT_MAX -> copy(monthlySentStoryCountMax = effectiveValue)
            TelegramLimitOptionNames.STORY_LINK_AREA_COUNT_MAX -> copy(storyLinkAreaCountMax = effectiveValue)
            TelegramLimitOptionNames.STORY_SUGGESTED_REACTION_AREA_COUNT_MAX -> copy(
                storySuggestedReactionAreaCountMax = effectiveValue
            )

            TelegramLimitOptionNames.STORY_STEALTH_MODE_COOLDOWN_PERIOD -> copy(
                storyStealthModeCooldownPeriod = effectiveValue
            )

            TelegramLimitOptionNames.STORY_STEALTH_MODE_FUTURE_PERIOD -> copy(
                storyStealthModeFuturePeriod = effectiveValue
            )

            TelegramLimitOptionNames.STORY_STEALTH_MODE_PAST_PERIOD -> copy(storyStealthModePastPeriod = effectiveValue)
            TelegramLimitOptionNames.STORY_VIEWERS_EXPIRATION_DELAY -> copy(storyViewersExpirationDelay = effectiveValue)
            TelegramLimitOptionNames.FAVORITE_STICKERS_LIMIT -> copy(favoriteStickersLimit = effectiveValue)
            TelegramLimitOptionNames.SAVED_ANIMATIONS_LIMIT -> copy(savedAnimationCountMax = effectiveValue)
            TelegramLimitOptionNames.NOTIFICATION_SOUND_COUNT_MAX -> copy(notificationSoundCountMax = effectiveValue)
            TelegramLimitOptionNames.NOTIFICATION_SOUND_DURATION_MAX -> copy(
                notificationSoundDurationMax = effectiveValue
            )

            TelegramLimitOptionNames.NOTIFICATION_SOUND_SIZE_MAX -> copy(notificationSoundSizeMax = effectiveValue)
            TelegramLimitOptionNames.GIFT_TEXT_LENGTH_MAX -> copy(giftTextLengthMax = effectiveValue)
            TelegramLimitOptionNames.USER_NOTE_TEXT_LENGTH_MAX -> copy(userNoteTextLengthMax = effectiveValue)
            TelegramLimitOptionNames.GROUP_CALL_MESSAGE_TEXT_LENGTH_MAX -> copy(
                groupCallMessageTextLengthMax = effectiveValue
            )

            else -> this
        }
    }

    private fun fallbackValue(name: String): Int? = when (name) {
        TelegramLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX -> DEFAULTS.messageTextLengthMax
        TelegramLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX -> DEFAULTS.messageCaptionLengthMax
        TelegramLimitOptionNames.MESSAGE_REPLY_QUOTE_LENGTH_MAX -> DEFAULTS.messageReplyQuoteLengthMax
        TelegramLimitOptionNames.STORY_CAPTION_LENGTH_MAX -> DEFAULTS.storyCaptionLengthMax
        TelegramLimitOptionNames.BIO_LENGTH_MAX -> DEFAULTS.bioLengthMax
        TelegramLimitOptionNames.BUSINESS_START_PAGE_TITLE_LENGTH_MAX -> DEFAULTS.businessStartPageTitleLengthMax
        TelegramLimitOptionNames.BUSINESS_START_PAGE_MESSAGE_LENGTH_MAX -> DEFAULTS.businessStartPageMessageLengthMax
        TelegramLimitOptionNames.FORWARDED_MESSAGE_COUNT_MAX -> DEFAULTS.forwardedMessageCountMax
        TelegramLimitOptionNames.RICH_MESSAGE_TEXT_LENGTH_MAX -> DEFAULTS.richMessageTextLengthMax
        TelegramLimitOptionNames.RICH_MESSAGE_BLOCK_COUNT_MAX -> DEFAULTS.richMessageBlockCountMax
        TelegramLimitOptionNames.RICH_MESSAGE_DEPTH_MAX -> DEFAULTS.richMessageDepthMax
        TelegramLimitOptionNames.RICH_MESSAGE_MEDIA_COUNT_MAX -> DEFAULTS.richMessageMediaCountMax
        TelegramLimitOptionNames.RICH_MESSAGE_TABLE_COLUMN_COUNT_MAX -> DEFAULTS.richMessageTableColumnCountMax
        TelegramLimitOptionNames.CHECKLIST_TASK_COUNT_MAX -> DEFAULTS.checklistTaskCountMax
        TelegramLimitOptionNames.CHECKLIST_TASK_TEXT_LENGTH_MAX -> DEFAULTS.checklistTaskTextLengthMax
        TelegramLimitOptionNames.CHECKLIST_TITLE_LENGTH_MAX -> DEFAULTS.checklistTitleLengthMax
        TelegramLimitOptionNames.POLL_ANSWER_COUNT_MAX -> DEFAULTS.pollAnswerCountMax
        TelegramLimitOptionNames.POLL_OPEN_PERIOD_MAX -> DEFAULTS.pollOpenPeriodMax
        TelegramLimitOptionNames.CHAT_FOLDER_COUNT_MAX -> DEFAULTS.chatFolderCountMax
        TelegramLimitOptionNames.CHAT_FOLDER_CHOSEN_CHAT_COUNT_MAX -> DEFAULTS.chatFolderChosenChatCountMax
        TelegramLimitOptionNames.CHAT_FOLDER_INVITE_LINK_COUNT_MAX -> DEFAULTS.chatFolderInviteLinkCountMax
        TelegramLimitOptionNames.PINNED_CHAT_COUNT_MAX -> DEFAULTS.pinnedChatCountMax
        TelegramLimitOptionNames.PINNED_ARCHIVED_CHAT_COUNT_MAX -> DEFAULTS.pinnedArchivedChatCountMax
        TelegramLimitOptionNames.PINNED_FORUM_TOPIC_COUNT_MAX -> DEFAULTS.pinnedForumTopicCountMax
        TelegramLimitOptionNames.PINNED_SAVED_MESSAGES_TOPIC_COUNT_MAX -> DEFAULTS.pinnedSavedMessagesTopicCountMax
        TelegramLimitOptionNames.ACTIVE_STORY_COUNT_MAX -> DEFAULTS.activeStoryCountMax
        TelegramLimitOptionNames.WEEKLY_SENT_STORY_COUNT_MAX -> DEFAULTS.weeklySentStoryCountMax
        TelegramLimitOptionNames.MONTHLY_SENT_STORY_COUNT_MAX -> DEFAULTS.monthlySentStoryCountMax
        TelegramLimitOptionNames.STORY_LINK_AREA_COUNT_MAX -> DEFAULTS.storyLinkAreaCountMax
        TelegramLimitOptionNames.STORY_SUGGESTED_REACTION_AREA_COUNT_MAX -> DEFAULTS.storySuggestedReactionAreaCountMax
        TelegramLimitOptionNames.STORY_STEALTH_MODE_COOLDOWN_PERIOD -> DEFAULTS.storyStealthModeCooldownPeriod
        TelegramLimitOptionNames.STORY_STEALTH_MODE_FUTURE_PERIOD -> DEFAULTS.storyStealthModeFuturePeriod
        TelegramLimitOptionNames.STORY_STEALTH_MODE_PAST_PERIOD -> DEFAULTS.storyStealthModePastPeriod
        TelegramLimitOptionNames.STORY_VIEWERS_EXPIRATION_DELAY -> DEFAULTS.storyViewersExpirationDelay
        TelegramLimitOptionNames.FAVORITE_STICKERS_LIMIT -> DEFAULTS.favoriteStickersLimit
        TelegramLimitOptionNames.SAVED_ANIMATIONS_LIMIT -> DEFAULTS.savedAnimationCountMax
        TelegramLimitOptionNames.NOTIFICATION_SOUND_COUNT_MAX -> DEFAULTS.notificationSoundCountMax
        TelegramLimitOptionNames.NOTIFICATION_SOUND_DURATION_MAX -> DEFAULTS.notificationSoundDurationMax
        TelegramLimitOptionNames.NOTIFICATION_SOUND_SIZE_MAX -> DEFAULTS.notificationSoundSizeMax
        TelegramLimitOptionNames.GIFT_TEXT_LENGTH_MAX -> DEFAULTS.giftTextLengthMax
        TelegramLimitOptionNames.USER_NOTE_TEXT_LENGTH_MAX -> DEFAULTS.userNoteTextLengthMax
        TelegramLimitOptionNames.GROUP_CALL_MESSAGE_TEXT_LENGTH_MAX -> DEFAULTS.groupCallMessageTextLengthMax
        else -> null
    }
}
