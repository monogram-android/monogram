package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.mapper.toEntity
import org.monogram.data.mapper.user.mapBasicGroupFullInfoToChat
import org.monogram.data.mapper.user.mapSupergroupFullInfoToChat
import org.monogram.data.mapper.user.toApi
import org.monogram.data.mapper.user.toDomain
import org.monogram.data.mapper.user.toEntity
import org.monogram.data.mapper.user.toTdApiChat
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.domain.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap

class ChatInfoRepositoryImpl(
    private val remote: UserRemoteDataSource,
    private val chatLocal: ChatLocalDataSource,
    private val userRepository: UserRepository,
    private val scope: CoroutineScope
) : ChatInfoRepository {
    private val fullInfoRequests = ConcurrentHashMap<Long, Deferred<ChatFullInfoModel?>>()

    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel? {
        if (chatId == 0L) return null

        val localChat = chatLocal.getChat(chatId)?.toTdApiChat()
        val localFullInfo = chatLocal.getChatFullInfo(chatId)?.toDomain()
        if (localChat != null && localFullInfo != null) {
            refreshChatFullInfo(chatId, localChat, await = false)
            return localFullInfo
        }

        val chat = localChat
            ?: remote.getChat(chatId)?.also { chatLocal.insertChat(it.toEntity()) }
            ?: return userRepository.resolveUserChatFullInfo(chatId)

        val type = chat.type
        if (type is TdApi.ChatTypePrivate) {
            return userRepository.resolveUserChatFullInfo(type.userId) ?: localFullInfo
        }

        return refreshChatFullInfo(chatId, chat, await = true) ?: localFullInfo
    }

    private suspend fun refreshChatFullInfo(
        chatId: Long,
        chat: TdApi.Chat,
        await: Boolean
    ): ChatFullInfoModel? {
        val deferred = fullInfoRequests.getOrPut(chatId) {
            scope.async {
                try {
                    fetchAndPersistRemoteFullInfo(chatId, chat)
                } finally {
                    fullInfoRequests.remove(chatId)
                }
            }
        }

        return if (await) deferred.await() else null
    }

    private suspend fun fetchAndPersistRemoteFullInfo(
        chatId: Long,
        chat: TdApi.Chat
    ): ChatFullInfoModel? {
        return when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                val fullInfo = remote.getSupergroupFullInfo(type.supergroupId)
                val supergroup = remote.getSupergroup(type.supergroupId)
                fullInfo?.let {
                    chatLocal.insertChatFullInfo(it.toEntity(chatId))
                }
                fullInfo?.mapSupergroupFullInfoToChat(supergroup)
            }

            is TdApi.ChatTypeBasicGroup -> {
                val fullInfo = remote.getBasicGroupFullInfo(type.basicGroupId)
                fullInfo?.let {
                    chatLocal.insertChatFullInfo(it.toEntity(chatId))
                }
                fullInfo?.mapBasicGroupFullInfoToChat()
            }

            else -> null
        }
    }

    override suspend fun searchPublicChat(username: String): ChatModel? {
        val chat = remote.searchPublicChat(username) ?: return null
        chatLocal.insertChat(chat.toEntity())
        return chat.toDomain()
    }

    override suspend fun getSimilarChatIds(chatId: Long): List<Long> {
        return remote.getSimilarChatIds(chatId)
            .filter { it != 0L && it != chatId }
            .distinct()
            .toList()
    }

    override suspend fun getChatMembers(
        chatId: Long,
        offset: Int,
        limit: Int,
        filter: ChatMembersFilter
    ): List<GroupMemberModel> {
        val chat = remote.getChat(chatId) ?: return emptyList()
        val members: List<TdApi.ChatMember> = when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                val tdFilter = filter.toApi()
                remote.getSupergroupMembers(type.supergroupId, tdFilter, offset, limit)
                    ?.members?.toList() ?: emptyList()
            }

            is TdApi.ChatTypeBasicGroup -> {
                if (offset > 0) return emptyList()
                val fullInfo = remote.getBasicGroupMembers(type.basicGroupId) ?: return emptyList()
                fullInfo.members.filter { member ->
                    when (filter) {
                        is ChatMembersFilter.Administrators ->
                            member.status is TdApi.ChatMemberStatusAdministrator ||
                                    member.status is TdApi.ChatMemberStatusCreator

                        else -> true
                    }
                }
            }

            else -> emptyList()
        }

        return coroutineScope {
            members.map { member ->
                async {
                    val sender = member.memberId as? TdApi.MessageSenderUser ?: return@async null
                    val user = userRepository.getUser(sender.userId) ?: return@async null
                    member.toDomain(user)
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel? {
        val member = remote.getChatMember(chatId, userId) ?: return null
        val user = userRepository.getUser(userId) ?: return null
        return member.toDomain(user)
    }

    override suspend fun setChatMemberStatus(chatId: Long, userId: Long, status: ChatMemberStatus) {
        remote.setChatMemberStatus(chatId, userId, status.toApi())
    }
}