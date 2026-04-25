package org.monogram.presentation.features.chats.conversation.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

private const val SEARCH_DEBOUNCE_MS = 250L
private const val SEARCH_PAGE_SIZE = 20
private const val SEARCH_FETCH_ATTEMPTS = 8

private fun hasDateFilter(fromEpochSeconds: Int?, toEpochSeconds: Int?): Boolean {
    return fromEpochSeconds != null || toEpochSeconds != null
}

private fun DefaultChatComponent.hasSearchCriteria(state: org.monogram.presentation.features.chats.conversation.ChatComponent.State): Boolean {
    return state.searchQuery.isNotBlank() ||
            state.searchSender != null ||
            state.searchDateFromEpochSeconds != null ||
            state.searchDateToEpochSeconds != null
}

private fun DefaultChatComponent.hasMoreSearchResults(state: org.monogram.presentation.features.chats.conversation.ChatComponent.State): Boolean {
    return state.searchResults.size < state.searchResultsTotalCount ||
            state.searchNextFromMessageId != 0L
}

internal fun DefaultChatComponent.handleSearchToggle() {
    searchJob?.cancel()
    val isSearchActive = _state.value.isSearchActive
    _state.update {
        it.copy(
            isSearchActive = !isSearchActive,
            searchQuery = "",
            isSearchingMessages = false,
            searchResults = emptyList(),
            searchResultsTotalCount = 0,
            selectedSearchResultIndex = -1,
            searchNextFromMessageId = 0L,
            searchSender = null,
            searchDateFromEpochSeconds = null,
            searchDateToEpochSeconds = null
        )
    }
}

internal fun DefaultChatComponent.resetSearchState(isSearchActive: Boolean = _state.value.isSearchActive) {
    searchJob?.cancel()
    _state.update {
        it.copy(
            isSearchActive = isSearchActive,
            searchQuery = "",
            isSearchingMessages = false,
            searchResults = emptyList(),
            searchResultsTotalCount = 0,
            selectedSearchResultIndex = -1,
            searchNextFromMessageId = 0L,
            searchSender = null,
            searchDateFromEpochSeconds = null,
            searchDateToEpochSeconds = null
        )
    }
}

internal fun DefaultChatComponent.handleSearchQueryChange(query: String) {
    searchJob?.cancel()
    _state.update { it.copy(searchQuery = query) }

    val currentState = _state.value
    if (
        query.isBlank() &&
        currentState.searchSender == null &&
        currentState.searchDateFromEpochSeconds == null &&
        currentState.searchDateToEpochSeconds == null
    ) {
        _state.update {
            it.copy(
                isSearchingMessages = false,
                searchResults = emptyList(),
                searchResultsTotalCount = 0,
                selectedSearchResultIndex = -1,
                searchNextFromMessageId = 0L
            )
        }
        return
    }

    startSearch(query = query, sender = currentState.searchSender, withDebounce = true)
}

internal fun DefaultChatComponent.handleSearchSenderChange(user: UserModel?) {
    searchJob?.cancel()
    val currentQuery = _state.value.searchQuery
    _state.update { it.copy(searchSender = user) }

    if (
        currentQuery.isBlank() &&
        user == null &&
        _state.value.searchDateFromEpochSeconds == null &&
        _state.value.searchDateToEpochSeconds == null
    ) {
        _state.update {
            it.copy(
                isSearchingMessages = false,
                searchResults = emptyList(),
                searchResultsTotalCount = 0,
                selectedSearchResultIndex = -1,
                searchNextFromMessageId = 0L
            )
        }
        return
    }

    startSearch(query = currentQuery, sender = user, withDebounce = false)
}

internal fun DefaultChatComponent.handleSearchDateRangeChange(
    fromEpochSeconds: Int?,
    toEpochSeconds: Int?
) {
    searchJob?.cancel()
    _state.update {
        it.copy(
            searchDateFromEpochSeconds = fromEpochSeconds,
            searchDateToEpochSeconds = toEpochSeconds
        )
    }

    val updatedState = _state.value
    if (
        updatedState.searchQuery.isBlank() &&
        updatedState.searchSender == null &&
        fromEpochSeconds == null &&
        toEpochSeconds == null
    ) {
        _state.update {
            it.copy(
                isSearchingMessages = false,
                searchResults = emptyList(),
                searchResultsTotalCount = 0,
                selectedSearchResultIndex = -1,
                searchNextFromMessageId = 0L
            )
        }
        return
    }

    startSearch(
        query = updatedState.searchQuery,
        sender = updatedState.searchSender,
        fromEpochSeconds = fromEpochSeconds,
        toEpochSeconds = toEpochSeconds,
        withDebounce = false
    )
}

