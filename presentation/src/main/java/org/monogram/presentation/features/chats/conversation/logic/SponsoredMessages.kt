package org.monogram.presentation.features.chats.conversation.logic

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.SponsoredMessageModel
import org.monogram.domain.models.SponsoredMessagesFeedModel
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

internal data class ChannelSponsoredRequestContext(
    val isChannel: Boolean,
    val isGroup: Boolean,
    val isBot: Boolean,
    val currentTopicId: Long?,
    val rootMessageId: Long?,
    val viewAsTopics: Boolean,
    val isPremium: Boolean,
    val showSponsoredMessagesForPremium: Boolean
)

internal fun shouldRequestChannelSponsoredMessage(context: ChannelSponsoredRequestContext): Boolean {
    if (!context.isChannel) return false
    if (context.isGroup) return false
    if (context.isBot) return false
    if (context.currentTopicId != null) return false
    if (context.rootMessageId != null) return false
    if (context.viewAsTopics) return false
    return !context.isPremium || context.showSponsoredMessagesForPremium
}

internal fun DefaultChatComponent.observeSponsoredMessagePolicy() {
    _state
        .map { state ->
            ChannelSponsoredRequestContext(
                isChannel = state.isChannel,
                isGroup = state.isGroup,
                isBot = state.isBot,
                currentTopicId = state.currentTopicId,
                rootMessageId = state.rootMessage?.id,
                viewAsTopics = state.viewAsTopics,
                isPremium = state.currentUser?.isPremium == true,
                showSponsoredMessagesForPremium = state.showSponsoredMessagesForPremium
            )
        }
        .distinctUntilChanged()
        .onEach(::refreshSponsoredMessageIfNeeded)
        .launchIn(scope)
}

internal fun DefaultChatComponent.refreshSponsoredMessageIfNeeded(
    context: ChannelSponsoredRequestContext = ChannelSponsoredRequestContext(
        isChannel = _state.value.isChannel,
        isGroup = _state.value.isGroup,
        isBot = _state.value.isBot,
        currentTopicId = _state.value.currentTopicId,
        rootMessageId = _state.value.rootMessage?.id,
        viewAsTopics = _state.value.viewAsTopics,
        isPremium = _state.value.currentUser?.isPremium == true,
        showSponsoredMessagesForPremium = _state.value.showSponsoredMessagesForPremium
    )
) {
    sponsoredMessageLoadingJob?.cancel()
    if (!shouldRequestChannelSponsoredMessage(context)) {
        _state.update { it.copy(channelSponsoredMessages = null) }
        return
    }

    sponsoredMessageLoadingJob = scope.launch {
        val sponsoredMessages =
            runCatching { repositoryMessage.getChannelSponsoredMessages(chatId) }
                .getOrNull()
        _state.update { current ->
            val mergedSponsoredMessages = sponsoredMessages?.mergeWithExistingMediaPaths(
                existing = current.channelSponsoredMessages
            )
            if (
                shouldRequestChannelSponsoredMessage(
                    ChannelSponsoredRequestContext(
                        isChannel = current.isChannel,
                        isGroup = current.isGroup,
                        isBot = current.isBot,
                        currentTopicId = current.currentTopicId,
                        rootMessageId = current.rootMessage?.id,
                        viewAsTopics = current.viewAsTopics,
                        isPremium = current.currentUser?.isPremium == true,
                        showSponsoredMessagesForPremium = current.showSponsoredMessagesForPremium
                    )
                )
            ) {
                current.copy(channelSponsoredMessages = mergedSponsoredMessages)
            } else {
                current.copy(channelSponsoredMessages = null)
            }
        }
    }
}

internal fun DefaultChatComponent.refreshSponsoredMessageAfterMediaDownload(
    messageId: Long,
    fileId: Int,
    path: String
) {
    val current = _state.value
    val currentMessages = current.channelSponsoredMessages?.messages.orEmpty()
    val affected = currentMessages.firstOrNull { sponsored ->
        when (val content = sponsored.content) {
            is MessageContent.Photo -> content.fileId == fileId
            is MessageContent.Video -> content.fileId == fileId
            is MessageContent.Gif -> content.fileId == fileId
            else -> false
        }
    } ?: return

    _state.update { currentState ->
        val feed = currentState.channelSponsoredMessages ?: return@update currentState
        val patchedFeed = feed.patchSponsoredMediaPath(fileId = fileId, path = path)
        if (patchedFeed === feed) {
            currentState
        } else {
            currentState.copy(channelSponsoredMessages = patchedFeed)
        }
    }
    sponsoredMessageLoadingJob?.cancel()
    sponsoredMessageLoadingJob = scope.launch {
        delay(300)
        refreshSponsoredMessageIfNeeded()
    }
}

