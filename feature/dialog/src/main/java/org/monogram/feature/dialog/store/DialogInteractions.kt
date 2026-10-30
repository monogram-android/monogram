package org.monogram.feature.dialog.store

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.encodeLocationSend
import org.monogram.core.database.entity.OutgoingTextSendEntity
import org.monogram.core.database.toEntity
import org.monogram.core.models.ChecklistTextEdit
import org.monogram.core.models.ForumIo
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.StyledText
import org.monogram.core.models.poll

internal fun DialogExecutor.toggleChecklist(messageId: Int, itemId: Int) = mutateChecklist(
    messageId,
    allowed = { message ->
        val list = message.checklist
        list != null && (message.outgoing || list.othersCanComplete) && list.items.any { it.id == itemId }
    },
    request = { message ->
        val item = message.checklist!!.items.first { it.id == itemId }
        if (!item.done) client.toggleTodoCompleted(chatId, messageId, listOf(itemId), emptyList())
        else client.toggleTodoCompleted(chatId, messageId, emptyList(), listOf(itemId))
    },
)

internal fun DialogExecutor.removeChecklistItem(original: Message, itemId: Int) {
    val item = original.checklist?.items?.singleOrNull { it.id == itemId } ?: return
    mutateChecklist(original.id.id, allowed = { row ->
        snapshot().canCreateChecklist && row.id == original.id && row.outgoing && row.checklist?.let { list -> list.items.size > 1 && list.items.singleOrNull { it.id == itemId } == item } == true
    }, request = {
        client.removeChecklistItem(
            chatId, original.id.id, ChecklistTextEdit(
                itemId, "", emptyList(), item.text, item.entities
            )
        )
    }, reconciled = { row ->
        val current = snapshot()
        if (row?.checklist?.items?.none { it.id == itemId } == true && current.editing?.id == original.id && current.editingChecklistItemId == itemId) finishComposerEdit()
    })
}

internal fun DialogExecutor.editChecklistText(
    editing: Message, styled: StyledText, itemId: Int?, revision: Long
) {
    val current = snapshot()
    if (styled.text.isBlank() || current.sending || !current.canCreateChecklist || current.editing?.id != editing.id || current.editingChecklistItemId != itemId || current.composerRevision != revision) return
    val original = editing.checklist ?: return
    val originalItem = itemId?.let { id -> original.items.singleOrNull { it.id == id } }
    if (itemId != null && originalItem == null) return
    val expectedText = originalItem?.text ?: original.title
    val expectedEntities = originalItem?.entities ?: original.entities
    val spec = ChecklistTextEdit(
        itemId, styled.text, styled.entities, expectedText, expectedEntities
    )
    mutateChecklist(
        editing.id.id,
        allowed = { row ->
            val list = row.checklist
            val item = itemId?.let { id -> list?.items?.singleOrNull { it.id == id } }
            snapshot().canCreateChecklist && row.outgoing && list != null && (itemId == null || item != null) && (item?.text
                ?: list.title) == expectedText && (item?.entities
                ?: list.entities) == expectedEntities
        },
        request = { client.editChecklistText(chatId, editing.id.id, spec) },
        reconciled = { row ->
            val list = row?.checklist
            val item = itemId?.let { id -> list?.items?.singleOrNull { it.id == id } }
            val latest = snapshot()
            if (list != null && (itemId == null || item != null) && (item?.text
                    ?: list.title) == styled.text && (item?.entities
                    ?: list.entities) == styled.entities && latest.editing?.id == editing.id && latest.editingChecklistItemId == itemId
            ) {
                if (latest.composerRevision == revision) finishComposerEdit()
                else if (row != null) emit(Msg.Editing(row, itemId))
            }
        },
    )
}

private fun DialogExecutor.mutateChecklist(
    messageId: Int, allowed: (Message) -> Boolean, request: suspend (Message) -> Outcome<Unit>,
    reconciled: (Message?) -> Unit = {},
) {
    work.launch {
        val state = snapshot()
        if (!state.canView || messageId <= 0 || messageId in state.checklistMutations) return@launch
        val message = state.messages.firstOrNull { it.id.id == messageId && it.id.chatId == chatId }
            ?: return@launch
        if (message.pending || !allowed(message)) return@launch
        val epoch = warmup?.outgoingAccountEpoch()
        emit(Msg.ChecklistMutating(messageId, true))
        try {
            val result = request(message)
            if (epoch != null && warmup?.outgoingAccountIsCurrent(epoch) != true) return@launch
            if (result is Outcome.Err) handleError(result.telegramError, false)
            when (val refreshed = client.getMessage(chatId, messageId)) {
                is Outcome.Err -> if (result is Outcome.Ok) handleError(
                    refreshed.telegramError, false
                )

                is Outcome.Ok -> {
                    val row = refreshed.value
                    if (row != null && row.id != message.id) return@launch
                    if (epoch != null && warmup?.applyMessageRead(
                            epoch, chatId, messageId, row
                        ) != true
                    ) return@launch
                    if (row == null) emit(Msg.MessagesDeleted(setOf(messageId))) else emit(
                        Msg.MessageEdited(
                            row
                        )
                    )
                    reconciled(row)
                }
            }
        } finally {
            emit(Msg.ChecklistMutating(messageId, false))
        }
    }
}

