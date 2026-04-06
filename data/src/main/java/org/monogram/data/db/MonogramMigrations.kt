package org.monogram.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MonogramMigrations {
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `text_composition_styles` (
                    `name` TEXT NOT NULL,
                    `customEmojiId` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    PRIMARY KEY(`name`)
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.addColumn("users", "isScam", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "isFake", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botVerificationIconCustomEmojiId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeCanBeEdited", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeCanJoinGroups", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeCanReadAllGroupMessages", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeHasMainWebApp", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeHasTopics", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeAllowsUsersToCreateTopics", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeCanManageBots", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeIsInline", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeInlineQueryPlaceholder", "TEXT")
            db.addColumn("users", "botTypeNeedLocation", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeCanConnectToBusiness", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeCanBeAddedToAttachmentMenu", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "botTypeActiveUserCount", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "userType", "TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.addColumn("users", "restrictionReason", "TEXT")
            db.addColumn("users", "hasSensitiveContent", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "activeStoryStateType", "TEXT")
            db.addColumn("users", "activeStoryId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "restrictsNewChats", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "paidMessageStarCount", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "backgroundCustomEmojiId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "profileBackgroundCustomEmojiId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("users", "addedToAttachmentMenu", "INTEGER NOT NULL DEFAULT 0")

            db.addColumn("chats", "isScam", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "isFake", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "botVerificationIconCustomEmojiId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "restrictionReason", "TEXT")
            db.addColumn("chats", "hasSensitiveContent", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "activeStoryStateType", "TEXT")
            db.addColumn("chats", "activeStoryId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "boostLevel", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "hasForumTabs", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "isAdministeredDirectMessagesGroup", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chats", "paidMessageStarCount", "INTEGER NOT NULL DEFAULT 0")

            db.addColumn("user_full_info", "botInfoShortDescription", "TEXT")
            db.addColumn("user_full_info", "botInfoPhotoFileId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botInfoPhotoPath", "TEXT")
            db.addColumn("user_full_info", "botInfoAnimationFileId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botInfoAnimationPath", "TEXT")
            db.addColumn("user_full_info", "botInfoManagerBotUserId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botInfoMenuButtonText", "TEXT")
            db.addColumn("user_full_info", "botInfoMenuButtonUrl", "TEXT")
            db.addColumn("user_full_info", "botInfoCommandsData", "TEXT")
            db.addColumn("user_full_info", "botInfoPrivacyPolicyUrl", "TEXT")
            db.addColumn("user_full_info", "botInfoDefaultGroupRightsData", "TEXT")
            db.addColumn("user_full_info", "botInfoDefaultChannelRightsData", "TEXT")
            db.addColumn("user_full_info", "botInfoAffiliateProgramData", "TEXT")
            db.addColumn("user_full_info", "botInfoWebAppBackgroundLightColor", "INTEGER NOT NULL DEFAULT -1")
            db.addColumn("user_full_info", "botInfoWebAppBackgroundDarkColor", "INTEGER NOT NULL DEFAULT -1")
            db.addColumn("user_full_info", "botInfoWebAppHeaderLightColor", "INTEGER NOT NULL DEFAULT -1")
            db.addColumn("user_full_info", "botInfoWebAppHeaderDarkColor", "INTEGER NOT NULL DEFAULT -1")
            db.addColumn(
                "user_full_info",
                "botInfoVerificationParametersIconCustomEmojiId",
                "INTEGER NOT NULL DEFAULT 0"
            )
            db.addColumn("user_full_info", "botInfoVerificationParametersOrganizationName", "TEXT")
            db.addColumn("user_full_info", "botInfoVerificationParametersDefaultCustomDescription", "TEXT")
            db.addColumn(
                "user_full_info",
                "botInfoVerificationParametersCanSetCustomDescription",
                "INTEGER NOT NULL DEFAULT 0"
            )
            db.addColumn("user_full_info", "botInfoCanManageEmojiStatus", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botInfoHasMediaPreviews", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botInfoEditCommandsLinkType", "TEXT")
            db.addColumn("user_full_info", "botInfoEditDescriptionLinkType", "TEXT")
            db.addColumn("user_full_info", "botInfoEditDescriptionMediaLinkType", "TEXT")
            db.addColumn("user_full_info", "botInfoEditSettingsLinkType", "TEXT")
            db.addColumn("user_full_info", "publicPhotoPath", "TEXT")
            db.addColumn("user_full_info", "blockListType", "TEXT")
            db.addColumn("user_full_info", "note", "TEXT")
            db.addColumn("user_full_info", "hasSponsoredMessagesEnabled", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "needPhoneNumberPrivacyException", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "usesUnofficialApp", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botVerificationBotUserId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botVerificationIconCustomEmojiId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "botVerificationCustomDescription", "TEXT")
            db.addColumn("user_full_info", "mainProfileTab", "TEXT")
            db.addColumn("user_full_info", "firstProfileAudioDuration", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "firstProfileAudioTitle", "TEXT")
            db.addColumn("user_full_info", "firstProfileAudioPerformer", "TEXT")
            db.addColumn("user_full_info", "firstProfileAudioFileName", "TEXT")
            db.addColumn("user_full_info", "firstProfileAudioMimeType", "TEXT")
            db.addColumn("user_full_info", "firstProfileAudioFileId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "firstProfileAudioPath", "TEXT")
            db.addColumn("user_full_info", "ratingLevel", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "ratingIsMaximumLevelReached", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "ratingValue", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "ratingCurrentLevelValue", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "ratingNextLevelValue", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "pendingRatingLevel", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "pendingRatingIsMaximumLevelReached", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "pendingRatingValue", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "pendingRatingCurrentLevelValue", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "pendingRatingNextLevelValue", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("user_full_info", "pendingRatingDate", "INTEGER NOT NULL DEFAULT 0")

            db.addColumn("chat_full_info", "directMessagesChatId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "botInfoData", "TEXT")
            db.addColumn("chat_full_info", "blockListType", "TEXT")
            db.addColumn("chat_full_info", "publicPhotoPath", "TEXT")
            db.addColumn("chat_full_info", "usesUnofficialApp", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "hasSponsoredMessagesEnabled", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "needPhoneNumberPrivacyException", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "botVerificationBotUserId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "botVerificationIconCustomEmojiId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "botVerificationCustomDescription", "TEXT")
            db.addColumn("chat_full_info", "mainProfileTab", "TEXT")
            db.addColumn("chat_full_info", "firstProfileAudioData", "TEXT")
            db.addColumn("chat_full_info", "ratingData", "TEXT")
            db.addColumn("chat_full_info", "pendingRatingData", "TEXT")
            db.addColumn("chat_full_info", "pendingRatingDate", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "slowModeDelayExpiresIn", "REAL NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "canEnablePaidMessages", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "canEnablePaidReaction", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "hasHiddenMembers", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "canHideMembers", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "canGetStarRevenueStatistics", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "canToggleAggressiveAntiSpam", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "isAllHistoryAvailable", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "canHaveSponsoredMessages", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "hasAggressiveAntiSpamEnabled", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "hasPaidMediaAllowed", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "hasPinnedStories", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "myBoostCount", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "unrestrictBoostCount", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "stickerSetId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "customEmojiStickerSetId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "botCommandsData", "TEXT")
            db.addColumn("chat_full_info", "upgradedFromBasicGroupId", "INTEGER NOT NULL DEFAULT 0")
            db.addColumn("chat_full_info", "upgradedFromMaxMessageId", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notification_exceptions` (
                    `chatId` INTEGER NOT NULL,
                    `scope` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `avatarPath` TEXT,
                    `personalAvatarPath` TEXT,
                    `isMuted` INTEGER NOT NULL,
                    `isGroup` INTEGER NOT NULL,
                    `isChannel` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`chatId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notification_exceptions_scope` ON `notification_exceptions` (`scope`)"
            )
        }
    }

    private fun SupportSQLiteDatabase.addColumn(table: String, column: String, definition: String) {
        execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}
