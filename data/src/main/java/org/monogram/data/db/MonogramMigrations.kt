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
}
