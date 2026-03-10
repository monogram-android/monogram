package org.monogram.presentation.features.profile

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import org.monogram.presentation.features.profile.ProfileStore.Intent
import org.monogram.presentation.features.profile.ProfileStore.Label

class ProfileStoreFactory(
    private val storeFactory: StoreFactory,
    private val component: DefaultProfileComponent
) {

    fun create(): ProfileStore =
        object : ProfileStore, Store<Intent, ProfileComponent.State, Label> by storeFactory.create(
            name = "ProfileStore",
            initialState = component.state.value,
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl
        ) {}

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Nothing, ProfileComponent.State, Message, Label>() {
        override fun executeIntent(intent: Intent) {
            when (intent) {
                Intent.Back -> component.onBack()
                is Intent.TabSelected -> component.onTabSelected(intent.index)
                is Intent.MessageClick -> component.onMessageClick(intent.message)
                is Intent.MessageLongClick -> component.onMessageLongClick(intent.message)
                Intent.AvatarClick -> component.onAvatarClick()
                Intent.DismissViewer -> component.onDismissViewer()
                Intent.LoadMoreMedia -> component.onLoadMoreMedia()
                is Intent.OpenMiniApp -> component.onOpenMiniApp(intent.url, intent.name, intent.chatId)
                Intent.DismissMiniApp -> component.onDismissMiniApp()
                Intent.ToggleMute -> component.onToggleMute()
                Intent.Edit -> component.onEdit()
                Intent.ShowQRCode -> component.onShowQRCode()
                Intent.DismissQRCode -> component.onDismissQRCode()
                Intent.SendMessage -> component.onSendMessage()
                Intent.Leave -> component.onLeave()
                Intent.JoinChat -> component.onJoinChat()
                is Intent.Report -> component.onReport(intent.reason)
                Intent.DismissReport -> component.onDismissReport()
                Intent.ShowReport -> component.onShowReport()
                Intent.ShowLogs -> component.onShowLogs()
                is Intent.MemberClick -> component.onMemberClick(intent.userId)
                is Intent.MemberLongClick -> component.onMemberLongClick(intent.userId)
                is Intent.UpdateChatTitle -> component.onUpdateChatTitle(intent.title)
                is Intent.UpdateChatDescription -> component.onUpdateChatDescription(intent.description)
                is Intent.UpdateChatUsername -> component.onUpdateChatUsername(intent.username)
                is Intent.UpdateChatPermissions -> component.onUpdateChatPermissions(intent.permissions)
                is Intent.UpdateChatSlowModeDelay -> component.onUpdateChatSlowModeDelay(intent.delay)
                is Intent.UpdateMemberStatus -> component.onUpdateMemberStatus(intent.userId, intent.status)
                Intent.ShowStatistics -> component.onShowStatistics()
                Intent.ShowRevenueStatistics -> component.onShowRevenueStatistics()
                Intent.DismissStatistics -> component.onDismissStatistics()
                is Intent.LoadStatisticsGraph -> component.onLoadStatisticsGraph(intent.token)
                is Intent.DownloadMedia -> component.onDownloadMedia(intent.message)
                Intent.LinkedChatClick -> component.onLinkedChatClick()
                Intent.ShowPermissions -> component.onShowPermissions()
                Intent.DismissPermissions -> component.onDismissPermissions()
                is Intent.TogglePermission -> component.onTogglePermission(intent.permission)
                Intent.AcceptTOS -> component.onAcceptTOS()
                Intent.DismissTOS -> component.onDismissTOS()
                is Intent.LocationClick -> component.onLocationClick(intent.lat, intent.lon, intent.address)
                Intent.DismissLocation -> component.onDismissLocation()
                is Intent.UpdateState -> dispatch(Message.UpdateState(intent.state))
            }
        }
    }

    private object ReducerImpl : Reducer<ProfileComponent.State, Message> {
        override fun ProfileComponent.State.reduce(msg: Message): ProfileComponent.State =
            when (msg) {
                is Message.UpdateState -> msg.state
            }
    }

    sealed class Message {
        data class UpdateState(val state: ProfileComponent.State) : Message()
    }
}
