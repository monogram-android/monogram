package org.monogram

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.MonogramDatabase
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.OutgoingMediaFiles
import org.monogram.core.database.SessionMetadataStore
import org.monogram.network.bridge.BridgedMtprotoClient
import org.monogram.network.bridge.ClientInitInfo
import java.io.File
import java.util.UUID

/** Owns the independently fenced native and cache state for each registered account. */
class AccountRuntimeManager(
    private val context: Context,
    private val registry: AccountRegistry,
    private val credentials: TelegramCredentials,
    private val initConnection: ClientInitInfo,
) {
    private val mutex = Mutex()
    private val runtimes = LinkedHashMap<Long, AccountRuntime>()
    private val failedTeardowns = mutableSetOf<Long>()

    @Volatile
    private var activeGeneration = 0L

    @Volatile
    private var activeUserId: Long? = null

    suspend fun open(account: AccountRecord): AccountRuntime = mutex.withLock {
        checkTeardown(account.userId)
        runtimes[account.userId] ?: create(account).also { runtimes[account.userId] = it }
    }

    suspend fun adopt(
        account: AccountRecord,
        client: BridgedMtprotoClient,
        database: MonogramDatabase,
        sessionStore: SessionMetadataStore,
        warmup: OfflineWarmup,
    ) = mutex.withLock {
        checkTeardown(account.userId)
        runtimes[account.userId]?.takeIf { it.client !== client }?.let(::closeRuntime)
        runtimes[account.userId] = AccountRuntime(account, database, sessionStore, warmup, client)
        if (registry.activeUserId == account.userId) {
            activeUserId = account.userId
            activeGeneration++
        }
    }

    suspend fun switchTo(userId: Long): AccountRuntime = mutex.withLock {
        checkTeardown(userId)
        val account = registry.get(userId) ?: error("Unknown account: $userId")
        val runtime = runtimes[userId] ?: create(account).also { runtimes[userId] = it }
        registry.setActive(userId)
        activeUserId = userId
        activeGeneration++
        runtime
    }

    suspend fun startAll(readyUserIds: Set<Long>) = coroutineScope {
        registry.all().filter { it.userId in readyUserIds }.map { account ->
            async {
                try {
                    val runtime = mutex.withLock {
                        val registered = registry.get(account.userId) ?: return@async
                        checkTeardown(registered.userId)
                        runtimes[registered.userId] ?: create(registered).also {
                            runtimes[registered.userId] = it
                        }
                    }
                    if (runtime.sessionStore.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0") return@async
                    if (runtime.client.connect() is Outcome.Err) {
                        AppLog.warn("startup", "account client connection failed")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AppLog.warn("startup", "account client connection failed unexpectedly")
                }
            }
        }.awaitAll()
    }

    suspend fun hibernateAll() = mutex.withLock {
        runtimes.values.filter { it.account.userId !in failedTeardowns }
            .forEach { it.client.hibernate() }
    }

    suspend fun requireAvailable(userId: Long) = mutex.withLock { checkTeardown(userId) }

    suspend fun close(userId: Long, removeStorage: Boolean) = mutex.withLock {
        checkTeardown(userId)
        val runtime = runtimes[userId]
        runtime?.let(::closeRuntime)
        runtimes.remove(userId)
        val account = runtime?.account ?: registry.get(userId)
        if (account?.databaseName == "monogram.db") {
            DatabaseProvider.closeDefault()
        } else {
            DatabaseProvider.close(userId)
        }
        if (activeUserId == userId) {
            activeUserId = registry.all().firstOrNull { it.userId != userId }?.userId
            activeUserId?.let(registry::setActive)
            activeGeneration++
        }
        if (removeStorage) {
            if (account?.databaseName == "monogram.db") {
                deleteLegacyAccountFiles(context.filesDir)
                deleteLegacyOutgoingMedia(File(context.noBackupFilesDir, "outgoing-media"))
                check(!account.mediaPath.exists() || account.mediaPath.deleteRecursively()) {
                    "Unable to remove legacy account media"
                }
                val database = context.getDatabasePath("monogram.db")
                check(!database.exists() || context.deleteDatabase("monogram.db")) {
                    "Unable to remove legacy account database"
                }
            } else if (account != null) {
                val accountDir = File(context.filesDir, "accounts/${account.userId}")
                check(!accountDir.exists() || accountDir.deleteRecursively()) {
                    "Unable to remove account files"
                }
                val outgoingDir = File(context.noBackupFilesDir, "outgoing-media/${account.userId}")
                check(!outgoingDir.exists() || outgoingDir.deleteRecursively()) {
                    "Unable to remove account outgoing media"
                }
                val database = context.getDatabasePath(account.databaseName)
                val databaseExisted = database.exists()
                val databaseDeleted = context.deleteDatabase(account.databaseName)
                check(!databaseExisted || databaseDeleted) { "Unable to remove account database" }
                val identity = File(context.filesDir, "session-identity-${account.userId}.json")
                check(!identity.exists() || identity.delete()) { "Unable to remove account identity" }
            }
            registry.remove(userId)
        }
    }

    private fun checkTeardown(userId: Long) {
        check(userId !in failedTeardowns) { "Account shutdown requires recovery" }
    }

    private fun closeRuntime(runtime: AccountRuntime) {
        runtime.invalidate()
        try {
            runtime.client.close()
        } catch (failure: Throwable) {
            failedTeardowns.add(runtime.account.userId)
            throw failure
        }
    }

    fun activeGeneration(): Long = activeGeneration
    fun activeUserId(): Long? = activeUserId
    suspend fun active(): AccountRuntime? = mutex.withLock {
        activeUserId?.takeIf { it !in failedTeardowns }?.let(runtimes::get)
    }

    suspend fun all(): List<AccountRuntime> = mutex.withLock {
        runtimes.values.filter { it.account.userId !in failedTeardowns }
    }

    private fun create(account: AccountRecord): AccountRuntime {
        val database = if (account.databaseName == "monogram.db") {
            DatabaseProvider.get(context)
        } else {
            DatabaseProvider.get(context, account.userId)
        }
        val session = SessionMetadataStore(database)
        val warmup = OfflineWarmup(
            database,
            outgoingMediaFiles = OutgoingMediaFiles(
                File(
                    context.noBackupFilesDir,
                    if (account.databaseName == "monogram.db") "outgoing-media"
                    else "outgoing-media/${account.userId}",
                )
            ),
        )
        val client = BridgedMtprotoClient(
            credentials = credentials,
            sessionPath = account.sessionPath.absolutePath,
            initConnection = initConnection,
        )
        return AccountRuntime(account, database, session, warmup, client)
    }
}

internal fun requireVacantAccountStorage(
    filesDir: File, noBackupFilesDir: File, database: File, userId: Long
) {
    val paths = listOf(
        File(filesDir, "accounts/$userId"),
        File(filesDir, "session-identity-$userId.json"),
        File(noBackupFilesDir, "outgoing-media/$userId"),
        database,
        File("${database.path}-journal"),
        File("${database.path}-wal"),
        File("${database.path}-shm"),
    )
    check(paths.none(File::exists)) { "Existing account storage requires recovery" }
}

internal fun deleteLegacyAccountFiles(filesDir: File) {
    listOf(
        "mtproto.session.json",
        "mtproto.session.json.tmp",
        "mtproto.session.tmp",
        "mtproto.session.json.key",
        "mtproto.session.json.key.bak",
        "mtproto.session.json.key.new",
        "session-identity.json",
    ).forEach { name ->
        val file = File(filesDir, name)
        check(!file.exists() || file.delete()) { "Unable to remove account file" }
    }
}

internal fun deleteLegacyOutgoingMedia(directory: File) {
    if (!directory.exists()) return
    val files = directory.listFiles() ?: error("Unable to list legacy outgoing media")
    files.forEach { file ->
        val name = file.name.removeSuffix(".part")
        val id = name.removeSuffix(".bin")
        val validName = name.endsWith(".bin") && runCatching {
            UUID.fromString(id).toString() == id
        }.getOrDefault(false)
        if (validName && file.isFile && file.canonicalFile == file.absoluteFile) {
            check(file.delete()) { "Unable to remove legacy outgoing media" }
        }
    }
}

enum class AccountRuntimeStatus {
    Authorized, SignInRequired,
}

data class AccountRuntime(
    val account: AccountRecord,
    val database: MonogramDatabase,
    val sessionStore: SessionMetadataStore,
    val warmup: OfflineWarmup,
    val client: BridgedMtprotoClient,
) {
    @Volatile
    var status: AccountRuntimeStatus = AccountRuntimeStatus.Authorized
        private set

    fun markSignInRequired() {
        status = AccountRuntimeStatus.SignInRequired
    }

    fun markAuthorized() {
        status = AccountRuntimeStatus.Authorized
    }

    @Volatile
    private var valid = true

    fun isValid(): Boolean = valid

    fun invalidate() {
        valid = false
    }
}
