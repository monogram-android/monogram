package org.monogram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.monogram.core.models.AuthSession
import java.io.File
import java.io.FileOutputStream

/** Durable account identities and derived storage paths; never stores auth keys. */
class AccountRegistry(
    private val filesDir: File,
) {
    private val file = File(filesDir, FILE_NAME)
    private val backup = File(filesDir, "$FILE_NAME.bak")
    private val temporary = File(filesDir, "$FILE_NAME.tmp")
    private val records = LinkedHashMap<Long, AccountRecord>()

    @Volatile
    private var recoveryRequired = false

    @Volatile
    @get:Synchronized
    var activeUserId: Long? = null
        get() {
            checkReadable()
            return field
        }
        private set

    @Synchronized
    fun load(): List<AccountRecord> {
        val state = when {
            !file.exists() && !backup.exists() -> {
                check(!temporary.exists()) { "Account registry recovery required" }
                RegistryState()
            }

            !file.exists() -> {
                val recovered = json.decodeFromString<RegistryState>(backup.readText())
                validatedRecords(recovered)
                check(backup.renameTo(file)) { "Unable to recover account registry" }
                recovered
            }

            else -> runCatching { json.decodeFromString<RegistryState>(file.readText()) }.getOrElse { primaryError ->
                check(backup.exists()) { throw primaryError }
                val recovered = json.decodeFromString<RegistryState>(backup.readText())
                validatedRecords(recovered)
                check(file.delete()) { "Unable to recover account registry" }
                check(backup.renameTo(file)) { "Unable to recover account registry" }
                recovered
            }
        }
        val restored = validatedRecords(state)
        if (backup.exists()) check(backup.delete()) { "Unable to finish account registry recovery" }
        records.clear()
        restored.forEach { record ->
            records[record.userId] = record
        }
        activeUserId = state.activeUserId.takeIf { it > 0 && records.containsKey(it) }
        recoveryRequired = false
        return records.values.toList()
    }

    private fun validatedRecords(state: RegistryState): List<AccountRecord> {
        val restored = state.accounts.mapIndexed { index, stored ->
            checkNotNull(stored.toRecord(filesDir, allowLegacy = index == 0)) {
                "Account registry contains an invalid account"
            }
        }
        check(restored.map { it.userId }
            .distinct().size == restored.size) { "Account registry has duplicate accounts" }
        return restored
    }

    @Synchronized
    fun all(): List<AccountRecord> {
        checkReadable()
        return records.values.toList()
    }

    @Synchronized
    fun get(userId: Long): AccountRecord? {
        checkReadable()
        return records[userId]
    }

    @Synchronized
    fun activeForStartup(): AccountRecord? {
        val active = activeUserId?.let(records::get)
        check(records.isEmpty() || active != null) { "Account registry has no active account" }
        return active
    }

    @Synchronized
    fun ensure(session: AuthSession, activate: Boolean = true): AccountRecord {
        require(session.userId.value > 0 && session.dcId > 0) { "Invalid account identity" }
        checkNoOrphanedStaging()
        val existing = records[session.userId.value]
        if (existing != null) {
            val updated = existing.copy(dcId = session.dcId)
            records[updated.userId] = updated
            if (activate) activeUserId = updated.userId
            persist()
            return updated
        }
        val record = AccountRecord(
            userId = session.userId.value,
            dcId = session.dcId,
            sessionPath = File(filesDir, "accounts/${session.userId.value}/mtproto.session.json"),
            databaseName = "monogram-account-${session.userId.value}.db",
            mediaPath = File(filesDir, "accounts/${session.userId.value}/media"),
        )
        records[record.userId] = record
        if (activate) activeUserId = record.userId
        persist()
        return record
    }

    @Synchronized
    fun adoptAuthorized(session: AuthSession): AccountRecord =
        if (records.isEmpty() || records[session.userId.value]?.databaseName == "monogram.db") {
            adoptLegacy(session)
        } else {
            ensure(session)
        }

    @Synchronized
    fun adoptLegacy(session: AuthSession): AccountRecord {
        require(session.userId.value > 0 && session.dcId > 0) { "Invalid account identity" }
        checkNoOrphanedStaging()
        val record = records[session.userId.value] ?: AccountRecord(
            userId = session.userId.value,
            dcId = session.dcId,
            sessionPath = File(filesDir, "mtproto.session.json"),
            databaseName = "monogram.db",
            mediaPath = File(filesDir, "media"),
        )
        records[record.userId] = record.copy(dcId = session.dcId)
        activeUserId = record.userId
        persist()
        return records[record.userId]!!
    }

    @Synchronized
    fun setActive(userId: Long) {
        require(records.containsKey(userId)) { "Unknown account: $userId" }
        checkNoOrphanedStaging()
        activeUserId = userId
        persist()
    }

    @Synchronized
    fun remove(userId: Long): AccountRecord? {
        checkNoOrphanedStaging()
        val removed = records.remove(userId)
        if (activeUserId == userId) activeUserId = records.keys.firstOrNull()
        if (removed != null) persist()
        return removed
    }

    private fun checkReadable() {
        check(!recoveryRequired) { "Account registry recovery required" }
    }

    private fun checkNoOrphanedStaging() {
        checkReadable()
        check(file.exists() || backup.exists() || !temporary.exists()) { "Account registry recovery required" }
    }

    private fun persist() {
        try {
            writeState()
        } catch (error: Exception) {
            try {
                load()
            } catch (recoveryError: Exception) {
                recoveryRequired = true
                error.addSuppressed(recoveryError)
            }
            throw error
        }
    }

    private fun writeState() {
        filesDir.mkdirs()
        val accounts = records.values.map(AccountRecord::toStored)
        val encoded = json.encodeToString(RegistryState(activeUserId ?: 0L, accounts))
        check(!backup.exists()) { "Account registry recovery required" }
        check(temporary.createNewFile()) { "Account registry recovery required" }
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) check(file.renameTo(backup)) { "Unable to back up account registry" }
        if (!temporary.renameTo(file)) {
            if (backup.exists()) check(backup.renameTo(file)) { "Unable to restore account registry" }
            error("Unable to persist account registry")
        }
        check(!backup.exists() || backup.delete()) { "Unable to finish account registry write" }
    }

    companion object {
        private const val FILE_NAME = "accounts.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class RegistryState(
    val activeUserId: Long = 0L,
    val accounts: List<StoredAccount> = emptyList(),
)

