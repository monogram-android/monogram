package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.GifModel
import org.monogram.domain.repository.GifRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.Document_be725c3b31
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetSavedGifs
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SavedGifs_ed772ead35

/** Selected-backend saved GIF reads backed by persisted document locations and opaque file handles. */
internal class MtProtoGifRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val locations: MtProtoDocumentLocationStore,
    private val files: MtProtoFileRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : GifRepository {
    override fun getGifFile(gif: GifModel): Flow<String?> = flow {
        val fileId = gif.fileId.toIntExact()
        files.getPath(fileId)?.let {
            emit(it)
            return@flow
        }
        val result = coroutineScope {
            val completed = async(start = CoroutineStart.UNDISPATCHED) {
                files.fileDownloadFlow
                    .filter { it.fileId == fileId }
                    .first { it is FileDownloadEvent.Completed || it is FileDownloadEvent.Cancelled }
            }
            files.download(fileId, offset = 0L, limit = 0L)
            completed.await()
        }
        emit((result as? FileDownloadEvent.Completed)?.path)
    }

    override fun getGifThumbnailFile(fileId: Long): Flow<String?> = flow { emit(null) }

    override suspend fun getSavedGifs(): List<GifModel> {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val result = transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetSavedGifs(0)) as? SavedGifs_ed772ead35
                ?: error("Unsupported MTProto saved GIF response")
        }
        return result.gifs.mapNotNull { it as? Document_be725c3b31 }.mapNotNull { document ->
            locations.upsert(scope, document)
            val file = files.registerDocument(document.id) ?: return@mapNotNull null
            val video = document.attributes.filterIsInstance<DocumentAttributeVideo>().firstOrNull()
            GifModel(
                id = document.id.toString(),
                fileId = file.fileId.toLong(),
                thumbFileId = null,
                width = video?.w ?: 0,
                height = video?.h ?: 0,
            )
        }
    }

    override suspend fun addSavedGif(path: String): Nothing = unsupported("GIF upload")

    override suspend fun searchGifs(query: String): Nothing = unsupported("GIF search")

    private fun Long.toIntExact(): Int {
        val value = toInt()
        require(value.toLong() == this) { "MTProto GIF handle is invalid: $this" }
        return value
    }

    private fun unsupported(operation: String): Nothing = throw UnsupportedOperationException(
        "MTProto $operation is not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
