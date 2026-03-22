package org.monogram.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.monogram.data.db.dao.*
import org.monogram.data.db.model.*

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        UserEntity::class,
        ChatFullInfoEntity::class,
        TopicEntity::class,
        UserFullInfoEntity::class,
        StickerSetEntity::class,
        RecentEmojiEntity::class,
        SearchHistoryEntity::class,
        ChatFolderEntity::class,
        AttachBotEntity::class,
        KeyValueEntity::class,
        NotificationSettingEntity::class,
        WallpaperEntity::class,
        StickerPathEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class MonogramDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun chatFullInfoDao(): ChatFullInfoDao
    abstract fun topicDao(): TopicDao
    abstract fun userFullInfoDao(): UserFullInfoDao
    abstract fun stickerSetDao(): StickerSetDao
    abstract fun recentEmojiDao(): RecentEmojiDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun chatFolderDao(): ChatFolderDao
    abstract fun attachBotDao(): AttachBotDao
    abstract fun keyValueDao(): KeyValueDao
    abstract fun notificationSettingDao(): NotificationSettingDao
    abstract fun wallpaperDao(): WallpaperDao
    abstract fun stickerPathDao(): StickerPathDao

    companion object {
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN unreadMentionCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN unreadReactionCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isMarkedAsUnread INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN hasProtectedContent INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isTranslatable INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN hasAutomaticTranslation INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN messageAutoDeleteTime INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN canBeDeletedOnlyForSelf INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN canBeDeletedForAllUsers INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN canBeReported INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN lastReadInboxMessageId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN lastReadOutboxMessageId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN lastMessageId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isLastMessageOutgoing INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN replyMarkupMessageId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN messageSenderId INTEGER")
                database.execSQL("ALTER TABLE chats ADD COLUMN blockList INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN emojiStatusId INTEGER")
                database.execSQL("ALTER TABLE chats ADD COLUMN accentColorId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN profileAccentColorId INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE chats ADD COLUMN backgroundCustomEmojiId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN photoId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isSupergroup INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isAdmin INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isOnline INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN typingAction TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN draftMessage TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN viewAsTopics INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isForum INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isBot INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isMember INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN username TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN description TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN inviteLink TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendBasicMessages INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendAudios INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendDocuments INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendPhotos INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendVideos INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendVideoNotes INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendVoiceNotes INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendPolls INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanSendOtherMessages INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanAddLinkPreviews INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanEditTag INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanChangeInfo INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanInviteUsers INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanPinMessages INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN permissionCanCreateTopics INTEGER NOT NULL DEFAULT 0")

                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN giftCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN canGetRevenueStatistics INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN hasRestrictedVoiceAndVideoNoteMessages INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN hasPostedToProfileStories INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN setChatBackground INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN incomingPaidMessageStarCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_full_info ADD COLUMN outgoingPaidMessageStarCount INTEGER NOT NULL DEFAULT 0")

                database.execSQL("ALTER TABLE user_full_info ADD COLUMN giftCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_full_info ADD COLUMN hasRestrictedVoiceAndVideoNoteMessages INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_full_info ADD COLUMN hasPostedToProfileStories INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_full_info ADD COLUMN setChatBackground INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_full_info ADD COLUMN canGetRevenueStatistics INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_full_info ADD COLUMN incomingPaidMessageStarCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_full_info ADD COLUMN outgoingPaidMessageStarCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN privateUserId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN basicGroupId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN supergroupId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN secretChatId INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
