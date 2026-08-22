package org.monogram.domain.models

/** Stable Telegram option names used by the limits integration. */
object TelegramLimitOptionNames {
    const val IS_PREMIUM = "is_premium"
    const val MESSAGE_TEXT_LENGTH_MAX = "message_text_length_max"
    const val MESSAGE_CAPTION_LENGTH_MAX = "message_caption_length_max"
    const val MESSAGE_REPLY_QUOTE_LENGTH_MAX = "message_reply_quote_length_max"
    const val STORY_CAPTION_LENGTH_MAX = "story_caption_length_max"
    const val BIO_LENGTH_MAX = "bio_length_max"
    const val BUSINESS_START_PAGE_TITLE_LENGTH_MAX = "business_start_page_title_length_max"
    const val BUSINESS_START_PAGE_MESSAGE_LENGTH_MAX = "business_start_page_message_length_max"
    const val FORWARDED_MESSAGE_COUNT_MAX = "forwarded_message_count_max"

    const val RICH_MESSAGE_TEXT_LENGTH_MAX = "rich_message_text_length_max"
    const val RICH_MESSAGE_BLOCK_COUNT_MAX = "rich_message_block_count_max"
    const val RICH_MESSAGE_DEPTH_MAX = "rich_message_depth_max"
    const val RICH_MESSAGE_MEDIA_COUNT_MAX = "rich_message_media_count_max"
    const val RICH_MESSAGE_TABLE_COLUMN_COUNT_MAX = "rich_message_table_column_count_max"

    const val CHECKLIST_TASK_COUNT_MAX = "checklist_task_count_max"
    const val CHECKLIST_TASK_TEXT_LENGTH_MAX = "checklist_task_text_length_max"
    const val CHECKLIST_TITLE_LENGTH_MAX = "checklist_title_length_max"
    const val POLL_ANSWER_COUNT_MAX = "poll_answer_count_max"
    const val POLL_OPEN_PERIOD_MAX = "poll_open_period_max"

    const val CHAT_FOLDER_COUNT_MAX = "chat_folder_count_max"
    const val CHAT_FOLDER_CHOSEN_CHAT_COUNT_MAX = "chat_folder_chosen_chat_count_max"
    const val CHAT_FOLDER_INVITE_LINK_COUNT_MAX = "chat_folder_invite_link_count_max"
    const val PINNED_CHAT_COUNT_MAX = "pinned_chat_count_max"
    const val PINNED_ARCHIVED_CHAT_COUNT_MAX = "pinned_archived_chat_count_max"
    const val PINNED_FORUM_TOPIC_COUNT_MAX = "pinned_forum_topic_count_max"
    const val PINNED_SAVED_MESSAGES_TOPIC_COUNT_MAX = "pinned_saved_messages_topic_count_max"

    const val ACTIVE_STORY_COUNT_MAX = "active_story_count_max"
    const val WEEKLY_SENT_STORY_COUNT_MAX = "weekly_sent_story_count_max"
    const val MONTHLY_SENT_STORY_COUNT_MAX = "monthly_sent_story_count_max"
    const val STORY_LINK_AREA_COUNT_MAX = "story_link_area_count_max"
    const val STORY_SUGGESTED_REACTION_AREA_COUNT_MAX = "story_suggested_reaction_area_count_max"
    const val STORY_STEALTH_MODE_COOLDOWN_PERIOD = "story_stealth_mode_cooldown_period"
    const val STORY_STEALTH_MODE_FUTURE_PERIOD = "story_stealth_mode_future_period"
    const val STORY_STEALTH_MODE_PAST_PERIOD = "story_stealth_mode_past_period"
    const val STORY_VIEWERS_EXPIRATION_DELAY = "story_viewers_expiration_delay"

    const val FAVORITE_STICKERS_LIMIT = "favorite_stickers_limit"
    const val SAVED_ANIMATIONS_LIMIT = "saved_animations_limit"
    const val NOTIFICATION_SOUND_COUNT_MAX = "notification_sound_count_max"
    const val NOTIFICATION_SOUND_DURATION_MAX = "notification_sound_duration_max"
    const val NOTIFICATION_SOUND_SIZE_MAX = "notification_sound_size_max"

    const val GIFT_TEXT_LENGTH_MAX = "gift_text_length_max"
    const val USER_NOTE_TEXT_LENGTH_MAX = "user_note_text_length_max"
    const val GROUP_CALL_MESSAGE_TEXT_LENGTH_MAX = "group_call_message_text_length_max"

    val ALL: Set<String> = setOf(
        MESSAGE_TEXT_LENGTH_MAX,
        MESSAGE_CAPTION_LENGTH_MAX,
        MESSAGE_REPLY_QUOTE_LENGTH_MAX,
        STORY_CAPTION_LENGTH_MAX,
        BIO_LENGTH_MAX,
        BUSINESS_START_PAGE_TITLE_LENGTH_MAX,
        BUSINESS_START_PAGE_MESSAGE_LENGTH_MAX,
        FORWARDED_MESSAGE_COUNT_MAX,
        RICH_MESSAGE_TEXT_LENGTH_MAX,
        RICH_MESSAGE_BLOCK_COUNT_MAX,
        RICH_MESSAGE_DEPTH_MAX,
        RICH_MESSAGE_MEDIA_COUNT_MAX,
        RICH_MESSAGE_TABLE_COLUMN_COUNT_MAX,
        CHECKLIST_TASK_COUNT_MAX,
        CHECKLIST_TASK_TEXT_LENGTH_MAX,
        CHECKLIST_TITLE_LENGTH_MAX,
        POLL_ANSWER_COUNT_MAX,
        POLL_OPEN_PERIOD_MAX,
        CHAT_FOLDER_COUNT_MAX,
        CHAT_FOLDER_CHOSEN_CHAT_COUNT_MAX,
        CHAT_FOLDER_INVITE_LINK_COUNT_MAX,
        PINNED_CHAT_COUNT_MAX,
        PINNED_ARCHIVED_CHAT_COUNT_MAX,
        PINNED_FORUM_TOPIC_COUNT_MAX,
        PINNED_SAVED_MESSAGES_TOPIC_COUNT_MAX,
        ACTIVE_STORY_COUNT_MAX,
        WEEKLY_SENT_STORY_COUNT_MAX,
        MONTHLY_SENT_STORY_COUNT_MAX,
        STORY_LINK_AREA_COUNT_MAX,
        STORY_SUGGESTED_REACTION_AREA_COUNT_MAX,
        STORY_STEALTH_MODE_COOLDOWN_PERIOD,
        STORY_STEALTH_MODE_FUTURE_PERIOD,
        STORY_STEALTH_MODE_PAST_PERIOD,
        STORY_VIEWERS_EXPIRATION_DELAY,
        FAVORITE_STICKERS_LIMIT,
        SAVED_ANIMATIONS_LIMIT,
        NOTIFICATION_SOUND_COUNT_MAX,
        NOTIFICATION_SOUND_DURATION_MAX,
        NOTIFICATION_SOUND_SIZE_MAX,
        GIFT_TEXT_LENGTH_MAX,
        USER_NOTE_TEXT_LENGTH_MAX,
        GROUP_CALL_MESSAGE_TEXT_LENGTH_MAX
    )
}
