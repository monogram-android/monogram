package org.monogram.presentation.features.profile.admin

import org.monogram.domain.models.ChatPermissionsModel

internal fun ChatPermissionsModel.toggle(permission: ChatEditComponent.Permission): ChatPermissionsModel {
    return when (permission) {
        ChatEditComponent.Permission.SEND_MESSAGES -> copy(canSendBasicMessages = !canSendBasicMessages)
        ChatEditComponent.Permission.SEND_MEDIA -> copy(canSendPhotos = !canSendPhotos)
        ChatEditComponent.Permission.SEND_STICKERS -> copy(canSendOtherMessages = !canSendOtherMessages)
        ChatEditComponent.Permission.SEND_POLLS -> copy(canSendPolls = !canSendPolls)
        ChatEditComponent.Permission.EMBED_LINKS -> copy(canAddLinkPreviews = !canAddLinkPreviews)
        ChatEditComponent.Permission.REACT_TO_MESSAGES -> copy(canReactToMessages = !canReactToMessages)
        ChatEditComponent.Permission.ADD_MEMBERS -> copy(canInviteUsers = !canInviteUsers)
        ChatEditComponent.Permission.PIN_MESSAGES -> copy(canPinMessages = !canPinMessages)
        ChatEditComponent.Permission.CHANGE_INFO -> copy(canChangeInfo = !canChangeInfo)
        ChatEditComponent.Permission.MANAGE_TOPICS -> copy(canCreateTopics = !canCreateTopics)
    }
}
