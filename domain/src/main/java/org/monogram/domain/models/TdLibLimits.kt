package org.monogram.domain.models

/**
 * Effective account limits reported by TDLib. A null value means that the
 * option is unavailable or has not been loaded for the current account.
 */
data class TdLibLimits(
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
         * Conservative non-Premium defaults used until TDLib returns the
         * account-specific values. They mirror the defaults in the bundled
         * TDLib OptionManager; the server value always wins after refresh.
         */
        val DEFAULTS = TdLibLimits(
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

    fun withOption(name: String, value: Int?): TdLibLimits {
        val effectiveValue = value ?: fallbackValue(name)
        return when (name) {
            TdLibLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX -> copy(messageTextLengthMax = effectiveValue)
            TdLibLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX -> copy(messageCaptionLengthMax = effectiveValue)
            TdLibLimitOptionNames.MESSAGE_REPLY_QUOTE_LENGTH_MAX -> copy(messageReplyQuoteLengthMax = effectiveValue)
            TdLibLimitOptionNames.STORY_CAPTION_LENGTH_MAX -> copy(storyCaptionLengthMax = effectiveValue)
            TdLibLimitOptionNames.BIO_LENGTH_MAX -> copy(bioLengthMax = effectiveValue)
            TdLibLimitOptionNames.BUSINESS_START_PAGE_TITLE_LENGTH_MAX -> copy(
                businessStartPageTitleLengthMax = effectiveValue
            )

            TdLibLimitOptionNames.BUSINESS_START_PAGE_MESSAGE_LENGTH_MAX -> copy(
                businessStartPageMessageLengthMax = effectiveValue
            )

            TdLibLimitOptionNames.FORWARDED_MESSAGE_COUNT_MAX -> copy(forwardedMessageCountMax = effectiveValue)
            TdLibLimitOptionNames.RICH_MESSAGE_TEXT_LENGTH_MAX -> copy(richMessageTextLengthMax = effectiveValue)
            TdLibLimitOptionNames.RICH_MESSAGE_BLOCK_COUNT_MAX -> copy(richMessageBlockCountMax = effectiveValue)
            TdLibLimitOptionNames.RICH_MESSAGE_DEPTH_MAX -> copy(richMessageDepthMax = effectiveValue)
            TdLibLimitOptionNames.RICH_MESSAGE_MEDIA_COUNT_MAX -> copy(richMessageMediaCountMax = effectiveValue)
            TdLibLimitOptionNames.RICH_MESSAGE_TABLE_COLUMN_COUNT_MAX -> copy(
                richMessageTableColumnCountMax = effectiveValue
            )

            TdLibLimitOptionNames.CHECKLIST_TASK_COUNT_MAX -> copy(checklistTaskCountMax = effectiveValue)
            TdLibLimitOptionNames.CHECKLIST_TASK_TEXT_LENGTH_MAX -> copy(checklistTaskTextLengthMax = effectiveValue)
            TdLibLimitOptionNames.CHECKLIST_TITLE_LENGTH_MAX -> copy(checklistTitleLengthMax = effectiveValue)
            TdLibLimitOptionNames.POLL_ANSWER_COUNT_MAX -> copy(pollAnswerCountMax = effectiveValue)
            TdLibLimitOptionNames.POLL_OPEN_PERIOD_MAX -> copy(pollOpenPeriodMax = effectiveValue)
            TdLibLimitOptionNames.CHAT_FOLDER_COUNT_MAX -> copy(chatFolderCountMax = effectiveValue)
            TdLibLimitOptionNames.CHAT_FOLDER_CHOSEN_CHAT_COUNT_MAX -> copy(
                chatFolderChosenChatCountMax = effectiveValue
            )

            TdLibLimitOptionNames.CHAT_FOLDER_INVITE_LINK_COUNT_MAX -> copy(
                chatFolderInviteLinkCountMax = effectiveValue
            )

            TdLibLimitOptionNames.PINNED_CHAT_COUNT_MAX -> copy(pinnedChatCountMax = effectiveValue)
            TdLibLimitOptionNames.PINNED_ARCHIVED_CHAT_COUNT_MAX -> copy(pinnedArchivedChatCountMax = effectiveValue)
            TdLibLimitOptionNames.PINNED_FORUM_TOPIC_COUNT_MAX -> copy(pinnedForumTopicCountMax = effectiveValue)
            TdLibLimitOptionNames.PINNED_SAVED_MESSAGES_TOPIC_COUNT_MAX -> copy(
                pinnedSavedMessagesTopicCountMax = effectiveValue
            )

            TdLibLimitOptionNames.ACTIVE_STORY_COUNT_MAX -> copy(activeStoryCountMax = effectiveValue)
            TdLibLimitOptionNames.WEEKLY_SENT_STORY_COUNT_MAX -> copy(weeklySentStoryCountMax = effectiveValue)
            TdLibLimitOptionNames.MONTHLY_SENT_STORY_COUNT_MAX -> copy(monthlySentStoryCountMax = effectiveValue)
            TdLibLimitOptionNames.STORY_LINK_AREA_COUNT_MAX -> copy(storyLinkAreaCountMax = effectiveValue)
            TdLibLimitOptionNames.STORY_SUGGESTED_REACTION_AREA_COUNT_MAX -> copy(
                storySuggestedReactionAreaCountMax = effectiveValue
            )

            TdLibLimitOptionNames.STORY_STEALTH_MODE_COOLDOWN_PERIOD -> copy(
                storyStealthModeCooldownPeriod = effectiveValue
            )

            TdLibLimitOptionNames.STORY_STEALTH_MODE_FUTURE_PERIOD -> copy(
                storyStealthModeFuturePeriod = effectiveValue
            )

            TdLibLimitOptionNames.STORY_STEALTH_MODE_PAST_PERIOD -> copy(storyStealthModePastPeriod = effectiveValue)
            TdLibLimitOptionNames.STORY_VIEWERS_EXPIRATION_DELAY -> copy(storyViewersExpirationDelay = effectiveValue)
            TdLibLimitOptionNames.FAVORITE_STICKERS_LIMIT -> copy(favoriteStickersLimit = effectiveValue)
            TdLibLimitOptionNames.SAVED_ANIMATIONS_LIMIT -> copy(savedAnimationCountMax = effectiveValue)
            TdLibLimitOptionNames.NOTIFICATION_SOUND_COUNT_MAX -> copy(notificationSoundCountMax = effectiveValue)
            TdLibLimitOptionNames.NOTIFICATION_SOUND_DURATION_MAX -> copy(
                notificationSoundDurationMax = effectiveValue
            )

            TdLibLimitOptionNames.NOTIFICATION_SOUND_SIZE_MAX -> copy(notificationSoundSizeMax = effectiveValue)
            TdLibLimitOptionNames.GIFT_TEXT_LENGTH_MAX -> copy(giftTextLengthMax = effectiveValue)
            TdLibLimitOptionNames.USER_NOTE_TEXT_LENGTH_MAX -> copy(userNoteTextLengthMax = effectiveValue)
            TdLibLimitOptionNames.GROUP_CALL_MESSAGE_TEXT_LENGTH_MAX -> copy(
                groupCallMessageTextLengthMax = effectiveValue
            )

            else -> this
        }
    }

    private fun fallbackValue(name: String): Int? = when (name) {
        TdLibLimitOptionNames.MESSAGE_TEXT_LENGTH_MAX -> DEFAULTS.messageTextLengthMax
        TdLibLimitOptionNames.MESSAGE_CAPTION_LENGTH_MAX -> DEFAULTS.messageCaptionLengthMax
        TdLibLimitOptionNames.MESSAGE_REPLY_QUOTE_LENGTH_MAX -> DEFAULTS.messageReplyQuoteLengthMax
        TdLibLimitOptionNames.STORY_CAPTION_LENGTH_MAX -> DEFAULTS.storyCaptionLengthMax
        TdLibLimitOptionNames.BIO_LENGTH_MAX -> DEFAULTS.bioLengthMax
        TdLibLimitOptionNames.BUSINESS_START_PAGE_TITLE_LENGTH_MAX -> DEFAULTS.businessStartPageTitleLengthMax
        TdLibLimitOptionNames.BUSINESS_START_PAGE_MESSAGE_LENGTH_MAX -> DEFAULTS.businessStartPageMessageLengthMax
        TdLibLimitOptionNames.FORWARDED_MESSAGE_COUNT_MAX -> DEFAULTS.forwardedMessageCountMax
        TdLibLimitOptionNames.RICH_MESSAGE_TEXT_LENGTH_MAX -> DEFAULTS.richMessageTextLengthMax
        TdLibLimitOptionNames.RICH_MESSAGE_BLOCK_COUNT_MAX -> DEFAULTS.richMessageBlockCountMax
        TdLibLimitOptionNames.RICH_MESSAGE_DEPTH_MAX -> DEFAULTS.richMessageDepthMax
        TdLibLimitOptionNames.RICH_MESSAGE_MEDIA_COUNT_MAX -> DEFAULTS.richMessageMediaCountMax
        TdLibLimitOptionNames.RICH_MESSAGE_TABLE_COLUMN_COUNT_MAX -> DEFAULTS.richMessageTableColumnCountMax
        TdLibLimitOptionNames.CHECKLIST_TASK_COUNT_MAX -> DEFAULTS.checklistTaskCountMax
        TdLibLimitOptionNames.CHECKLIST_TASK_TEXT_LENGTH_MAX -> DEFAULTS.checklistTaskTextLengthMax
        TdLibLimitOptionNames.CHECKLIST_TITLE_LENGTH_MAX -> DEFAULTS.checklistTitleLengthMax
        TdLibLimitOptionNames.POLL_ANSWER_COUNT_MAX -> DEFAULTS.pollAnswerCountMax
        TdLibLimitOptionNames.POLL_OPEN_PERIOD_MAX -> DEFAULTS.pollOpenPeriodMax
        TdLibLimitOptionNames.CHAT_FOLDER_COUNT_MAX -> DEFAULTS.chatFolderCountMax
        TdLibLimitOptionNames.CHAT_FOLDER_CHOSEN_CHAT_COUNT_MAX -> DEFAULTS.chatFolderChosenChatCountMax
        TdLibLimitOptionNames.CHAT_FOLDER_INVITE_LINK_COUNT_MAX -> DEFAULTS.chatFolderInviteLinkCountMax
        TdLibLimitOptionNames.PINNED_CHAT_COUNT_MAX -> DEFAULTS.pinnedChatCountMax
        TdLibLimitOptionNames.PINNED_ARCHIVED_CHAT_COUNT_MAX -> DEFAULTS.pinnedArchivedChatCountMax
        TdLibLimitOptionNames.PINNED_FORUM_TOPIC_COUNT_MAX -> DEFAULTS.pinnedForumTopicCountMax
        TdLibLimitOptionNames.PINNED_SAVED_MESSAGES_TOPIC_COUNT_MAX -> DEFAULTS.pinnedSavedMessagesTopicCountMax
        TdLibLimitOptionNames.ACTIVE_STORY_COUNT_MAX -> DEFAULTS.activeStoryCountMax
        TdLibLimitOptionNames.WEEKLY_SENT_STORY_COUNT_MAX -> DEFAULTS.weeklySentStoryCountMax
        TdLibLimitOptionNames.MONTHLY_SENT_STORY_COUNT_MAX -> DEFAULTS.monthlySentStoryCountMax
        TdLibLimitOptionNames.STORY_LINK_AREA_COUNT_MAX -> DEFAULTS.storyLinkAreaCountMax
        TdLibLimitOptionNames.STORY_SUGGESTED_REACTION_AREA_COUNT_MAX -> DEFAULTS.storySuggestedReactionAreaCountMax
        TdLibLimitOptionNames.STORY_STEALTH_MODE_COOLDOWN_PERIOD -> DEFAULTS.storyStealthModeCooldownPeriod
        TdLibLimitOptionNames.STORY_STEALTH_MODE_FUTURE_PERIOD -> DEFAULTS.storyStealthModeFuturePeriod
        TdLibLimitOptionNames.STORY_STEALTH_MODE_PAST_PERIOD -> DEFAULTS.storyStealthModePastPeriod
        TdLibLimitOptionNames.STORY_VIEWERS_EXPIRATION_DELAY -> DEFAULTS.storyViewersExpirationDelay
        TdLibLimitOptionNames.FAVORITE_STICKERS_LIMIT -> DEFAULTS.favoriteStickersLimit
        TdLibLimitOptionNames.SAVED_ANIMATIONS_LIMIT -> DEFAULTS.savedAnimationCountMax
        TdLibLimitOptionNames.NOTIFICATION_SOUND_COUNT_MAX -> DEFAULTS.notificationSoundCountMax
        TdLibLimitOptionNames.NOTIFICATION_SOUND_DURATION_MAX -> DEFAULTS.notificationSoundDurationMax
        TdLibLimitOptionNames.NOTIFICATION_SOUND_SIZE_MAX -> DEFAULTS.notificationSoundSizeMax
        TdLibLimitOptionNames.GIFT_TEXT_LENGTH_MAX -> DEFAULTS.giftTextLengthMax
        TdLibLimitOptionNames.USER_NOTE_TEXT_LENGTH_MAX -> DEFAULTS.userNoteTextLengthMax
        TdLibLimitOptionNames.GROUP_CALL_MESSAGE_TEXT_LENGTH_MAX -> DEFAULTS.groupCallMessageTextLengthMax
        else -> null
    }
}
