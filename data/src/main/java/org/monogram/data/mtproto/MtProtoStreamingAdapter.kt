package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.repository.StreamingRepository

internal class MtProtoStreamingAdapter(
    private val mtProtoFactory: () -> MtProtoStreamingRepository,
) : StreamingRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    override fun getDownloadProgress(fileId: Int): Flow<Float> = mtProto.getDownloadProgress(fileId)
}
