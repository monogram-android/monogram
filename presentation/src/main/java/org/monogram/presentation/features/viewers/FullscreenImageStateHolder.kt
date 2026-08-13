package org.monogram.presentation.features.viewers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.repository.FileRepository

class FullscreenImageStateHolder(
    initialItems: List<FullscreenImageItem>,
    private val fileRepository: FileRepository,
    private val scope: CoroutineScope
) {
    private val _items = MutableStateFlow(initialItems)
    val items: StateFlow<List<FullscreenImageItem>> = _items.asStateFlow()

    init {
        scope.launch {
            fileRepository.fileDownloadFlow.collectLatest(::handleFileEvent)
        }
    }

    fun updateItems(items: List<FullscreenImageItem>) {
        _items.value = items.map { incoming ->
            val current = _items.value.firstOrNull { it.id == incoming.id }
            if (current?.originalFileId == incoming.originalFileId && current.originalPath != null) {
                incoming.copy(
                    originalPath = current.originalPath,
                    loadState = FullscreenImageLoadState.Ready
                )
            } else if (current?.originalFileId == incoming.originalFileId &&
                current.loadState is FullscreenImageLoadState.Loading
            ) {
                incoming.copy(loadState = current.loadState)
            } else {
                incoming
            }
        }
    }

    fun requestPage(index: Int) {
        requestOriginal(index)
        requestOriginal(index + 1)
    }

    fun retry(id: String) {
        val index = _items.value.indexOfFirst { it.id == id }
        if (index >= 0) requestOriginal(index, force = true)
    }

    fun markDecodeError(id: String) {
        update(id) { item ->
            if (item.originalPath != null) {
                item.copy(originalPath = null, loadState = FullscreenImageLoadState.Error)
            } else {
                item
            }
        }
    }

    private fun requestOriginal(index: Int, force: Boolean = false) {
        val item = _items.value.getOrNull(index) ?: return
        if (!item.hasOriginalTarget) return
        if (!force && item.loadState is FullscreenImageLoadState.Loading) return

        update(item.id) { it.copy(loadState = FullscreenImageLoadState.Loading(0f)) }
        scope.launch {
            val cached = fileRepository.getFileInfo(item.originalFileId)
            val cachedPath = cached?.local?.path?.takeIf {
                cached.local.isDownloadingCompleted && it.isNotBlank()
            }
            if (cachedPath != null) {
                markReady(item.originalFileId, cachedPath)
                return@launch
            }

            fileRepository.downloadFile(
                fileId = item.originalFileId,
                priority = 32,
                userInitiated = true
            )
        }
    }

    private fun handleFileEvent(event: FileDownloadEvent) {
        when (event) {
            is FileDownloadEvent.Progress -> updateByFileId(event.fileId) {
                if (it.originalPath != null || it.loadState is FullscreenImageLoadState.Ready) {
                    it
                } else {
                    it.copy(
                        loadState = FullscreenImageLoadState.Loading(
                            event.progress.coerceIn(
                                0f,
                                1f
                            )
                        )
                    )
                }
            }

            is FileDownloadEvent.Completed -> {
                if (event.path.isBlank()) markError(event.fileId) else markReady(
                    event.fileId,
                    event.path
                )
            }

            is FileDownloadEvent.Cancelled -> markError(event.fileId)
        }
    }

    private fun markReady(fileId: Int, path: String) {
        updateByFileId(fileId) {
            it.copy(originalPath = path, loadState = FullscreenImageLoadState.Ready)
        }
    }

    private fun markError(fileId: Int) {
        updateByFileId(fileId) { it.copy(loadState = FullscreenImageLoadState.Error) }
    }

    private fun updateByFileId(
        fileId: Int,
        transform: (FullscreenImageItem) -> FullscreenImageItem
    ) {
        _items.value = _items.value.map { item ->
            if (item.originalFileId == fileId) transform(item) else item
        }
    }

    private fun update(id: String, transform: (FullscreenImageItem) -> FullscreenImageItem) {
        _items.value = _items.value.map { item -> if (item.id == id) transform(item) else item }
    }
}