internal fun DefaultChatComponent.handleSearchNextResult() {
    val currentState = _state.value
    val results = currentState.searchResults
    if (results.isEmpty()) return

    val currentIndex = currentState.selectedSearchResultIndex.takeIf { it in results.indices } ?: 0
    val nextIndex = currentIndex + 1

    if (nextIndex < results.size) {
        handleSearchResultClick(nextIndex)
        return
    }

    val canLoadMore = hasMoreSearchResults(currentState)
    if (!canLoadMore) {
        handleSearchResultClick(0)
        return
    }

    scope.launch {
        val previousSize = _state.value.searchResults.size
        val updatedResults = appendMoreSearchResults() ?: return@launch
        val targetIndex = previousSize.coerceAtMost(updatedResults.lastIndex)
        if (targetIndex >= 0) {
            handleSearchResultClick(targetIndex)
        }
    }
}

internal fun DefaultChatComponent.handleSearchPreviousResult() {
    val results = _state.value.searchResults
    if (results.isEmpty()) return
    val currentIndex = _state.value.selectedSearchResultIndex.takeIf { it in results.indices } ?: 0
    val previousIndex = if (currentIndex == 0) results.lastIndex else currentIndex - 1
    handleSearchResultClick(previousIndex)
}

internal fun DefaultChatComponent.handleSearchResultClick(index: Int) {
    val results = _state.value.searchResults
    if (index !in results.indices) return
    _state.update { it.copy(selectedSearchResultIndex = index) }
    scrollToSearchResult(index, results)

    val stateAfterSelection = _state.value
    val isNearLoadedTail = index >= results.lastIndex - 2
    if (isNearLoadedTail && hasMoreSearchResults(stateAfterSelection) && !stateAfterSelection.isSearchingMessages) {
        scope.launch {
            appendMoreSearchResults()
        }
    }
}

internal fun DefaultChatComponent.loadMoreSearchResults() {
    searchJob?.cancel()
    searchJob = scope.launch {
        appendMoreSearchResults()
    }
}

private suspend fun DefaultChatComponent.appendMoreSearchResults(): List<MessageModel>? {
    val currentState = _state.value
    if (currentState.isSearchingMessages) return currentState.searchResults
    if (!hasSearchCriteria(currentState)) return currentState.searchResults
    if (!hasMoreSearchResults(currentState)) return currentState.searchResults

    val targetChatId = activeThreadChatId()
    val targetThreadId = activeThreadId()
    val query = currentState.searchQuery.trim()
    val senderId = currentState.searchSender?.id
    val fromEpochSeconds = currentState.searchDateFromEpochSeconds
    val toEpochSeconds = currentState.searchDateToEpochSeconds
    val isDateFiltered = hasDateFilter(fromEpochSeconds, toEpochSeconds)

    return try {
        _state.update { it.copy(isSearchingMessages = true) }

        var requestCursor: Long? = currentState.searchNextFromMessageId.takeIf { it != 0L }
        var latestState = currentState
        var mergedList = currentState.searchResults
        var totalCount = currentState.searchResultsTotalCount
        var shouldStopPaging = false

        repeat(SEARCH_FETCH_ATTEMPTS) {
            if (shouldStopPaging) return@repeat
            val fromMessageId = requestCursor
                ?: latestState.searchResults.lastOrNull()?.id
                ?: 0L

            val result = repositoryMessage.searchMessages(
                chatId = targetChatId,
                query = query,
                fromMessageId = fromMessageId,
                limit = SEARCH_PAGE_SIZE,
                threadId = targetThreadId,
                senderId = senderId
            )
            val filteredPage = result.messages.filterByDateRange(fromEpochSeconds, toEpochSeconds)

            val stillRelevant = _state.value.isSearchActive &&
                    _state.value.searchQuery.trim() == query &&
                    _state.value.searchSender?.id == senderId &&
                    _state.value.searchDateFromEpochSeconds == fromEpochSeconds &&
                    _state.value.searchDateToEpochSeconds == toEpochSeconds &&
                    activeThreadChatId() == targetChatId &&
                    activeThreadId() == targetThreadId
            if (!stillRelevant) return null

            val mergedResults =
                LinkedHashMap<Long, MessageModel>(mergedList.size + filteredPage.size)
            mergedList.forEach { mergedResults[it.id] = it }
            filteredPage.forEach { mergedResults[it.id] = it }
            val resolvedNextCursor = result.nextFromMessageId.takeIf { it != 0L }
                ?: result.messages.lastOrNull()?.id
                ?: 0L
            val shouldKeepPaging = resolvedNextCursor != 0L
            val updatedResults = mergedResults.values.toList()
            val didGrow = updatedResults.size > mergedList.size
            totalCount = if (isDateFiltered) {
                if (shouldKeepPaging) {
                    maxOf(totalCount, updatedResults.size + 1)
                } else {
                    updatedResults.size
                }
            } else {
                maxOf(totalCount, result.totalCount, updatedResults.size)
            }

            mergedList = updatedResults
            latestState = latestState.copy(
                searchResults = updatedResults,
                searchResultsTotalCount = totalCount,
                searchNextFromMessageId = if (shouldKeepPaging) resolvedNextCursor else 0L
            )

            shouldStopPaging = (!shouldKeepPaging) ||
                    resolvedNextCursor == fromMessageId ||
                    (!isDateFiltered && didGrow) ||
                    (isDateFiltered && didGrow && filteredPage.isNotEmpty())
            requestCursor = resolvedNextCursor.takeIf { !shouldStopPaging }
        }

        _state.update {
            if (
                it.isSearchActive &&
                it.searchQuery.trim() == query &&
                it.searchSender?.id == senderId &&
                it.searchDateFromEpochSeconds == fromEpochSeconds &&
                it.searchDateToEpochSeconds == toEpochSeconds &&
                activeThreadChatId() == targetChatId &&
                activeThreadId() == targetThreadId
            ) {
                latestState.copy(isSearchingMessages = false)
            } else {
                it
            }
        }
        mergedList
    } catch (e: CancellationException) {
        _state.update { it.copy(isSearchingMessages = false) }
        throw e
    } catch (_: Exception) {
        _state.update { it.copy(isSearchingMessages = false) }
        null
    }
}

