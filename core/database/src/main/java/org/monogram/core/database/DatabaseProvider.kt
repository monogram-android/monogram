package org.monogram.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.io.File

object DatabaseProvider {
    @Volatile
    private var instance: MonogramDatabase? = null

    const val SCHEMA_VERSION = 4

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN supportsStreaming INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToTopId INTEGER")
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN forumTopic INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE chats ADD COLUMN canManageTopics INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
    private const val DB_NAME = "monogram.db"
    private const val IDENTITY_BACKUP = "session-identity.json"

    private val identityKeys = listOf(
        SessionMetadataStore.KEY_USER_ID,
        SessionMetadataStore.KEY_DC_ID,
        SessionMetadataStore.KEY_AUTHORIZED,
    )

    fun hasMigrationPath(fromVersion: Int, toVersion: Int = SCHEMA_VERSION): Boolean =
        fromVersion <= 0 || fromVersion in 1..toVersion

    fun get(context: Context): MonogramDatabase {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: open(context.applicationContext)
        }
    }

    fun closeAndReset() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }

    private fun open(app: Context): MonogramDatabase {
        snapshotIdentity(app)
        return try {
            build(app).also { finishOpen(app, it) }
        } catch (_: IllegalStateException) {
            instance?.close()
            instance = null
            app.deleteDatabase(DB_NAME)
            build(app).also { finishOpen(app, it) }
        }
    }

    private fun finishOpen(app: Context, db: MonogramDatabase) {
        db.openHelper.writableDatabase
        restoreIdentity(app, db)
        instance = db
    }

    private fun build(app: Context): MonogramDatabase =
        Room.databaseBuilder(app, MonogramDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    /** Cache tables may be dropped; keep only login identity (not messages/chats). */
    internal fun snapshotIdentity(context: Context) {
        val backup = identityBackupFile(context)
        if (backup.exists()) return
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return
        val sqlite = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return
        try {
            val json = JSONObject()
            sqlite.rawQuery(
                "SELECT `key`, value FROM meta WHERE `key` IN (?, ?, ?)",
                identityKeys.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    json.put(cursor.getString(0), cursor.getString(1))
                }
            }
            if (json.length() > 0) {
                backup.writeText(json.toString())
            }
        } catch (_: Exception) {
            // Missing/corrupt cache: still allow Room to recreate.
        } finally {
            sqlite.close()
        }
    }

    internal fun restoreIdentity(context: Context, db: MonogramDatabase) {
        val backup = identityBackupFile(context)
        if (!backup.exists()) return
        val json = runCatching { JSONObject(backup.readText()) }.getOrNull() ?: run {
            backup.delete()
            return
        }
        val sqlite = db.openHelper.writableDatabase
        identityKeys.forEach { key ->
            if (!json.has(key)) return@forEach
            val value = json.optString(key, "")
            sqlite.execSQL(
                "INSERT OR REPLACE INTO meta (`key`, value) VALUES (?, ?)",
                arrayOf(key, value),
            )
        }
        backup.delete()
    }

    private fun identityBackupFile(context: Context): File =
        File(context.filesDir, IDENTITY_BACKUP)
}
