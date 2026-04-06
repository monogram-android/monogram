package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity
import org.monogram.domain.models.ChatPermissionsModel

internal data class ChatEntityPermissionValues(
    val canSendBasicMessages: Boolean,
    val canSendAudios: Boolean,
    val canSendDocuments: Boolean,
    val canSendPhotos: Boolean,
    val canSendVideos: Boolean,
    val canSendVideoNotes: Boolean,
    val canSendVoiceNotes: Boolean,
    val canSendPolls: Boolean,
    val canSendOtherMessages: Boolean,
    val canAddLinkPreviews: Boolean,
    val canEditTag: Boolean,
    val canChangeInfo: Boolean,
    val canInviteUsers: Boolean,
    val canPinMessages: Boolean,
    val canCreateTopics: Boolean
)

internal fun TdApi.ChatPermissions?.toDomainChatPermissions(): ChatPermissionsModel {
    val permissions = this ?: TdApi.ChatPermissions()
    return ChatPermissionsModel(
        canSendBasicMessages = permissions.canSendBasicMessages,
        canSendAudios = permissions.canSendAudios,
        canSendDocuments = permissions.canSendDocuments,
        canSendPhotos = permissions.canSendPhotos,
        canSendVideos = permissions.canSendVideos,
        canSendVideoNotes = permissions.canSendVideoNotes,
        canSendVoiceNotes = permissions.canSendVoiceNotes,
        canSendPolls = permissions.canSendPolls,
        canSendOtherMessages = permissions.canSendOtherMessages,
        canAddLinkPreviews = permissions.canAddLinkPreviews,
        canEditTag = permissions.canEditTag,
        canChangeInfo = permissions.canChangeInfo,
        canInviteUsers = permissions.canInviteUsers,
        canPinMessages = permissions.canPinMessages,
        canCreateTopics = permissions.canCreateTopics,
    )
}

internal fun ChatPermissionsModel.toTdApiChatPermissions(): TdApi.ChatPermissions {
    return TdApi.ChatPermissions(
        canSendBasicMessages,
        canSendAudios,
        canSendDocuments,
        canSendPhotos,
        canSendVideos,
        canSendVideoNotes,
        canSendVoiceNotes,
        canSendPolls,
        canSendOtherMessages,
        canAddLinkPreviews,
        canEditTag,
        canChangeInfo,
        canInviteUsers,
        canPinMessages,
        canCreateTopics
    )
}

internal fun ChatEntity.toDomainChatPermissionsModel(): ChatPermissionsModel {
    return ChatPermissionsModel(
        canSendBasicMessages = permissionCanSendBasicMessages,
        canSendAudios = permissionCanSendAudios,
        canSendDocuments = permissionCanSendDocuments,
        canSendPhotos = permissionCanSendPhotos,
        canSendVideos = permissionCanSendVideos,
        canSendVideoNotes = permissionCanSendVideoNotes,
        canSendVoiceNotes = permissionCanSendVoiceNotes,
        canSendPolls = permissionCanSendPolls,
        canSendOtherMessages = permissionCanSendOtherMessages,
        canAddLinkPreviews = permissionCanAddLinkPreviews,
        canEditTag = permissionCanEditTag,
        canChangeInfo = permissionCanChangeInfo,
        canInviteUsers = permissionCanInviteUsers,
        canPinMessages = permissionCanPinMessages,
        canCreateTopics = permissionCanCreateTopics
    )
}

internal fun ChatPermissionsModel.toEntityPermissionValues(): ChatEntityPermissionValues {
    return ChatEntityPermissionValues(
        canSendBasicMessages = canSendBasicMessages,
        canSendAudios = canSendAudios,
        canSendDocuments = canSendDocuments,
        canSendPhotos = canSendPhotos,
        canSendVideos = canSendVideos,
        canSendVideoNotes = canSendVideoNotes,
        canSendVoiceNotes = canSendVoiceNotes,
        canSendPolls = canSendPolls,
        canSendOtherMessages = canSendOtherMessages,
        canAddLinkPreviews = canAddLinkPreviews,
        canEditTag = canEditTag,
        canChangeInfo = canChangeInfo,
        canInviteUsers = canInviteUsers,
        canPinMessages = canPinMessages,
        canCreateTopics = canCreateTopics
    )
}

internal fun ChatEntity.withPermissions(permissions: ChatPermissionsModel): ChatEntity {
    val values = permissions.toEntityPermissionValues()
    return copy(
        permissionCanSendBasicMessages = values.canSendBasicMessages,
        permissionCanSendAudios = values.canSendAudios,
        permissionCanSendDocuments = values.canSendDocuments,
        permissionCanSendPhotos = values.canSendPhotos,
        permissionCanSendVideos = values.canSendVideos,
        permissionCanSendVideoNotes = values.canSendVideoNotes,
        permissionCanSendVoiceNotes = values.canSendVoiceNotes,
        permissionCanSendPolls = values.canSendPolls,
        permissionCanSendOtherMessages = values.canSendOtherMessages,
        permissionCanAddLinkPreviews = values.canAddLinkPreviews,
        permissionCanEditTag = values.canEditTag,
        permissionCanChangeInfo = values.canChangeInfo,
        permissionCanInviteUsers = values.canInviteUsers,
        permissionCanPinMessages = values.canPinMessages,
        permissionCanCreateTopics = values.canCreateTopics
    )
}