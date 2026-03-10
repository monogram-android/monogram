package org.monogram.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 14,
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
}