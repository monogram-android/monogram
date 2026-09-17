package org.monogram.feature.chats

import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.network.bridge.MtprotoClient

internal class ChatsStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    private val sessionStore: SessionMetadataStore?,
    private val notifications: NotificationLocalStore? = null,
) {
    fun create(): ChatsStore =
        object :
            ChatsStore,
            Store<ChatsStore.Intent, ChatsStore.State, Nothing> by storeFactory.create(
                name = "ChatsStore",
                initialState = ChatsStore.State(loading = true),
                bootstrapper = SimpleBootstrapper(Unit),
                executorFactory = {
                    ChatsExecutor(client, warmup, sessionStore, notifications)
                },
                reducer = ChatsReducer,
            ) {}
}

internal sealed interface Msg {
    data class Loading(val value: Boolean) : Msg
    data class Syncing(val value: Boolean) : Msg
    data class Chats(val value: List<Chat>, val fromCache: Boolean, val replace: Boolean) : Msg
    data class Append(val value: List<Chat>) : Msg
    data class Query(val value: String) : Msg
    data class LoadingMore(val value: Boolean) : Msg
    data class HasMore(val value: Boolean) : Msg
    data class Error(val value: TelegramError?) : Msg
    data class Typing(
        val chatId: PeerId,
        val typing: Boolean,
        val names: List<String> = emptyList(),
        val action: String? = null,
    ) : Msg
    data class Status(
        val userId: PeerId,
        val status: String?,
        val statusAt: Long?,
    ) : Msg
    data class EmojiStatus(
        val userId: PeerId,
        val documentId: Long?,
    ) : Msg
    data class Self(val value: Profile?) : Msg
    data class Incoming(val value: Message) : Msg
    data class Edited(val value: Message) : Msg
    data class LatestReplaced(val chatId: PeerId, val value: Message?) : Msg
    data class InboxRead(val chatId: PeerId, val maxId: Int, val unread: Int) : Msg
    data class UnreadMark(val chatId: PeerId, val value: Boolean) : Msg
    data class ReadStates(val rows: Map<Long, ChatReadState>) : Msg
    data class OutboxRead(val chatId: PeerId, val maxId: Int) : Msg
    data class UnreadMentions(val chatId: PeerId, val stillUnread: Int) : Msg
    data class UnreadReactions(val chatId: PeerId, val stillUnread: Int) : Msg
    data class UnreadMentionsDelta(val chatId: PeerId, val delta: Int) : Msg
    data class UnreadReactionsDelta(val chatId: PeerId, val delta: Int) : Msg
}
