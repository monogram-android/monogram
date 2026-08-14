package org.monogram.data.datasource.remote

import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.mapper.toDomain

class MessageFileCoordinator(
    private val fileDownloadQueue: FileDownloadQueue
) : MessageFileApi {

    override fun registerFileForMessage(fileId: Int, chatId: Long, messageId: Long) {
        fileDownloadQueue.registry.register(fileId, chatId, messageId)
    }

    override fun registerFileForMessage(
        fileId: Int,
        chatId: Long,
        messageId: Long,
        type: TdMessageRemoteDataSource.DownloadType,
        descriptor: FileDownloadQueue.MediaDescriptor?
    ) {
        fileDownloadQueue.registerFileForMessage(
            fileId,
            chatId,
            messageId,
            type.toDomain(),
            descriptor
        )
    }

    override fun registerSponsoredFileForMessage(fileId: Int, chatId: Long, messageId: Long) {
        fileDownloadQueue.registry.registerSponsored(fileId, chatId, messageId)
    }

    override fun enqueueDownload(
        fileId: Int,
        priority: Int,
        type: TdMessageRemoteDataSource.DownloadType,
        offset: Long,
        limit: Long,
        synchronous: Boolean
    ) {
        fileDownloadQueue.enqueue(
            fileId = fileId,
            priority = priority,
            type = type.toDomain(),
            offset = offset,
            limit = limit,
            synchronous = synchronous
        )
    }

    override fun isFileQueued(fileId: Int): Boolean {
        return fileDownloadQueue.isFileQueued(fileId)
    }
}