@Serializable
private data class StoredAccount(
    val userId: Long,
    val dcId: Int,
    val sessionPath: String,
    val databaseName: String,
    val mediaPath: String,
)

data class AccountRecord(
    val userId: Long,
    val dcId: Int,
    val sessionPath: File,
    val databaseName: String,
    val mediaPath: File,
)

private fun AccountRecord.toStored(): StoredAccount = StoredAccount(
    userId = userId,
    dcId = dcId,
    sessionPath = sessionPath.absolutePath,
    databaseName = databaseName,
    mediaPath = mediaPath.absolutePath,
)

private fun StoredAccount.toRecord(filesDir: File, allowLegacy: Boolean): AccountRecord? {
    if (userId <= 0L || dcId <= 0) return null
    val legacy = allowLegacy && databaseName == "monogram.db"
    val root = if (legacy) File(filesDir, "") else File(filesDir, "accounts/$userId")
    return AccountRecord(
        userId = userId,
        dcId = dcId,
        sessionPath = if (legacy) File(filesDir, "mtproto.session.json")
        else File(root, "mtproto.session.json"),
        databaseName = if (legacy) "monogram.db" else "monogram-account-$userId.db",
        mediaPath = if (legacy) File(filesDir, "media") else File(root, "media"),
    )
}
