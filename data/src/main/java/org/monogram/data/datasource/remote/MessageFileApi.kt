package org.monogram.data.datasource.remote

interface MessageFileApi {
    fun registerFileForMessage(fileId: Int, chatId: Long, messageId: Long)
    fun registerSponsoredFileForMessage(fileId: Int, chatId: Long, messageId: Long)
    fun enqueueDownload(
        fileId: Int,
        priority: Int = 1,
        type: TdMessageRemoteDataSource.DownloadType = TdMessageRemoteDataSource.DownloadType.DEFAULT,
        offset: Long = 0,
        limit: Long = 0,
        synchronous: Boolean = false
    )
    fun isFileQueued(fileId: Int): Boolean
}