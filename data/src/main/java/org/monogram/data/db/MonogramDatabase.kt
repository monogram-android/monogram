package org.monogram.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import org.monogram.data.db.dao.AttachBotDao
import org.monogram.data.db.dao.ChatDao
import org.monogram.data.db.dao.ChatFolderDao
import org.monogram.data.db.dao.ChatFullInfoDao
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.dao.MessageDao
import org.monogram.data.db.dao.NotificationExceptionDao
import org.monogram.data.db.dao.NotificationSettingDao
import org.monogram.data.db.dao.RecentEmojiDao
import org.monogram.data.db.dao.SearchHistoryDao
import org.monogram.data.db.dao.SponsorDao
import org.monogram.data.db.dao.StickerPathDao
import org.monogram.data.db.dao.StickerSetDao
import org.monogram.data.db.dao.TextCompositionStyleDao
import org.monogram.data.db.dao.TopicDao
import org.monogram.data.db.dao.UserDao
import org.monogram.data.db.dao.UserFullInfoDao
import org.monogram.data.db.dao.WallpaperDao
import org.monogram.data.db.model.AttachBotEntity
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.ChatFolderEntity
import org.monogram.data.db.model.ChatFullInfoEntity
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.NotificationExceptionEntity
import org.monogram.data.db.model.NotificationSettingEntity
import org.monogram.data.db.model.RecentEmojiEntity
import org.monogram.data.db.model.SearchHistoryEntity
import org.monogram.data.db.model.SponsorEntity
import org.monogram.data.db.model.StickerPathEntity
import org.monogram.data.db.model.StickerSetEntity
import org.monogram.data.db.model.TextCompositionStyleEntity
import org.monogram.data.db.model.TopicEntity
import org.monogram.data.db.model.UserEntity
import org.monogram.data.db.model.UserFullInfoEntity
import org.monogram.data.db.model.WallpaperEntity

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
        NotificationExceptionEntity::class,
        WallpaperEntity::class,
        StickerPathEntity::class,
        SponsorEntity::class,
        TextCompositionStyleEntity::class
    ],
    version = 30,
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
    abstract fun notificationExceptionDao(): NotificationExceptionDao
    abstract fun wallpaperDao(): WallpaperDao
    abstract fun stickerPathDao(): StickerPathDao
    abstract fun sponsorDao(): SponsorDao
    abstract fun textCompositionStyleDao(): TextCompositionStyleDao
}