internal fun pollVoteAllowed(message: Message, options: List<ByteArray>): Boolean {
    val poll = message.poll ?: return false
    if (message.id.id <= 0 || message.pending || poll.closed || poll.isAnswered && (poll.isQuiz || poll.revotingDisabled)) return false
    if (options.isEmpty()) return poll.isAnswered && !poll.isQuiz
    if (!poll.multipleChoice && options.size > 1) return false
    if (options.map { it.toList() }.distinct().size != options.size) return false
    return options.all { option -> poll.answers.any { it.option.contentEquals(option) } }
}

internal fun DialogExecutor.refreshPoll(pollId: Long) {
    val state = snapshot()
    if (!state.canView || (state.messages + state.pinnedMessages).none { it.poll?.id == pollId }) return
    pendingPollRefreshes += pollId
    if (pollRefreshJob?.isActive == true) return
    pollRefreshJob = work.launch {
        try {
            while (pendingPollRefreshes.isNotEmpty()) {
                val id = pendingPollRefreshes.first()
                pendingPollRefreshes.remove(id)
                val current = snapshot()
                if (!current.canView) continue
                val epoch = warmup?.outgoingAccountEpoch()
                val messages = (current.messages + current.pinnedMessages).distinctBy { it.id }
                    .filter { it.id.chatId == chatId && it.id.id > 0 && !it.pending && it.poll?.id == id }
                for (message in messages) {
                    val result = client.getMessage(chatId, message.id.id)
                    if (epoch != null && warmup?.outgoingAccountIsCurrent(epoch) != true) return@launch
                    val now = snapshot()
                    if (!now.canView) continue
                    val latest =
                        (now.messages + now.pinnedMessages).firstOrNull { it.id == message.id }
                            ?: continue
                    if (latest.fileName != message.fileName) continue
                    when (result) {
                        is Outcome.Err -> handleError(result.telegramError, false)
                        is Outcome.Ok -> {
                            val row = result.value
                            if (row != null && (row.id != message.id || row.poll?.id != id)) continue
                            if (epoch != null && warmup?.applyMessageRead(
                                    epoch, chatId, message.id.id, row
                                ) != true
                            ) return@launch
                            if (row == null) emit(Msg.MessagesDeleted(setOf(message.id.id))) else emit(
                                Msg.MessageEdited(row)
                            )
                        }
                    }
                }
            }
        } finally {
            pendingPollRefreshes.clear()
            pollRefreshJob = null
        }
    }
}

internal fun DialogExecutor.closePoll(messageId: Int) = mutatePoll(messageId, null)

internal fun DialogExecutor.votePoll(messageId: Int, options: List<ByteArray>) =
    mutatePoll(messageId, options)

private fun DialogExecutor.mutatePoll(messageId: Int, options: List<ByteArray>?) {
    val requested = options?.map { it.copyOf() }
    work.launch {
        val state = snapshot()
        if (!state.canView || messageId in state.pollVotesInFlight) return@launch
        val message = state.messages.firstOrNull { it.id.id == messageId && it.id.chatId == chatId }
            ?: return@launch
        if (requested == null) {
            val poll = message.poll ?: return@launch
            if (message.id.id <= 0 || message.pending || poll.closed || !poll.creator) return@launch
        } else if (!pollVoteAllowed(message, requested)) return@launch
        val epoch = warmup?.outgoingAccountEpoch()
        emit(Msg.PollVoting(messageId, true))
        try {
            val result =
                if (requested == null) client.closePoll(chatId, messageId) else client.sendPollVote(
                    chatId, messageId, requested
                )
            if (epoch != null && warmup?.outgoingAccountIsCurrent(epoch) != true) return@launch
            if (result is Outcome.Err) handleError(result.telegramError, false)
            when (val refreshed = client.getMessage(chatId, messageId)) {
                is Outcome.Err -> if (result is Outcome.Ok) handleError(
                    refreshed.telegramError, false
                )

                is Outcome.Ok -> {
                    val row = refreshed.value
                    if (row != null && row.id != message.id) return@launch
                    if (epoch != null && warmup?.applyMessageRead(
                            epoch, chatId, messageId, row
                        ) != true
                    ) return@launch
                    if (row == null) emit(Msg.MessagesDeleted(setOf(messageId))) else emit(
                        Msg.MessageEdited(
                            row
                        )
                    )
                }
            }
        } finally {
            emit(Msg.PollVoting(messageId, false))
        }
    }
}

internal fun DialogExecutor.appendChecklistItems(
    messageId: Int, firstId: Int, titles: List<String>
) {
    val cleaned = titles.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty() || firstId < 0 || firstId.toLong() + cleaned.lastIndex > Int.MAX_VALUE) return
    mutateChecklist(
        messageId,
        allowed = { message ->
            val list = message.checklist
            list != null && (message.outgoing || list.othersCanAppend) && firstId.toLong() == (list.items.maxOfOrNull { it.id }
                ?.toLong() ?: 0L) + 1
        },
        request = { client.appendChecklistItems(chatId, messageId, firstId, cleaned) },
    )
}

