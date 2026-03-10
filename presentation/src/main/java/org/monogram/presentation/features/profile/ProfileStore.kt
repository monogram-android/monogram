package org.monogram.presentation.features.profile

import com.arkivanov.mvikotlin.core.store.Store
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.ChatMemberStatus

interface ProfileStore : Store<ProfileStore.Intent, ProfileComponent.State, ProfileStore.Label> {

    sealed class Intent {
        object Back : Intent()
        data class TabSelected(val index: Int) : Intent()
        data class MessageClick(val message: MessageModel) : Intent()
        data class MessageLongClick(val message: MessageModel) : Intent()
        object AvatarClick : Intent()
        object DismissViewer : Intent()
        object LoadMoreMedia : Intent()
        data class OpenMiniApp(val url: String, val name: String, val chatId: Long) : Intent()
        object DismissMiniApp : Intent()
        object ToggleMute : Intent()
        object Edit : Intent()
        object ShowQRCode : Intent()
        object DismissQRCode : Intent()
        object SendMessage : Intent()
        object Leave : Intent()
        object JoinChat : Intent()
        data class Report(val reason: String) : Intent()
        object DismissReport : Intent()
        object ShowReport : Intent()
        object ShowLogs : Intent()
        data class MemberClick(val userId: Long) : Intent()
        data class MemberLongClick(val userId: Long) : Intent()
        data class UpdateChatTitle(val title: String) : Intent()
        data class UpdateChatDescription(val description: String) : Intent()
        data class UpdateChatUsername(val username: String) : Intent()
        data class UpdateChatPermissions(val permissions: ChatPermissionsModel) : Intent()
        data class UpdateChatSlowModeDelay(val delay: Int) : Intent()
        data class UpdateMemberStatus(val userId: Long, val status: ChatMemberStatus) : Intent()
        object ShowStatistics : Intent()
        object ShowRevenueStatistics : Intent()
        object DismissStatistics : Intent()
        data class LoadStatisticsGraph(val token: String) : Intent()
        data class DownloadMedia(val message: MessageModel) : Intent()
        object LinkedChatClick : Intent()
        object ShowPermissions : Intent()
        object DismissPermissions : Intent()
        data class TogglePermission(val permission: String) : Intent()
        object AcceptTOS : Intent()
        object DismissTOS : Intent()
        data class LocationClick(val lat: Double, val lon: Double, val address: String) : Intent()
        object DismissLocation : Intent()
        data class UpdateState(val state: ProfileComponent.State) : Intent()
    }

    sealed class Label {
        object Back : Label()
    }
}
