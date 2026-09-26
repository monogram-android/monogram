package org.monogram.network.bridge.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.bridge.session.dispatchClassName
import org.monogram.network.bridge.session.nativeRequest
import uniffi.monogram_mtproto.UpdateEventDto
import org.monogram.network.bridge.chat.toChatModels
import org.monogram.network.bridge.chat.toModel as toFolderModel
import org.monogram.network.bridge.message.toModel as toMessageModel
import org.monogram.network.bridge.profile.toModel as toProfileModel

internal class UpdatesPump(private val core: SessionCore) : UpdatesOps {

    init {
        core.scope.launch { drainUpdatesLoop() }
    }

    override suspend fun getUpdatesState(): Outcome<UpdatesCursor> = core.coalesce("getUpdatesState") {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> connected
            is Outcome.Ok -> core.rpc("getUpdatesState failed") { activeHandle ->
                val state = core.native.getUpdatesState(activeHandle)
                UpdatesCursor(
                    pts = state.pts,
                    qts = state.qts,
                    date = state.date,
                    seq = state.seq,
                )
            }
        }
    }

    private suspend fun drainUpdatesLoop() {
        var drainDelayMs = 25L
        var premiumRefreshPending = false
        var chatsRefreshPending = false
        var foldersRefreshPending = false
        val lastMetadataRefreshMs = AtomicLong(-1L)
        val lastChatsRefreshMs = AtomicLong(-1L)
        val lastPremiumRefreshMs = AtomicLong(-1L)
        val foldersInFlight = AtomicBoolean(false)
        val chatsInFlight = AtomicBoolean(false)
        val premiumInFlight = AtomicBoolean(false)
        var cooldownHandle = 0L
        fun cooldownDue(last: AtomicLong, windowMs: Long, now: Long): Boolean {
            val value = last.get()
            return value < 0L || now - value >= windowMs
        }
        while (true) {
            val activeHandle = core.activeHandleOrZero()
            // A network failure clears connectedHandle while the native handle
            // remains allocated. Wait for connect() to re-establish the handle
            // instead of repeatedly polling a dead transport.
            if (activeHandle == 0L || core.sessionDead || core.connectedHandle != activeHandle) {
                // `close()` shuts the wake channel down; a closed channel ends the loop
                // instead of failing this coroutine with an uncaught exception.
                try {
                    core.updatesWake.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ClosedReceiveChannelException) {
                    return
                }
                continue
            }
            if (activeHandle != cooldownHandle) {
                cooldownHandle = activeHandle
                lastMetadataRefreshMs.set(-1L)
                lastChatsRefreshMs.set(-1L)
                lastPremiumRefreshMs.set(-1L)
            }
            val events = try {
                val drainAt = if (PerfLog.isEnabled()) System.nanoTime() else 0L
                val drained = nativeRequest(
                    core.native,
                    core.nativeDispatcher,
                    DispatchClass.BACKGROUND_READ
                ) {
                    core.native.drainUpdates(activeHandle)
                }
                if (drained.isNotEmpty()) {
                    PerfLog.noteUpdateDrain(drained.size)
                }
                if (drainAt != 0L && drained.isNotEmpty()) {
                    PerfLog.trace(
                        op = "updates",
                        phase = "drain",
                        elapsedMs = (System.nanoTime() - drainAt) / 1_000_000,
                        handle = activeHandle,
                        dispatchClass = DispatchClass.BACKGROUND_READ,
                        result = "ok",
                        detail = "count=${drained.size} lane=${dispatchClassName(DispatchClass.BACKGROUND_READ)}",
                    )
                }
                drainDelayMs = 25L
                if (core.isCurrentHandle(activeHandle)) drained else emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (core.isCurrentHandle(activeHandle)) {
                    core.fail(e, "drain updates failed")
                    drainDelayMs = (drainDelayMs * 2).coerceIn(4_000L, 30_000L)
                }
                emptyList()
            }
            for (event in events) {
                when (event) {
                    is UpdateEventDto.ChatsChanged -> {
                        chatsRefreshPending = true
                        premiumRefreshPending = true
                    }

                    is UpdateEventDto.FoldersChanged -> {
                        foldersRefreshPending = true
                    }

                    is UpdateEventDto.NewMessage -> {
                        AppLog.api(
                            "updates",
                            "newMessage chat=${event.message.chatId} id=${event.message.id}",
                        )
                        core.updatesEvents.emit(MtprotoUpdate.NewMessage(event.message.toMessageModel()))
                    }

                    is UpdateEventDto.MessageEdited -> {
                        AppLog.api(
                            "updates",
                            "messageEdited chat=${event.message.chatId} id=${event.message.id}",
                        )
                        core.updatesEvents.emit(MtprotoUpdate.MessageEdited(event.message.toMessageModel()))
                    }

                    is UpdateEventDto.MessagesDeleted -> core.updatesEvents.emit(
                        MtprotoUpdate.MessagesDeleted(
                            chatId = event.chatId?.let(::PeerId),
                            messageIds = event.messageIds,
                        ),
                    )

                    is UpdateEventDto.PeerTyping -> core.updatesEvents.emit(
                        MtprotoUpdate.PeerTyping(
                            chatId = PeerId(event.chatId),
                            userId = PeerId(event.userId),
                            typing = event.typing,
                            action = event.action.ifBlank {
                                if (event.typing) "typing" else ""
                            },
                        ),
                    )

                    is UpdateEventDto.PeerEmojiStatus -> core.updatesEvents.emit(
                        MtprotoUpdate.PeerEmojiStatus(
                            userId = PeerId(event.userId),
                            documentId = event.documentId,
                        ),
                    )

                    is UpdateEventDto.PeerStatus -> core.updatesEvents.emit(
                        MtprotoUpdate.PeerStatus(
                            userId = PeerId(event.userId),
                            status = event.status,
                            statusAt = event.statusAt,
                        ),
                    )

                    is UpdateEventDto.ReadInbox -> {
                        AppLog.api(
                            "read-state",
                            "server chat=${event.chatId} max=${event.maxId} unread=${event.stillUnread}"
                        )
                        core.updatesEvents.emit(
                            MtprotoUpdate.ReadInbox(
                                chatId = PeerId(event.chatId),
                                maxId = event.maxId,
                                stillUnread = event.stillUnread,
                            )
                        )
                    }

                    is UpdateEventDto.ReadOutbox -> core.updatesEvents.emit(
                        MtprotoUpdate.ReadOutbox(
                            chatId = PeerId(event.chatId),
                            maxId = event.maxId,
                        ),
                    )

                    is UpdateEventDto.Ignored -> {
                        when {
                            event.kind.startsWith("dialog_unread_mark:") -> {
                                val parts = event.kind.split(':')
                                val chatId = parts.getOrNull(1)?.toLongOrNull()
                                val unread = parts.getOrNull(2) == "1"
                                if (chatId != null) {
                                    core.updatesEvents.emit(
                                        MtprotoUpdate.DialogUnreadMark(PeerId(chatId), unread),
                                    )
                                }
                            }
                            event.kind.startsWith("notify_settings:") -> {
                                val parts = event.kind.split(':')
                                val peerKind = parts.getOrNull(1).orEmpty()
                                val chatId = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                                val muteUntil = parts.getOrNull(3)?.toIntOrNull() ?: 0
                                if (peerKind.isNotEmpty()) {
                                    core.updatesEvents.emit(
                                        MtprotoUpdate.NotifySettingsChanged(
                                            peerKind = peerKind,
                                            chatId = PeerId(chatId),
                                            muteUntil = muteUntil,
                                        ),
                                    )
                                }
                            }
                            event.kind.startsWith("UpdateMessageId:") -> {
                                AppLog.api("updates", "message id mapped")
                                core.updatesEvents.emit(MtprotoUpdate.Ignored(event.kind))
                            }
                            event.kind.startsWith("unread_mentions_delta:") -> {
                                val parts = event.kind.split(':')
                                val chatId = parts.getOrNull(1)?.toLongOrNull()
                                val delta = parts.getOrNull(2)?.toIntOrNull() ?: 1
                                if (chatId != null) {
                                    core.updatesEvents.emit(
                                        MtprotoUpdate.UnreadMentionsDelta(PeerId(chatId), delta),
                                    )
                                }
                            }
                            event.kind.startsWith("unread_reactions_delta:") -> {
                                val parts = event.kind.split(':')
                                val chatId = parts.getOrNull(1)?.toLongOrNull()
                                val delta = parts.getOrNull(2)?.toIntOrNull() ?: 1
                                if (chatId != null) {
                                    core.updatesEvents.emit(
                                        MtprotoUpdate.UnreadReactionsDelta(PeerId(chatId), delta),
                                    )
                                }
                            }
                            else -> {
                                AppLog.api("updates", "ignored ${event.kind}")
                                core.updatesEvents.emit(MtprotoUpdate.Ignored(event.kind))
                            }
                        }
                    }

                    is UpdateEventDto.SavedGifsChanged -> core.updatesEvents.emit(MtprotoUpdate.SavedGifsChanged)
                    is UpdateEventDto.MessageReactions -> core.updatesEvents.emit(
                        MtprotoUpdate.MessageReactions(
                            chatId = PeerId(event.chatId),
                            messageId = event.messageId,
                            reactionsJson = event.reactionsJson,
                        ),
                    )

                    is UpdateEventDto.DiscussionInbox -> core.updatesEvents.emit(
                        MtprotoUpdate.DiscussionInbox(
                            channelId = PeerId(event.channelId),
                            topMessageId = event.topMessageId,
                            readMaxId = event.readMaxId,
                        ),
                    )
                }
            }
            // Chat metadata events carry no self-user flags. Coalesce the fallback
            // lookup after delivering messages and bound it during busy updates.
            val now = core.clock.elapsedMs()
            val refreshHandle = activeHandle
            if (foldersRefreshPending &&
                cooldownDue(lastMetadataRefreshMs, 2_000L, now) &&
                foldersInFlight.compareAndSet(false, true)
            ) {
                core.scope.launch {
                    try {
                        val folders = core.onNativeIfFree {
                            core.native.getFolders(it).map { dto -> dto.toFolderModel() }
                        }
                        if (folders != null && core.isCurrentHandle(refreshHandle)) {
                            lastMetadataRefreshMs.set(core.clock.elapsedMs())
                            foldersRefreshPending = false
                            core.updatesEvents.emit(MtprotoUpdate.FoldersChanged(folders))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val raw = when (e) {
                            is uniffi.monogram_mtproto.MtprotoException.Message -> e.v1
                            else -> e.message
                        }?.removePrefix("v1=")?.trim().orEmpty()
                        val telegram = TelegramError.parse(
                            raw.ifBlank { "refresh folders failed" },
                            "refresh folders failed",
                        )
                        val waitSec = if (telegram.kind == TelegramError.Kind.Flood) {
                            (telegram.retryAfterSeconds ?: telegram.argument ?: 3).coerceIn(3, 60)
                        } else {
                            core.fail(e, "refresh folders failed", degradeHome = false)
                            15
                        }
                        lastMetadataRefreshMs.set(
                            core.clock.elapsedMs() + (waitSec - 2).toLong() * 1_000L,
                        )
                    } finally {
                        foldersInFlight.set(false)
                    }
                }
            }
            if (chatsRefreshPending && core.isCurrentHandle(activeHandle) &&
                cooldownDue(lastChatsRefreshMs, 2_000L, now) &&
                chatsInFlight.compareAndSet(false, true)
            ) {
                core.scope.launch {
                    try {
                        val chats = core.onNativeIfFree { handle ->
                            core.native.getChats(handle).toChatModels()
                        }
                        if (chats != null && core.isCurrentHandle(refreshHandle)) {
                            lastChatsRefreshMs.set(core.clock.elapsedMs())
                            chatsRefreshPending = false
                            core.updatesEvents.emit(MtprotoUpdate.ChatsChanged(chats))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        core.fail(e, "refresh chats failed", degradeHome = false)
                        lastChatsRefreshMs.set(core.clock.elapsedMs() + 13_000L)
                    } finally {
                        chatsInFlight.set(false)
                    }
                }
            }
            if (premiumRefreshPending && core.isCurrentHandle(activeHandle) &&
                cooldownDue(lastPremiumRefreshMs, 60_000L, now) &&
                premiumInFlight.compareAndSet(false, true)
            ) {
                core.scope.launch {
                    val profile = try {
                        core.onNativeIfFree { handle ->
                            core.native.getProfile(handle, 0L).toProfileModel()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastPremiumRefreshMs.set(core.clock.elapsedMs())
                        core.fail(e, "refresh account failed", degradeHome = false)
                        null
                    } finally {
                        premiumInFlight.set(false)
                    }
                    if (profile != null && core.isCurrentHandle(refreshHandle)) {
                        lastPremiumRefreshMs.set(core.clock.elapsedMs())
                        premiumRefreshPending = false
                        core.updatesEvents.emit(MtprotoUpdate.AccountPremium(profile.isPremium))
                    }
                }
            }
            if (core.isCurrentHandle(activeHandle)) {
                delay(drainDelayMs)
            }
        }
    }

    override fun updates(): Flow<MtprotoUpdate> = merge(core.updatesEvents, core.localUpdates)
    override fun sessionLost(): Flow<Unit> = core.sessionLostFlow.asSharedFlow()
}