internal fun DialogExecutor.sendLocation(
    latitude: Double, longitude: Double, livePeriodSeconds: Int
) {
    val cache = warmup ?: return
    val current = snapshot()
    if (current.sending || !current.canView || !current.canSendPlain || current.blockedByMe || current.left || !current.canSendComposerContent || current.editing != null || current.forwardMessage != null || current.pendingAttach.isNotEmpty()) return
    val place = GeoPlace(
        latitude, longitude, livePeriodSeconds > 0, livePeriodSeconds
    )
    val payload = try {
        encodeLocationSend(place)
    } catch (_: IllegalArgumentException) {
        return
    }
    val epoch = cache.outgoingAccountEpoch()
    emit(Msg.Sending(true))
    work.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        try {
            val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, current.composerReplyId)
            val randomId = pendingRandomId()
            val pending = Message(
                MessageId(chatId, pendingMessageId()),
                null,
                null,
                System.currentTimeMillis() / 1000,
                outgoing = true,
                pending = true,
                randomId = randomId,
                mediaKind = "geo",
                fileName = payload,
                replyToMsgId = replyId.takeIf { it > 0 },
                replyToTopId = topId.takeIf { it > 0 },
                forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0
            )
            withContext(kotlinx.coroutines.NonCancellable) {
                cache.enqueueOutgoingTextSend(
                    OutgoingTextSendEntity(
                        randomId,
                        pending.toEntity(),
                        silent = false,
                        noWebpage = false,
                        scheduleDate = null,
                        webpageUrl = null
                    ), epoch, consumeDraft = false
                )
            }
            if (cache.outgoingAccountIsCurrent(epoch) && snapshot().composerRevision == current.composerRevision) setComposerReply(
                null
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            handleError(TelegramError.parse("RPC 500: INTERNAL"), false)
        } finally {
            emit(Msg.Sending(false))
        }
    }
}

internal fun DialogExecutor.pressBotButton(
    messageId: Int, button: ReplyButton, fromKeyboard: Boolean
) {
    when (button.type) {
        ReplyButtonType.Text -> {
            if (!snapshot().canSendPlain) return
            applyDraft(button.text)
            send()
            if (fromKeyboard) {
                val keyboard = ReplyMarkups.latestBotKeyboard(snapshot().messages)
                if (keyboard?.singleUse == true) {
                    emit(Msg.KeyboardDismissedKey(ReplyMarkups.serialize(keyboard)))
                }
            }
        }

        ReplyButtonType.Url -> emit(Msg.BotUrl(button.url))
        ReplyButtonType.Copy -> emit(Msg.CopyText(button.copyText ?: button.text))
        ReplyButtonType.SwitchInline -> {
            if (!button.samePeer) {
                emit(Msg.BotNotice("inline-switch"))
                return
            }
            val senderId = snapshot().messages.firstOrNull { it.id.id == messageId }?.senderId
            val cachedUsername = senderId?.let { snapshot().senders[it]?.username }
            work.launch {
                val username = cachedUsername ?: senderId?.let { id ->
                    when (val profile = client.getProfile(id)) {
                        is Outcome.Ok -> {
                            sessionStore?.upsertProfile(profile.value)
                            profile.value.username
                        }

                        is Outcome.Err -> null
                    }
                }
                if (username.isNullOrBlank()) {
                    emit(Msg.BotNotice("inline-switch"))
                    return@launch
                }
                val query = button.query.orEmpty()
                applyDraft("@$username${query.takeIf { it.isNotBlank() }?.let { " $it" } ?: " "}")
            }
        }

        ReplyButtonType.User -> emit(Msg.BotUrl(button.url?.let { "tg://user?id=$it" }
            ?: button.url))

        ReplyButtonType.Disabled -> Unit
        ReplyButtonType.WebApp, ReplyButtonType.Login, ReplyButtonType.Game, ReplyButtonType.Pay, ReplyButtonType.RequestContact, ReplyButtonType.RequestLocation, ReplyButtonType.RequestPoll, ReplyButtonType.RequestPeer -> emit(
            Msg.BotNotice(button.type.name.lowercase())
        )

        ReplyButtonType.Callback -> {
            if (button.requiresPassword) {
                emit(Msg.BotNotice("callback-password"))
                return
            }
            val data = button.dataHex.orEmpty()
            if (data.isEmpty() || messageId <= 0) return
            work.launch {
                when (val result = client.getBotCallbackAnswer(chatId, messageId, data)) {
                    is Outcome.Ok -> {
                        result.value.url?.let { emit(Msg.BotUrl(it)) }
                        result.value.message?.let {
                            emit(Msg.BotNotice(it, alert = result.value.alert))
                        }
                    }

                    is Outcome.Err -> handleError(result.telegramError, false)
                }
            }
        }

        ReplyButtonType.Other -> Unit
    }
}