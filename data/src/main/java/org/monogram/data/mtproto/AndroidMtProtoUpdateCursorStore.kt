package org.monogram.data.mtproto

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.mtproto.updates.MtProtoUpdateCursor

internal class AndroidMtProtoUpdateCursorStore(context: Context) : MtProtoUpdateCursorStore {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val mutex = Mutex()

    override suspend fun load(scope: MtProtoAuthKeyScope): MtProtoUpdateCursorLoadResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = file(scope)
                if (!file.baseFile.exists()) return@withLock MtProtoUpdateCursorLoadResult.Missing
                try {
                    val bytes = file.openRead().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64)
                        try {
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                require(output.size() + read <= MAX_FILE_BYTES) { "MTProto update cursor file exceeds limit" }
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        } finally {
                            buffer.fill(0)
                        }
                    }
                    try {
                        MtProtoUpdateCursorLoadResult.Found(MtProtoUpdateCursorCodec.decode(bytes))
                    } finally {
                        bytes.fill(0)
                    }
                } catch (_: Exception) {
                    file.delete()
                    MtProtoUpdateCursorLoadResult.Corrupt
                }
            }
        }

    override suspend fun save(scope: MtProtoAuthKeyScope, cursor: MtProtoUpdateCursor) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = file(scope)
                val parent = checkNotNull(target.baseFile.parentFile)
                check(parent.exists() || parent.mkdirs()) { "Unable to create MTProto update cursor directory" }
                val bytes = MtProtoUpdateCursorCodec.encode(cursor)
                try {
                    val output = target.startWrite()
                    try {
                        output.write(bytes)
                        target.finishWrite(output)
                    } catch (failure: Throwable) {
                        target.failWrite(output)
                        throw failure
                    }
                } finally {
                    bytes.fill(0)
                }
            }
        }

    override suspend fun delete(scope: MtProtoAuthKeyScope) = withContext(Dispatchers.IO) {
        mutex.withLock { file(scope).delete() }
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        withContext(Dispatchers.IO) {
            val accountDirectory = accountDirectory(MtProtoAuthKeyScope(accountSlot, environment, 1))
            mutex.withLock {
                accountDirectory.listFiles().orEmpty()
                    .filter { it.name.startsWith(DC_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
                    .forEach { AtomicFile(it).delete() }
                accountDirectory.delete()
                Unit
            }
        }

    private fun accountDirectory(scope: MtProtoAuthKeyScope) =
        File(File(directory, scope.environment.storageName), scope.accountSlot)

    private fun file(scope: MtProtoAuthKeyScope) =
        AtomicFile(File(accountDirectory(scope), "$DC_PREFIX${scope.dcId}$FILE_SUFFIX"))

    private companion object {
        const val DIRECTORY_NAME = "mtproto-updates"
        const val DC_PREFIX = "dc"
        const val FILE_SUFFIX = ".cursor"
        const val MAX_FILE_BYTES = 256
    }
}
