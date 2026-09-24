package org.monogram.feature.dialog.store

import com.arkivanov.mvikotlin.core.store.Reducer
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.MessageId
import org.monogram.core.models.Presence
import org.monogram.core.models.TypingPresence
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.mergeLiveEdgeMessages
import org.monogram.feature.dialog.mergeOrReplaceMessages
import org.monogram.feature.dialog.pinnedIndexAfterReload
import org.monogram.feature.dialog.removeDeletedMessages

internal object DialogReducer : Reducer<DialogStore.State, Msg> {
    override fun DialogStore.State.reduce(msg: Msg): DialogStore.State = when (msg) {
        is Msg.Loading -> copy(loading = msg.value)
        is Msg.LoadingOlder -> copy(
            loadingOlder = msg.value,
            prefetchingOlder = msg.value && msg.prefetch,
        )
        is Msg.LoadingNewer -> copy(loadingNewer = msg.value)
        is Msg.Sending -> copy(sending = msg.value)
        is Msg.Title -> copy(title = msg.value)
        is Msg.IsSelf -> copy(isSelf = msg.value)
        is Msg.IsGroup -> copy(isGroup = msg.value)
        is Msg.IsChannel -> copy(isChannel = msg.value)
        is Msg.IsForum -> copy(isForum = msg.value)
        is Msg.Senders -> copy(senders = senders + msg.value)
        is Msg.SenderTags -> copy(senderTags = msg.value)
        is Msg.Draft -> if (draft == msg.value) this else copy(draft = msg.value)
        is Msg.PendingAttach -> copy(pendingAttach = msg.items)
        is Msg.LinkPreview -> copy(
            linkPreview = msg.preview,
            linkPreviewUrl = msg.url,
            linkPreviewFixed = msg.fixed,
            linkPreviewLoading = msg.loading,
            linkPreviewHidden = msg.hidden,
        )
        is Msg.ReplyTo -> copy(
            replyTo = msg.value,
            composerFocusSeq = if (msg.focusComposer) composerFocusSeq + 1 else composerFocusSeq,
        )
        is Msg.Editing -> copy(editing = msg.value)
        is Msg.ForwardMessage -> copy(forwardMessage = msg.value)
        is Msg.ForwardTargets -> copy(forwardTargets = msg.value)
        is Msg.ForwardQuery -> copy(forwardQuery = msg.value)
        is Msg.Forwarding -> copy(forwarding = msg.value)
        is Msg.ForwardHint -> copy(forwardHint = msg.value)
        is Msg.Rights -> copy(
            canView = msg.canView,
            canSendPlain = msg.canSendPlain,
            canSendPhotos = msg.canSendPhotos,
            canForward = msg.canForward,
            canDeleteOthers = msg.canDeleteOthers,
        )
        is Msg.SearchQuery -> copy(searchQuery = msg.value)
        is Msg.Searching -> copy(searching = msg.value)
        is Msg.Messages -> copy(
            messages = Presence.withReadState(
                filterThreadMessages(
                    visibleMessages(
                        mergeById(
                            if (msg.liveEdge && messages.isNotEmpty()) {
                                mergeLiveEdgeMessages(messages, msg.value)
                            } else {
                                mergeOrReplaceMessages(
                                    current = messages,
                                    incoming = msg.value,
                                    replace = msg.replace || msg.fromCache || messages.isEmpty(),
                                )
                            },
                        ),
                    ),
                    threadTopId,
                ),
                readOutboxMaxId,
            ),
            fromCache = msg.fromCache,
            error = if (msg.fromCache) error else null,
            hasOlder = when {
                msg.searchHit -> false
                msg.replace -> true
                else -> hasOlder
            },
            hasNewer = when {
                msg.liveEdge || msg.searchHit -> false
                msg.replace -> true
                msg.fromCache -> hasNewer
                else -> false
            },
        )
        is Msg.Prepend -> copy(messages = filterThreadMessages(mergeById(msg.value + messages), threadTopId))
        is Msg.AppendOlder -> copy(messages = filterThreadMessages(mergeById(messages + msg.value), threadTopId))
        is Msg.ReplacePending -> copy(
            messages = mergeById(
                messages.map { if (it.id.id == msg.pendingId) msg.sent else it },
            ),
        )
        is Msg.BindPending -> copy(
            messages = mergeById(
                messages.map { message ->
                    if (message.randomId == msg.randomId &&
                        (message.pending || message.failed || message.id.id < 0)
                    ) {
                        message.copy(
                            id = MessageId(message.id.chatId, msg.messageId),
                            pending = false,
                            failed = false,
                        )
                    } else {
                        message
                    }
                },
            ),
        )
        is Msg.MessageEdited -> copy(
            messages = applyMessageEdit(messages, msg.value),
            pinnedMessages = applyMessageEdit(pinnedMessages, msg.value),
        )
        is Msg.MessagesDeleted -> copy(
            messages = removeDeletedMessages(messages, msg.ids),
            pinnedMessages = removeDeletedMessages(pinnedMessages, msg.ids),
            pinnedIndex = pinnedIndex.coerceAtMost(
                (pinnedMessages.count { it.id.id !in msg.ids } - 1).coerceAtLeast(0),
            ),
        )
        is Msg.Append -> copy(
            messages = Presence.withReadState(
                filterThreadMessages(upsertMessage(messages, msg.value), threadTopId),
                readOutboxMaxId,
            ),
        )
        is Msg.HasOlder -> copy(hasOlder = msg.value)
        is Msg.HasNewer -> copy(hasNewer = msg.value)
        is Msg.PinnedListOpen -> copy(pinnedListOpen = msg.value)
        is Msg.UnreadCount -> copy(unreadCount = msg.value)
        is Msg.UnreadMentionsCount -> copy(unreadMentionsCount = msg.value.coerceAtLeast(0))
        is Msg.UnreadReactionsCount -> copy(unreadReactionsCount = msg.value.coerceAtLeast(0))
        is Msg.ReadInbox -> copy(readInboxMaxId = msg.value)
        is Msg.Pinned -> copy(
            pinnedMessages = msg.value,
            pinnedIndex = pinnedIndexAfterReload(
                pinnedMessages.getOrNull(pinnedIndex)?.id?.id,
                msg.value,
            ),
        )
        is Msg.PinnedIndex -> copy(pinnedIndex = msg.value)
        is Msg.Error -> copy(error = msg.value)
        is Msg.Typing -> {
            val next = if (msg.active) {
                val name = msg.name?.takeIf { it.isNotBlank() }
                    ?: typingUsers[msg.userId]?.name.orEmpty()
                val action = msg.action.ifBlank {
                    typingUsers[msg.userId]?.action ?: ChatActionKind.Typing.wire
                }
                (typingUsers - msg.userId) + (msg.userId to TypingPresence(name, action))
            } else {
                typingUsers - msg.userId
            }
            copy(typingUsers = next, typing = next.isNotEmpty())
        }
        is Msg.PeerStatus -> copy(peerStatus = msg.status, peerStatusAt = msg.at)
        is Msg.EmojiStatus -> copy(emojiStatusDocumentId = msg.documentId)
        is Msg.ChatProfile -> copy(
            membersCount = msg.members ?: membersCount,
            onlineCount = if (msg.authoritative) msg.online else (msg.online ?: onlineCount),
        )
        is Msg.ReadOutbox -> copy(
            readOutboxMaxId = msg.value,
            messages = Presence.withReadState(messages, msg.value),
        )
        is Msg.ReadReceiptConfig -> copy(readReceiptConfig = msg.value)
        is Msg.Viewers -> copy(messageViewers = messageViewers + (msg.messageId to msg.value))
        is Msg.ReactionUsers -> copy(reactionUsers = reactionUsers + (msg.messageId to msg.value))
        is Msg.PollVoters -> copy(pollVoters = pollVoters + (msg.messageId to msg.value))
        is Msg.OutboxRead -> copy(outboxReadStates = outboxReadStates + (msg.messageId to msg.value))
        is Msg.MarkFailed -> copy(
            messages = messages.map {
                if (it.id.id == msg.pendingId) it.copy(pending = false, failed = true) else it
            },
        )
        is Msg.Drop -> copy(messages = messages.filterNot { it.id.id == msg.messageId })
        is Msg.PhotoCacheKey -> copy(photoCacheKey = msg.value)
        is Msg.Anchor -> copy(anchorMessageId = msg.value, anchorAtTop = msg.atTop)
        is Msg.SavedGifs -> copy(
            savedGifs = msg.value,
            savedGifsLoaded = true,
            savedGifsError = msg.error,
        )
        is Msg.SavedGifsLoaded -> copy(savedGifsLoaded = msg.value)
        is Msg.GifPicker -> copy(gifPickerOpen = msg.open)
        is Msg.ComposerPanel -> copy(composerPanel = msg.value)
        is Msg.EmojiTab -> copy(emojiTab = msg.value)
        is Msg.StickerSets -> copy(
            stickerSets = msg.value,
            stickerSetsLoaded = true,
            stickerSetsError = msg.error,
        )
        is Msg.EmojiSets -> copy(
            emojiSets = msg.value,
            emojiSetsLoaded = true,
            emojiSetsError = msg.error,
        )
        is Msg.StickerPackLoading -> copy(
            loadingStickerPackIds = loadingStickerPackIds + msg.setId,
            failedStickerPackIds = failedStickerPackIds - msg.setId,
        )
        is Msg.StickerPackLoaded -> copy(
            openStickerPack = msg.value,
            loadedStickerPacks = loadedStickerPacks + (msg.value.id to msg.value),
            loadingStickerPackIds = loadingStickerPackIds - msg.value.id,
            failedStickerPackIds = failedStickerPackIds - msg.value.id,
        )
        is Msg.StickerPackFailed -> copy(
            loadingStickerPackIds = loadingStickerPackIds - msg.setId,
            failedStickerPackIds = failedStickerPackIds + msg.setId,
        )
        is Msg.Reactions -> copy(
            messages = messages.map {
                if (it.id.id == msg.messageId) it.copy(reactionsJson = msg.json) else it
            },
        )
        is Msg.PendingChat -> copy(pendingChatId = msg.chatId, pendingChatMessageId = msg.messageId)
        is Msg.DiscussionUnread -> copy(discussionUnread = msg.value)
        is Msg.RecentReactions -> copy(recentReactions = msg.value)
        is Msg.Topics -> {
            val merged = if (msg.append) {
                val seen = topics.map { it.id }.toMutableSet()
                topics + msg.value.filter { seen.add(it.id) }
            } else {
                msg.value
            }
            copy(topics = merged, topicsCount = msg.count)
        }
        is Msg.LoadingTopics -> copy(loadingTopics = msg.value)
        is Msg.HasMoreTopics -> copy(hasMoreTopics = msg.value)
        is Msg.TopicClosed -> copy(
            topicClosed = msg.value,
            canSendPlain = canSendPlain && !msg.value,
        )
        is Msg.TopicHeader -> copy(
            title = msg.title.ifBlank { title },
            topicIconColor = msg.iconColor,
            topicIconEmojiId = msg.iconEmojiId,
        )
        is Msg.BotNotice -> copy(botNotice = msg.text, botAlert = msg.alert && msg.text != null)
        is Msg.BotUrl -> copy(botUrl = msg.value)
        is Msg.CopyText -> copy(copyText = msg.value)
        is Msg.KeyboardDismissedKey -> copy(keyboardDismissedKey = msg.value)
        is Msg.InlineQuery -> copy(inlineQuery = msg.value)
        is Msg.InlinePage -> copy(
            inlineResults = msg.results,
            inlineLoading = msg.loading,
            inlineError = msg.error,
        )
        is Msg.MentionQuery -> copy(mentionToken = msg.value)
        is Msg.MentionPage -> copy(
            mentionCandidates = msg.candidates,
            mentionLoading = msg.loading,
            mentionHasMore = msg.hasMore,
        )
        is Msg.DraftMentions -> copy(draftMentions = msg.value)
    }
}
