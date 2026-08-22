package org.monogram.data.mtproto

import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatBannedRights_2339df02a7

internal fun ChatPermissionsModel.toMtProtoBannedRights(untilDate: Int): ChatBannedRights_2339df02a7 {
    require(!canReactToMessages) { "MTProto cannot represent reaction permissions" }
    return ChatBannedRights_2339df02a7(
        viewMessages = false,
        sendMessages = !canSendBasicMessages,
        sendMedia = !(canSendAudios || canSendDocuments || canSendPhotos || canSendVideos || canSendVideoNotes || canSendVoiceNotes),
        sendStickers = false,
        sendGifs = false,
        sendGames = false,
        sendInline = false,
        embedLinks = !canAddLinkPreviews,
        sendPolls = !canSendPolls,
        changeInfo = !canChangeInfo,
        inviteUsers = !canInviteUsers,
        pinMessages = !canPinMessages,
        manageTopics = !canCreateTopics,
        sendPhotos = !canSendPhotos,
        sendVideos = !canSendVideos,
        sendRoundvideos = !canSendVideoNotes,
        sendAudios = !canSendAudios,
        sendVoices = !canSendVoiceNotes,
        sendDocs = !canSendDocuments,
        sendPlain = !canSendOtherMessages,
        editRank = !canEditTag,
        untilDate = untilDate,
    )
}
