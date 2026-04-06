package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ChatType

internal data class TdChatTypeIds(
    val privateUserId: Long = 0L,
    val basicGroupId: Long = 0L,
    val supergroupId: Long = 0L,
    val secretChatId: Int = 0
)

internal fun TdApi.ChatType.toDomainChatType(): ChatType {
    return when (this) {
        is TdApi.ChatTypePrivate -> ChatType.PRIVATE
        is TdApi.ChatTypeBasicGroup -> ChatType.BASIC_GROUP
        is TdApi.ChatTypeSupergroup -> ChatType.SUPERGROUP
        is TdApi.ChatTypeSecret -> ChatType.SECRET
        else -> ChatType.PRIVATE
    }
}

internal fun TdApi.ChatType.toEntityChatType(): String = toDomainChatType().name

internal fun TdApi.ChatType.isChannelType(): Boolean {
    return (this as? TdApi.ChatTypeSupergroup)?.isChannel ?: false
}

internal fun TdApi.ChatType.isGroupType(): Boolean {
    return this is TdApi.ChatTypeBasicGroup || (this is TdApi.ChatTypeSupergroup && !isChannel)
}

internal fun TdApi.ChatType.extractTypeIds(): TdChatTypeIds {
    return when (this) {
        is TdApi.ChatTypePrivate -> TdChatTypeIds(privateUserId = userId)
        is TdApi.ChatTypeBasicGroup -> TdChatTypeIds(basicGroupId = basicGroupId)
        is TdApi.ChatTypeSupergroup -> TdChatTypeIds(supergroupId = supergroupId)
        is TdApi.ChatTypeSecret -> TdChatTypeIds(secretChatId = secretChatId)
        else -> TdChatTypeIds()
    }
}