private fun DefaultChatComponent.startSearch(
    query: String,
    sender: UserModel?,
    fromEpochSeconds: Int? = _state.value.searchDateFromEpochSeconds,
    toEpochSeconds: Int? = _state.value.searchDateToEpochSeconds,
    withDebounce: Boolean
) {
    val targetChatId = activeThreadChatId()
    val targetThreadId = activeThreadId()
    searchJob = scope.launch {
        try {
            _state.update { it.copy(isSearchingMessages = true) }
            if (withDebounce) {
                delay(SEARCH_DEBOUNCE_MS)
            }

            val normalizedQuery = query.trim()
            val senderId = sender?.id
            val result = repositoryMessage.searchMessages(
                chatId = targetChatId,
                query = normalizedQuery,
                fromMessageId = 0L,
                limit = SEARCH_PAGE_SIZE,
                threadId = targetThreadId,
                senderId = senderId
            )
            var filteredMessages =
                result.messages.filterByDateRange(fromEpochSeconds, toEpochSeconds)
            var nextCursor = result.nextFromMessageId
            val isDateFiltered = hasDateFilter(fromEpochSeconds, toEpochSeconds)
            var attempts = 0

            while (filteredMessages.size < SEARCH_PAGE_SIZE && nextCursor != 0L && attempts < SEARCH_FETCH_ATTEMPTS) {
                attempts++
                val nextResult = repositoryMessage.searchMessages(
                    chatId = targetChatId,
                    query = normalizedQuery,
                    fromMessageId = nextCursor,
                    limit = SEARCH_PAGE_SIZE,
                    threadId = targetThreadId,
                    senderId = senderId
                )
                filteredMessages = (filteredMessages + nextResult.messages.filterByDateRange(
                    fromEpochSeconds,
                    toEpochSeconds
                ))
                    .distinctBy(MessageModel::id)
                val resolvedNextCursor = nextResult.nextFromMessageId.takeIf { it != 0L }
                    ?: nextResult.messages.lastOrNull()?.id
                    ?: 0L
                if (resolvedNextCursor == nextCursor) break
                nextCursor = resolvedNextCursor
            }

            val resolvedTotalCount = if (isDateFiltered) {
                if (nextCursor != 0L) {
                    filteredMessages.size + 1
                } else {
                    filteredMessages.size
                }
            } else {
                maxOf(result.totalCount, filteredMessages.size)
            }

            val stillRelevant = _state.value.isSearchActive &&
                    _state.value.searchQuery.trim() == normalizedQuery &&
                    _state.value.searchSender?.id == senderId &&
                    _state.value.searchDateFromEpochSeconds == fromEpochSeconds &&
                    _state.value.searchDateToEpochSeconds == toEpochSeconds &&
                    activeThreadChatId() == targetChatId &&
                    activeThreadId() == targetThreadId
            if (!stillRelevant) return@launch

            _state.update {
                it.copy(
                    isSearchingMessages = false,
                    searchResults = filteredMessages,
                    searchResultsTotalCount = resolvedTotalCount,
                    selectedSearchResultIndex = if (filteredMessages.isNotEmpty()) 0 else -1,
                    searchNextFromMessageId = nextCursor
                )
            }

            if (filteredMessages.isNotEmpty()) {
                scrollToSearchResult(0, filteredMessages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update {
                if (it.searchQuery == query && it.searchSender?.id == sender?.id) {
                    it.copy(isSearchingMessages = false)
                } else {
                    it
                }
            }
        }
    }
}

private fun List<MessageModel>.filterByDateRange(
    fromEpochSeconds: Int?,
    toEpochSeconds: Int?
): List<MessageModel> {
    return filter { message ->
        val isAfterStart = fromEpochSeconds == null || message.date >= fromEpochSeconds
        val isBeforeEnd = toEpochSeconds == null || message.date <= toEpochSeconds
        isAfterStart && isBeforeEnd
    }
}

private fun DefaultChatComponent.scrollToSearchResult(index: Int, results: List<MessageModel>) {
    val message = results.getOrNull(index) ?: return
    scrollToMessage(message.id)
}
