package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun TdApi.ChatPermissions.toDomainCanReactToMessages(): Boolean = canReactToMessages

internal fun buildTdChatPermissions(
    canSendBasicMessages: Boolean,
    canSendAudios: Boolean,
    canSendDocuments: Boolean,
    canSendPhotos: Boolean,
    canSendVideos: Boolean,
    canSendVideoNotes: Boolean,
    canSendVoiceNotes: Boolean,
    canSendPolls: Boolean,
    canSendOtherMessages: Boolean,
    canAddLinkPreviews: Boolean,
    canReactToMessages: Boolean,
    canEditTag: Boolean,
    canChangeInfo: Boolean,
    canInviteUsers: Boolean,
    canPinMessages: Boolean,
    canCreateTopics: Boolean
): TdApi.ChatPermissions = TdApi.ChatPermissions(
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
    canReactToMessages,
    canEditTag,
    canChangeInfo,
    canInviteUsers,
    canPinMessages,
    canCreateTopics
)
