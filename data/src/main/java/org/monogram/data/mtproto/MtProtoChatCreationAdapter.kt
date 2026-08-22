package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoChatCreationRepository
import org.monogram.data.mtproto.MtProtoDatabaseSizeReader
import org.monogram.domain.repository.ChatCreationRepository

internal class MtProtoChatCreationAdapter(
    private val mtProtoFactory: () -> MtProtoChatCreationRepository,
    private val mtProtoDatabaseSizeReaderFactory: () -> MtProtoDatabaseSizeReader,
    private val storageCleanupFactory: () -> MtProtoStorageCleanupRepository,
) : ChatCreationRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    private val mtProtoDatabaseSizeReader by lazy(LazyThreadSafetyMode.NONE, mtProtoDatabaseSizeReaderFactory)
    private val storageCleanup by lazy(LazyThreadSafetyMode.NONE, storageCleanupFactory)

    override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int) = mtProto.createGroup(title, userIds, messageAutoDeleteTime)
    override suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int) = mtProto.createChannel(title, description, isMegagroup, messageAutoDeleteTime)
    override fun getDatabaseSize() = mtProtoDatabaseSizeReader.sizeBytes()
    override fun clearDatabase() {
        // Clears all completed download files; the closest MTProto equivalent to the
        // TDLib database maintenance operation.
        kotlinx.coroutines.runBlocking { storageCleanup.clearCompletedDownloads(chatId = null) }
    }
}
