package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoLinkHandler
import org.monogram.domain.repository.LinkAction
import org.monogram.domain.repository.LinkHandlerRepository

internal class TelegramBackendLinkHandlerRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> LinkHandlerRepository,
    scope: CoroutineScope,
    private val mtProtoFactory: () -> MtProtoLinkHandler = { throw UnsupportedOperationException("MTProto link handling is not configured") },
    private val accountId: String = "default",
) : LinkHandlerRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }
    override suspend fun handleLink(link: String): LinkAction = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.handleLink(link)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.handle(link)
    }
    override suspend fun joinChat(inviteLink: String): Long? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.joinChat(inviteLink)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.joinChat(inviteLink)
    }
    override suspend fun joinChatAction(inviteLink: String): LinkAction = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.joinChatAction(inviteLink)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.joinChatAction(inviteLink)
    }
    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
}
