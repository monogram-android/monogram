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
        StickerPathEntity::class,
        SponsorEntity::class
    ],
    version = 22,
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
    abstract fun sponsorDao(): SponsorDao

    companion object {
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                wipeAllTablesExceptFoldersAndAttachBots(db)
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chats` ADD COLUMN `isSponsor` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sponsors` (
                        `userId` INTEGER NOT NULL,
                        `sourceChannelId` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private fun wipeAllTablesExceptFoldersAndAttachBots(db: SupportSQLiteDatabase) {
            val keep = setOf("chat_folders", "attach_bots")
            val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
            val tables = mutableListOf<String>()
            cursor.use {
                val idx = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    val table = if (idx >= 0) it.getString(idx) else null
                    if (table != null && table !in keep) {
                        tables.add(table)
                    }
                }
            }

            db.execSQL("PRAGMA foreign_keys=OFF")
            try {
                tables.forEach { table ->
                    db.execSQL("DELETE FROM `$table`")
                }
            } finally {
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }
    }
}
