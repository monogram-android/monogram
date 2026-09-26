package org.monogram.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import org.monogram.core.database.dao.ChatDao
import org.monogram.core.database.dao.FolderDao
import org.monogram.core.database.dao.MessageDao
import org.monogram.core.database.dao.MetaDao
import org.monogram.core.database.dao.PeerDao
import org.monogram.core.database.dao.ProfileCommonDao
import org.monogram.core.database.dao.ProfileMediaDao
import org.monogram.core.database.dao.ProfileMembersDao
import org.monogram.core.database.dao.ProfileTabsDao
import org.monogram.core.database.dao.UpdateCursorDao
import org.monogram.core.database.entity.ChatEntity
import org.monogram.core.database.entity.FolderEntity
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.MetaEntity
import org.monogram.core.database.entity.PeerEntity
import org.monogram.core.database.dao.SponsorDao
import org.monogram.core.database.entity.ProfileCommonEntity
import org.monogram.core.database.entity.SponsorEntity
import org.monogram.core.database.entity.ProfileMediaEntity
import org.monogram.core.database.entity.ProfileMemberEntity
import org.monogram.core.database.entity.ProfileTabsEntity
import org.monogram.core.database.entity.UpdateCursorEntity

@Database(
    entities = [
        MetaEntity::class,
        ChatEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        UpdateCursorEntity::class,
        PeerEntity::class,
        ProfileTabsEntity::class,
        ProfileMemberEntity::class,
        ProfileMediaEntity::class,
        ProfileCommonEntity::class,
        SponsorEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MonogramDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao
    abstract fun chatDao(): ChatDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun updateCursorDao(): UpdateCursorDao
    abstract fun peerDao(): PeerDao
    abstract fun profileTabsDao(): ProfileTabsDao
    abstract fun profileMembersDao(): ProfileMembersDao
    abstract fun profileMediaDao(): ProfileMediaDao
    abstract fun profileCommonDao(): ProfileCommonDao
    abstract fun sponsorDao(): SponsorDao
}