private fun SponsoredMessagesFeedModel.patchSponsoredMediaPath(
    fileId: Int,
    path: String
): SponsoredMessagesFeedModel {
    var changed = false
    val updatedMessages = messages.map { message ->
        val updatedMessage = message.patchSponsoredMediaPath(fileId = fileId, path = path)
        if (updatedMessage !== message) {
            changed = true
        }
        updatedMessage
    }
    return if (changed) {
        copy(messages = updatedMessages)
    } else {
        this
    }
}

private fun SponsoredMessagesFeedModel.mergeWithExistingMediaPaths(
    existing: SponsoredMessagesFeedModel?
): SponsoredMessagesFeedModel {
    val existingMessages = existing?.messages.orEmpty()
    if (existingMessages.isEmpty()) return this

    var changed = false
    val mergedMessages = messages.map { incoming ->
        val current = existingMessages.firstOrNull { it.messageId == incoming.messageId }
            ?: return@map incoming
        val merged = incoming.preserveExistingMediaPath(current)
        if (merged !== incoming) {
            changed = true
        }
        merged
    }
    return if (changed) {
        copy(messages = mergedMessages)
    } else {
        this
    }
}

private fun SponsoredMessageModel.patchSponsoredMediaPath(
    fileId: Int,
    path: String
): SponsoredMessageModel {
    val updatedContent = when (val currentContent = content) {
        is MessageContent.Photo -> {
            if (currentContent.fileId != fileId || currentContent.path == path) {
                currentContent
            } else {
                currentContent.copy(
                    path = path,
                    isDownloading = false,
                    downloadProgress = 1f,
                    downloadError = false
                )
            }
        }

        is MessageContent.Video -> {
            if (currentContent.fileId != fileId || currentContent.path == path) {
                currentContent
            } else {
                currentContent.copy(
                    path = path,
                    isDownloading = false,
                    downloadProgress = 1f,
                    downloadError = false
                )
            }
        }

        is MessageContent.Gif -> {
            if (currentContent.fileId != fileId || currentContent.path == path) {
                currentContent
            } else {
                currentContent.copy(
                    path = path,
                    isDownloading = false,
                    downloadProgress = 1f,
                    downloadError = false
                )
            }
        }

        else -> content
    }
    return if (updatedContent === content) {
        this
    } else {
        copy(content = updatedContent)
    }
}

private fun SponsoredMessageModel.preserveExistingMediaPath(
    existing: SponsoredMessageModel
): SponsoredMessageModel {
    val incomingContent = content
    val existingContent = existing.content
    val mergedContent = when {
        incomingContent is MessageContent.Photo && existingContent is MessageContent.Photo -> {
            if (incomingContent.path.isNullOrBlank() && !existingContent.path.isNullOrBlank() && incomingContent.fileId == existingContent.fileId) {
                incomingContent.copy(path = existingContent.path)
            } else {
                incomingContent
            }
        }

        incomingContent is MessageContent.Video && existingContent is MessageContent.Video -> {
            if (incomingContent.path.isNullOrBlank() && !existingContent.path.isNullOrBlank() && incomingContent.fileId == existingContent.fileId) {
                incomingContent.copy(path = existingContent.path)
            } else {
                incomingContent
            }
        }

        incomingContent is MessageContent.Gif && existingContent is MessageContent.Gif -> {
            if (incomingContent.path.isNullOrBlank() && !existingContent.path.isNullOrBlank() && incomingContent.fileId == existingContent.fileId) {
                incomingContent.copy(path = existingContent.path)
            } else {
                incomingContent
            }
        }

        else -> incomingContent
    }

    return if (mergedContent === incomingContent) {
        this
    } else {
        copy(content = mergedContent)
    }
}
