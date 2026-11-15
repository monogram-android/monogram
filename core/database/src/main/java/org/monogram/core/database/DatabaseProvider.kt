package org.monogram.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.monogram.core.common.PerfLog
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

object DatabaseProvider {
    private const val DEFAULT_INSTANCE = "default"

    private val instances = mutableMapOf<String, MonogramDatabase>()

    const val SCHEMA_VERSION = 11

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
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_archived_left_pinned_pinnedOrder_lastMessageDate_id ON chats (archived ASC, `left` ASC, pinned DESC, pinnedOrder ASC, lastMessageDate DESC, id DESC)")
            db.execSQL("CREATE TABLE IF NOT EXISTS message_holes (chatId INTEGER NOT NULL, startId INTEGER NOT NULL, endId INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(chatId, startId, endId, source))")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS message_tombstones (chatId INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY(chatId, id))")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS update_batch_receipts (" + "accountGeneration INTEGER NOT NULL, batchId INTEGER NOT NULL, " + "appliedAtMs INTEGER NOT NULL, PRIMARY KEY(accountGeneration, batchId))"
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyQuoteEntitiesJson TEXT")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN photoKnown INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE peers ADD COLUMN avatarKnown INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_messages (
                    chatId INTEGER NOT NULL, id INTEGER NOT NULL, senderId INTEGER, text TEXT,
                    date INTEGER NOT NULL, editDate INTEGER, outgoing INTEGER NOT NULL,
                    mediaCacheKey TEXT, mediaKind TEXT, thumbCacheKey TEXT, mediaDuration INTEGER,
                    mediaWidth INTEGER, mediaHeight INTEGER, groupedId INTEGER, fileName TEXT,
                    fileSize INTEGER, supportsStreaming INTEGER NOT NULL, reactionsJson TEXT,
                    repliesCount INTEGER NOT NULL, discussionPeerId INTEGER, replyQuote TEXT,
                    replyQuoteEntitiesJson TEXT, entitiesJson TEXT, replyToMsgId INTEGER,
                    replyToTopId INTEGER, forumTopic INTEGER NOT NULL, fwdFrom TEXT, fwdFromId INTEGER,
                    fwdDate INTEGER, viaBot TEXT, senderName TEXT, replyMarkupJson TEXT,
                    pending INTEGER NOT NULL, failed INTEGER NOT NULL DEFAULT 0, randomId INTEGER,
                    checklistJson TEXT, hasUnsupportedContent INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(chatId, id)
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_messages_chatId_date ON scheduled_messages (chatId, date)")
            db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_queue_revisions (chatId INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(chatId))")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outgoing_text_sends (
                    randomId INTEGER NOT NULL,
                    message_chatId INTEGER NOT NULL, message_id INTEGER NOT NULL, message_senderId INTEGER,
                    message_text TEXT, message_date INTEGER NOT NULL, message_editDate INTEGER,
                    message_outgoing INTEGER NOT NULL, message_mediaCacheKey TEXT, message_mediaKind TEXT,
                    message_thumbCacheKey TEXT, message_mediaDuration INTEGER, message_mediaWidth INTEGER,
                    message_mediaHeight INTEGER, message_groupedId INTEGER, message_fileName TEXT,
                    message_fileSize INTEGER, message_supportsStreaming INTEGER NOT NULL, message_reactionsJson TEXT,
                    message_repliesCount INTEGER NOT NULL, message_discussionPeerId INTEGER, message_replyQuote TEXT,
                    message_replyQuoteEntitiesJson TEXT, message_entitiesJson TEXT, message_replyToMsgId INTEGER,
                    message_replyToTopId INTEGER, message_forumTopic INTEGER NOT NULL, message_fwdFrom TEXT,
                    message_fwdFromId INTEGER, message_fwdDate INTEGER, message_viaBot TEXT, message_senderName TEXT,
                    message_replyMarkupJson TEXT, message_pending INTEGER NOT NULL, message_failed INTEGER NOT NULL DEFAULT 0,
                    message_randomId INTEGER, message_checklistJson TEXT, silent INTEGER NOT NULL,
                    noWebpage INTEGER NOT NULL, scheduleDate INTEGER, webpageUrl TEXT, phase TEXT NOT NULL,
                    PRIMARY KEY(randomId)
                )
            """.trimIndent()
            )
            db.execSQL("ALTER TABLE chats ADD COLUMN canSendPolls INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outgoing_media_batches (
                    batchId INTEGER NOT NULL, chatId INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                    silent INTEGER NOT NULL, scheduleDate INTEGER, album INTEGER NOT NULL, phase TEXT NOT NULL,
                    PRIMARY KEY(batchId)
                )
            """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outgoing_media_items (
                    randomId INTEGER NOT NULL, batchId INTEGER NOT NULL, position INTEGER NOT NULL, mimeType TEXT NOT NULL,
                    source_name TEXT NOT NULL, source_size INTEGER NOT NULL, source_sha256 TEXT NOT NULL,
                    message_chatId INTEGER NOT NULL, message_id INTEGER NOT NULL, message_senderId INTEGER,
                    message_text TEXT, message_date INTEGER NOT NULL, message_editDate INTEGER,
                    message_outgoing INTEGER NOT NULL, message_mediaCacheKey TEXT, message_mediaKind TEXT,
                    message_thumbCacheKey TEXT, message_mediaDuration INTEGER, message_mediaWidth INTEGER,
                    message_mediaHeight INTEGER, message_groupedId INTEGER, message_fileName TEXT,
                    message_fileSize INTEGER, message_supportsStreaming INTEGER NOT NULL, message_reactionsJson TEXT,
                    message_repliesCount INTEGER NOT NULL, message_discussionPeerId INTEGER, message_replyQuote TEXT,
                    message_replyQuoteEntitiesJson TEXT, message_entitiesJson TEXT, message_replyToMsgId INTEGER,
                    message_replyToTopId INTEGER, message_forumTopic INTEGER NOT NULL, message_fwdFrom TEXT,
                    message_fwdFromId INTEGER, message_fwdDate INTEGER, message_viaBot TEXT, message_senderName TEXT,
                    message_replyMarkupJson TEXT, message_pending INTEGER NOT NULL, message_failed INTEGER NOT NULL DEFAULT 0,
                    message_randomId INTEGER, message_checklistJson TEXT, PRIMARY KEY(randomId),
                    FOREIGN KEY(batchId) REFERENCES outgoing_media_batches(batchId) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_outgoing_media_items_batchId_position ON outgoing_media_items(batchId, position)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_outgoing_media_items_source_name ON outgoing_media_items(source_name)")
        }
    }

    /** SQL stays off Dispatchers.IO, which media downloads also use. */
    private val queueLock = Object()
    private val onRoomThread = ThreadLocal<Boolean>()
    private val foregroundQueue = ArrayDeque<Runnable>()
    private val maintenanceQueue = ArrayDeque<Runnable>()
    private val roomThread = Thread({
        while (!Thread.currentThread().isInterrupted) {
            val task = synchronized(queueLock) {
                while (foregroundQueue.isEmpty() && maintenanceQueue.isEmpty()) {
                    queueLock.wait()
                }
                if (foregroundQueue.isNotEmpty()) foregroundQueue.removeFirst() else maintenanceQueue.removeFirst()
            }
            task.run()
        }
    }, "monogram-room").apply {
        isDaemon = true
        start()
    }

    internal val roomExecutor: Executor = Executor { runnable ->
        val queuedAt = PerfLog.nowMs()
        val opId = PerfLog.currentOpId()
        val maintenance = RoomLane.current() == RoomLane.Kind.MAINTENANCE
        val task = Runnable {
            val startedAt = PerfLog.nowMs()
            onRoomThread.set(true)
            try {
                PerfLog.withOpId(opId) { runnable.run() }
            } finally {
                onRoomThread.remove()
                if (PerfLog.isEnabled()) {
                    PerfLog.withOpId(opId) {
                        PerfLog.event(
                            "room_queue",
                            "wait_ms=${startedAt - queuedAt} run_ms=${PerfLog.nowMs() - startedAt}"
                        )
                    }
                }
            }
        }
        synchronized(queueLock) {
            (if (maintenance) maintenanceQueue else foregroundQueue).addLast(task)
            queueLock.notifyAll()
        }
    }

    @Suppress("unused")
    private val keepRoomThread = roomThread
    private val roomDispatcher = roomExecutor.asCoroutineDispatcher()

    private val roomQueryExecutor = roomPool("monogram-room-query", threads = 2, background = false)
    private val roomTransactionExecutor =
        roomPool("monogram-room-tx", threads = 1, background = true)

    internal suspend fun <T> onRoom(block: suspend () -> T): T =
        if (onRoomThread.get() == true) block() else withContext(roomDispatcher) { block() }

    private fun roomPool(name: String, threads: Int, background: Boolean): Executor {
        val pool = Executors.newFixedThreadPool(threads) { worker ->
            Thread(worker, name).apply { isDaemon = true }
        }
        return Executor { command ->
            pool.execute {
                if (background) {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                }
                onRoomThread.set(true)
                try {
                    command.run()
                } finally {
                    onRoomThread.remove()
                }
            }
        }
    }

    fun roomQueueDepth(): Int = synchronized(queueLock) {
        foregroundQueue.size + maintenanceQueue.size
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

    fun get(context: Context): MonogramDatabase = getNamed(
        context = context,
        instanceKey = DEFAULT_INSTANCE,
        databaseName = DB_NAME,
        identityBackupName = IDENTITY_BACKUP,
    )

    /** Opens an isolated database for one Telegram account. */
    fun get(context: Context, accountId: Long): MonogramDatabase {
        require(accountId > 0) { "accountId must be positive" }
        val suffix = accountId.toString()
        return getNamed(
            context = context,
            instanceKey = "account:$suffix",
            databaseName = "monogram-account-$suffix.db",
            identityBackupName = "session-identity-$suffix.json",
        )
    }

    private fun getNamed(
        context: Context,
        instanceKey: String,
        databaseName: String,
        identityBackupName: String,
    ): MonogramDatabase = synchronized(this) {
        instances[instanceKey] ?: open(
            context.applicationContext,
            databaseName,
            identityBackupName,
        ).also { instances[instanceKey] = it }
    }

    fun closeAndReset() {
        synchronized(this) {
            instances.values.forEach { it.close() }
            instances.clear()
        }
    }

    fun close(accountId: Long) {
        synchronized(this) {
            instances.remove("account:$accountId")?.close()
        }
    }

    fun closeDefault() {
        synchronized(this) {
            instances.remove(DEFAULT_INSTANCE)?.close()
        }
    }

    private fun open(
        app: Context,
        databaseName: String,
        identityBackupName: String,
    ): MonogramDatabase {
        snapshotIdentity(app, databaseName, identityBackupName)
        val database = build(app, databaseName)
        try {
            finishOpen(app, database, identityBackupName)
            return database
        } catch (failure: Throwable) {
            try {
                database.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun finishOpen(
        app: Context,
        db: MonogramDatabase,
        identityBackupName: String,
    ) {
        db.openHelper.writableDatabase
        restoreIdentity(app, db, identityBackupName)
    }

    private fun build(app: Context, databaseName: String): MonogramDatabase =
        Room.databaseBuilder(app, MonogramDatabase::class.java, databaseName).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
        ).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(roomQueryExecutor).setTransactionExecutor(roomTransactionExecutor)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=NORMAL")
                }
            }).build()

    /** Cache tables may be dropped; keep only login identity (not messages/chats). */
    internal fun snapshotIdentity(
        context: Context,
        databaseName: String = DB_NAME,
        identityBackupName: String = IDENTITY_BACKUP,
    ) {
        val backup = identityBackupFile(context, identityBackupName)
        if (backup.exists()) return
        val file = context.getDatabasePath(databaseName)
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
            // Let Room validate the database even if the identity snapshot is unavailable.
        } finally {
            sqlite.close()
        }
    }

    internal fun restoreIdentity(
        context: Context,
        db: MonogramDatabase,
        identityBackupName: String = IDENTITY_BACKUP,
    ) {
        val backup = identityBackupFile(context, identityBackupName)
        if (!backup.exists()) return
        val json = runCatching { JSONObject(backup.readText()) }.getOrNull() ?: run {
            backup.delete()
            return
        }
        val sqlite = db.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            val identityExists = sqlite.query(
                "SELECT 1 FROM meta WHERE `key` IN (?, ?, ?) LIMIT 1",
                identityKeys.toTypedArray(),
            ).use { it.moveToFirst() }
            if (!identityExists) {
                identityKeys.forEach { key ->
                    if (!json.has(key)) return@forEach
                    val value = json.optString(key, "")
                    sqlite.execSQL(
                        "INSERT INTO meta (`key`, value) VALUES (?, ?)",
                        arrayOf(key, value),
                    )
                }
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        backup.delete()
    }

    private fun identityBackupFile(context: Context, name: String): File =
        File(context.filesDir, name)
}

internal class RoomLane(val kind: Kind) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<RoomLane.Kind?> {
    enum class Kind { FOREGROUND, MAINTENANCE }

    override fun updateThreadContext(context: CoroutineContext): Kind? {
        val previous = current.get()
        current.set(kind)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Kind?) {
        if (oldState == null) current.remove() else current.set(oldState)
    }

    companion object Key : CoroutineContext.Key<RoomLane> {
        private val current = ThreadLocal<Kind>()
        fun current(): Kind = current.get() ?: Kind.FOREGROUND
        fun maintenance(): RoomLane = RoomLane(Kind.MAINTENANCE)
    }
}
