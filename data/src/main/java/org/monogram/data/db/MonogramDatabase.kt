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
        UserFullInfoEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MonogramDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun chatFullInfoDao(): ChatFullInfoDao
    abstract fun topicDao(): TopicDao
    abstract fun userFullInfoDao(): UserFullInfoDao
}