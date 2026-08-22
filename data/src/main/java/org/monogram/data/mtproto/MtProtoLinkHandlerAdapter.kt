package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoLinkHandler
import org.monogram.domain.repository.LinkAction
import org.monogram.domain.repository.LinkHandlerRepository

internal class MtProtoLinkHandlerAdapter(
    private val mtProtoFactory: () -> MtProtoLinkHandler,
) : LinkHandlerRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    override suspend fun handleLink(link: String): LinkAction = mtProto.handle(link)
    override suspend fun joinChat(inviteLink: String): Long? = mtProto.joinChat(inviteLink)
    override suspend fun joinChatAction(inviteLink: String): LinkAction = mtProto.joinChatAction(inviteLink)
}
