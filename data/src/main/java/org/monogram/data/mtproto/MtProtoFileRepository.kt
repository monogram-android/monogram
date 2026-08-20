package org.monogram.data.mtproto

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.monogram.data.db.dao.MtProtoFileTransferDao
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.FileLocalModel
import org.monogram.domain.models.FileModel
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.mtproto.tl.generated.cloud.layer223.InputDocumentFileLocation
import org.monogram.mtproto.tl.runtime.TlBytes

internal data class MtProtoDocumentFile(
    val fileId: Int,
    val fileName: String,
    val mimeType: String,
    val size: Long,
)

/**
 * Owns selected-backend document downloads. Public handles remain opaque and only resolve inside
 * the active account scope; protocol document IDs and locations never cross this boundary.
 */
internal interface MtProtoFileRepository {
    val fileDownloadFlow: Flow<FileDownloadEvent>
    val messageDownloadFlow: Flow<MessageDownloadEvent>

    suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long): MtProtoDocumentFile?
    fun download(fileId: Int, offset: Long, limit: Long)
    suspend fun cancel(fileId: Int)
    suspend fun getPath(fileId: Int): String?
    suspend fun getInfo(fileId: Int): FileModel?
}

internal class MtProtoDocumentFileRepository(
    private val context: Context,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val handles: MtProtoFileHandleStore,
    private val locations: MtProtoDocumentLocationStore,
    private val transfers: MtProtoFileTransferDao,
    private val coordinator: MtProtoFileTransferCoordinator,
    private val scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : MtProtoFileRepository {
    private val events = MutableSharedFlow<FileDownloadEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val messageEvents = MutableSharedFlow<MessageDownloadEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val downloads = ConcurrentHashMap<Int, Job>()
    private val messageReferences = ConcurrentHashMap<Int, MutableSet<Pair<Long, Long>>>()

    override val fileDownloadFlow: Flow<FileDownloadEvent> = events.asSharedFlow()
    override val messageDownloadFlow: Flow<MessageDownloadEvent> = messageEvents.asSharedFlow()

    override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long): MtProtoDocumentFile? {
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val location = locations.get(scope, documentId) ?: return null
        val handle = handles.getOrCreate(
            scope,
            MtProtoFileResourceKey(MtProtoFileResourceType.DOCUMENT, documentId),
        ).fileId
        messageReferences.computeIfAbsent(handle) { ConcurrentHashMap.newKeySet() }.add(chatId to messageId)
        return MtProtoDocumentFile(
            fileId = handle,
            fileName = location.fileName,
            mimeType = location.mimeType,
            size = location.size,
        )
    }

    override fun download(fileId: Int, offset: Long, limit: Long) {
        require(offset >= 0L) { "MTProto file offset must not be negative" }
        require(limit >= 0L) { "MTProto file limit must not be negative" }
        require(offset == 0L && limit == 0L) {
            "MTProto partial file downloads are not available"
        }
        var created = false
        val download = downloads.compute(fileId) { _, current ->
            current?.takeIf { it.isActive } ?: scope.launch(start = CoroutineStart.LAZY) {
                try {
                    downloadDocument(fileId)
                } finally {
                    coroutineContext[Job]?.let { downloads.remove(fileId, it) }
                }
            }.also { created = true }
        }
        if (created) download?.start()
    }

    override suspend fun cancel(fileId: Int) {
        downloads.remove(fileId)?.cancel()
        emit(FileDownloadEvent.Cancelled(fileId))
    }

    override suspend fun getPath(fileId: Int): String? = getInfo(fileId)
        ?.local
        ?.takeIf { it.isDownloadingCompleted }
        ?.path

    override suspend fun getInfo(fileId: Int): FileModel? {
        val resolved = resolve(fileId) ?: return null
        val transfer = transfers.get(
            accountSlot = resolved.scope.accountSlot,
            environment = resolved.scope.environment.storageName,
            dcId = resolved.location.documentDcId,
            fileKey = resolved.location.documentId.toString(),
        )
        val path = transfer?.path.orEmpty()
        val completed = transfer?.isComplete == true && path.isNotBlank() && File(path).isFile
        val downloaded = transfer?.committedOffset ?: 0L
        return FileModel(
            id = fileId,
            size = resolved.location.size,
            expectedSize = resolved.location.size,
            local = FileLocalModel(
                path = path,
                isDownloadingActive = downloads[fileId]?.isActive == true,
                canBeDownloaded = !completed,
                isDownloadingCompleted = completed,
                downloadOffset = 0L,
                downloadedPrefixSize = downloaded,
                downloadedSize = downloaded,
            ),
            remote = null,
        )
    }

    private suspend fun downloadDocument(fileId: Int) {
        val resolved = requireNotNull(resolve(fileId)) { "Unknown MTProto file handle: $fileId" }
        val path = outputFile(resolved.scope, fileId).absolutePath
        val sink = ProgressSink(
            delegate = MtProtoRoomFileTransferSink(
                dao = transfers,
                accountSlot = resolved.scope.accountSlot,
                environment = resolved.scope.environment.storageName,
                dcId = resolved.location.documentDcId,
                fileKey = resolved.location.documentId.toString(),
                path = path,
                expectedSize = resolved.location.size,
            ),
            fileId = fileId,
            expectedSize = resolved.location.size,
            emit = ::emit,
        )
        emit(FileDownloadEvent.Progress(fileId, sink.progress()))
        try {
            coordinator.download(
                location = InputDocumentFileLocation(
                    id = resolved.location.documentId,
                    accessHash = resolved.location.accessHash,
                    fileReference = TlBytes.copyOf(resolved.location.fileReference),
                    thumbSize = "",
                ),
                sink = sink,
                dcId = resolved.location.documentDcId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        emit(FileDownloadEvent.Completed(fileId, path))
    }

    private suspend fun emit(event: FileDownloadEvent) {
        events.emit(event)
        messageReferences[event.fileId]?.forEach { (chatId, messageId) ->
            messageEvents.emit(
                when (event) {
                    is FileDownloadEvent.Progress -> MessageDownloadEvent.Progress(chatId, messageId, event.fileId, event.progress)
                    is FileDownloadEvent.Completed -> MessageDownloadEvent.Completed(chatId, messageId, event.fileId, event.path)
                    is FileDownloadEvent.Cancelled -> MessageDownloadEvent.Cancelled(chatId, messageId, event.fileId)
                }
            )
        }
    }

    private suspend fun resolve(fileId: Int): ResolvedDocument? {
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val handle = handles.get(scope, fileId) ?: return null
        if (handle.resource.type != MtProtoFileResourceType.DOCUMENT) return null
        val location = locations.get(scope, handle.resource.id) ?: return null
        return ResolvedDocument(scope, location)
    }

    private fun outputFile(scope: MtProtoAuthKeyScope, fileId: Int): File = File(
        context.filesDir,
        "mtproto/files/${scope.environment.storageName}/${scope.accountSlot}/${scope.dcId}/$fileId.bin",
    ).also { it.parentFile?.mkdirs() }

    private data class ResolvedDocument(
        val scope: MtProtoAuthKeyScope,
        val location: MtProtoDocumentLocation,
    )

    private class ProgressSink(
        private val delegate: MtProtoFileTransferSink,
        private val fileId: Int,
        private val expectedSize: Long,
        private val emit: suspend (FileDownloadEvent) -> Unit,
    ) : MtProtoFileTransferSink {
        override suspend fun committedOffset(): Long = delegate.committedOffset()

        override suspend fun write(offset: Long, bytes: ByteArray) {
            delegate.write(offset, bytes)
            emit(FileDownloadEvent.Progress(fileId, progress()))
        }

        override suspend fun complete(totalBytes: Long) = delegate.complete(totalBytes)

        suspend fun progress(): Float = committedOffset().toProgress(expectedSize)
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
        const val EVENT_BUFFER_CAPACITY = 32

        fun Long.toProgress(expectedSize: Long): Float = when {
            expectedSize <= 0L -> 0f
            else -> (toFloat() / expectedSize).coerceIn(0f, 1f)
        }
    }
}
