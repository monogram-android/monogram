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
    @Volatile
    private var instance: MonogramDatabase? = null

    const val SCHEMA_VERSION = 8

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
                "CREATE TABLE IF NOT EXISTS update_batch_receipts (" +
                        "accountGeneration INTEGER NOT NULL, batchId INTEGER NOT NULL, " +
                        "appliedAtMs INTEGER NOT NULL, PRIMARY KEY(accountGeneration, batchId))"
            )
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
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8
            )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(roomQueryExecutor)
            .setTransactionExecutor(roomTransactionExecutor)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=NORMAL")
                }
            })
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

internal class RoomLane(val kind: Kind) :
    AbstractCoroutineContextElement(Key), ThreadContextElement<RoomLane.Kind?> {
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
