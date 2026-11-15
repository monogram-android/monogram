package org.monogram.root

import kotlinx.coroutines.CancellationException
import org.monogram.core.common.AppLog
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.RetainedMediaSource
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.entity.UpdateCursorEntity
import org.monogram.network.bridge.DurableUpdateBatch
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.push.NotificationReadGuard

internal class AccountDurableUpdateSink(
    private val cache: OfflineWarmup,
) {
    suspend fun commit(batch: DurableUpdateBatch): Boolean {
        val cursor = batch.cursor?.let {
            UpdateCursorEntity(
                SessionMetadataStore.KEY_PRIMARY_CURSOR,
                it.pts,
                it.qts,
                it.date,
                it.seq,
            )
        }
        val releasedSources = mutableListOf<RetainedMediaSource>()
        val committed = NotificationReadGuard.withAccount(cache) {
            cache.applyDurableBatch(
                batch.accountGeneration,
                batch.batchId,
                cursor,
                batch.channelCursors,
            ) {
                val receipts =
                    batch.updates.filterIsInstance<MtprotoUpdate.OutgoingMessageAcknowledged>()
                val receiptIds = receipts.map { it.randomId }.toSet()
                val media = if (receiptIds.isEmpty()) {
                    emptyList()
                } else {
                    cache.outgoingMediaSends().filter { send ->
                        send.items.any { it.randomId in receiptIds }
                    }
                }
                val mediaIds = media.flatMap { send -> send.items.map { it.randomId } }.toSet()
                var index = 0
                while (index < batch.updates.size) {
                    val update = batch.updates[index]
                    if (update is MtprotoUpdate.NewMessage) {
                        val messages = mutableListOf(update.message)
                        index++
                        while (index < batch.updates.size && messages.size < 100) {
                            val next = batch.updates[index] as? MtprotoUpdate.NewMessage ?: break
                            messages += next.message
                            index++
                        }
                        cache.applyIncomingMessages(
                            messages, batch.accountGeneration, batch.batchId
                        )
                    } else {
                        index++
                        if (update is MtprotoUpdate.OutgoingMessageAcknowledged && update.randomId in mediaIds) continue
                        apply(update, batch.accountGeneration, batch.batchId)
                    }
                }
                media.forEach { send ->
                    val ids = send.items.map { it.randomId }.toSet()
                    cache.applyOutgoingMediaResults(
                        send.batch.batchId,
                        receipts.filter { it.randomId in ids }.map { it.randomId to it.result },
                        cache.outgoingAccountEpoch(),
                        batch.accountGeneration,
                        batch.batchId,
                    )
                    releasedSources += send.items.map { it.source }
                }
            }
        }
        if (committed && releasedSources.isNotEmpty()) {
            try {
                cache.outgoingMediaFiles?.release(releasedSources)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppLog.warn("outgoing", "retained media cleanup deferred")
            }
        }
        return committed
    }

    private suspend fun apply(
        update: MtprotoUpdate,
        generation: Long,
        order: Long,
    ) {
        when (update) {
            is MtprotoUpdate.NewMessage -> {
                cache.applyIncomingMessage(
                    update.message,
                    generation,
                    order,
                )
            }

            is MtprotoUpdate.OutgoingMessageAcknowledged -> {
                cache.applyOutgoingResult(
                    update.randomId,
                    update.result,
                    generation,
                    order,
                )
            }

            is MtprotoUpdate.ScheduledMessageChanged -> {
                cache.applyScheduledMessage(update.message)
            }

            is MtprotoUpdate.ScheduledMessagesDeleted -> {
                cache.deleteScheduledMessages(
                    update.chatId,
                    update.messageIds,
                )
            }

            is MtprotoUpdate.DraftChanged -> {
                cache.applyCloudDraft(update.draft)
            }

            is MtprotoUpdate.MessageEdited -> {
                cache.applyMessageEdit(update.message)
            }

            is MtprotoUpdate.MessagesDeleted -> {
                cache.deleteMessages(
                    update.chatId,
                    update.messageIds,
                )
            }

            is MtprotoUpdate.MessageReactions -> {
                cache.applyReactions(
                    update.chatId,
                    update.messageId,
                    update.reactionsJson,
                )
            }

            is MtprotoUpdate.ReadOutbox -> {
                cache.applyOutboxRead(update.chatId, update.maxId)
            }

            is MtprotoUpdate.DiscussionInbox -> {
                cache.applyDiscussionRead(
                    update.channelId,
                    update.topMessageId,
                    update.readMaxId,
                )
            }

            is MtprotoUpdate.ReadInbox -> {
                cache.applyInboxRead(
                    update.chatId,
                    update.maxId,
                    update.stillUnread,
                )
            }

            is MtprotoUpdate.ChatsChanged -> {
                cache.upsertChats(update.chats)
            }

            is MtprotoUpdate.Ignored -> {
                when {
                    update.kind == "difference_too_long" -> {
                        cache.invalidateCachedHistories()
                    }

                    update.kind == "channel_difference_too_long" || update.kind.startsWith("channel_difference_too_long:") -> {
                        val chatId = update.kind.substringAfter(':', "").toLongOrNull()
                        require(chatId != null && chatId != 0L) { "Invalid channel history recovery marker" }
                        cache.invalidateCachedHistory(chatId)
                    }
                }
            }

            is MtprotoUpdate.UnreadMentionsDelta -> {
                cache.addUnreadMentions(
                    update.chatId,
                    update.delta,
                )
            }

            is MtprotoUpdate.UnreadReactionsDelta -> {
                cache.addUnreadReactions(
                    update.chatId,
                    update.delta,
                )
            }

            else -> {
                Unit
            }
        }
    }
}
