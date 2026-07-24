package org.monogram.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.DispatcherProvider
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramLinkRepositoryImplTest {

    @Test
    fun `buildUrl fetches and caches resolved t me url`() = runTest {
        val keyValueDao = InMemoryKeyValueDao()
        val gateway = FakeTelegramGateway(
            responses = ArrayDeque(
                listOf(TdApi.OptionValueString("telegram.dog"))
            ),
            authenticated = true
        )
        val repository = TelegramLinkRepositoryImpl(
            gateway = gateway,
            updates = FakeUpdateDispatcher(),
            keyValueDao = keyValueDao,
            scope = backgroundScope,
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        )

        runCurrent()
        advanceUntilIdle()

        val url = repository.buildUrl("monogram")

        assertEquals("https://telegram.dog/monogram", url)
        assertEquals("https://telegram.dog", repository.baseUrl.value)
        assertEquals("https://telegram.dog", keyValueDao.getValue(TELEGRAM_KEY_T_ME_URL)?.value)
        assertTrue(keyValueDao.getValue(TELEGRAM_KEY_T_ME_URL_LOADED)?.value.toTelegramLinkLoadedFlag())
        assertEquals(1, gateway.executeCalls)
    }

    @Test
    fun `buildUrl uses cached fallback after single startup fetch`() = runTest {
        val keyValueDao = InMemoryKeyValueDao()
        val gateway = FakeTelegramGateway(
            responses = ArrayDeque(
                listOf(
                    TdApi.OptionValueBoolean(false)
                )
            ),
            authenticated = true
        )
        val repository = TelegramLinkRepositoryImpl(
            gateway = gateway,
            updates = FakeUpdateDispatcher(),
            keyValueDao = keyValueDao,
            scope = backgroundScope,
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        )

        runCurrent()
        advanceUntilIdle()

        val firstUrl = repository.buildUrl("monogram")
        val loadedAfterFirst = keyValueDao.getValue(TELEGRAM_KEY_T_ME_URL_LOADED)?.value
        val secondUrl = repository.buildUrl("monogram")

        assertEquals("https://t.me/monogram", firstUrl)
        assertFalse(loadedAfterFirst.toTelegramLinkLoadedFlag())
        assertEquals("https://t.me/monogram", secondUrl)
        assertFalse(keyValueDao.getValue(TELEGRAM_KEY_T_ME_URL_LOADED)?.value.toTelegramLinkLoadedFlag())
        assertEquals("https://t.me", keyValueDao.getValue(TELEGRAM_KEY_T_ME_URL)?.value)
        assertEquals(1, gateway.executeCalls)
    }
}

private class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
}

private class InMemoryKeyValueDao : KeyValueDao {
    private val values = LinkedHashMap<String, KeyValueEntity>()
    private val flows = mutableMapOf<String, MutableStateFlow<KeyValueEntity?>>()

    override suspend fun getValue(key: String): KeyValueEntity? = values[key]

    override fun observeValue(key: String): Flow<KeyValueEntity?> {
        return flows.getOrPut(key) { MutableStateFlow(values[key]) }
    }

    override suspend fun insertValue(entity: KeyValueEntity) {
        values[entity.key] = entity
        flows.getOrPut(entity.key) { MutableStateFlow(null) }.value = entity
    }

    override suspend fun deleteValue(key: String) {
        values.remove(key)
        flows.getOrPut(key) { MutableStateFlow(null) }.value = null
    }
}

private class FakeTelegramGateway(
    private val responses: ArrayDeque<TdApi.Object>,
    authenticated: Boolean = true
) : TelegramGateway {
    override val updates = MutableSharedFlow<TdApi.Update>()
    private val authState = MutableStateFlow(authenticated)
    override val isAuthenticated: StateFlow<Boolean> = authState
    var executeCalls: Int = 0

    fun setAuthenticated(value: Boolean) {
        authState.value = value
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
        executeCalls++
        return responses.removeFirstOrNull() as T? ?: TdApi.OptionValueString("t.me") as T
    }
}

private class FakeUpdateDispatcher : UpdateDispatcher {
    override val all: Flow<TdApi.Update> = MutableSharedFlow()
    override val authorizationState: Flow<TdApi.UpdateAuthorizationState> = MutableSharedFlow()
    override val newMessage: Flow<TdApi.UpdateNewMessage> = MutableSharedFlow()
    override val activeNotifications: Flow<TdApi.UpdateActiveNotifications> = MutableSharedFlow()
    override val notificationGroup: Flow<TdApi.UpdateNotificationGroup> = MutableSharedFlow()
    override val notification: Flow<TdApi.UpdateNotification> = MutableSharedFlow()
    override val messageEdited: Flow<TdApi.UpdateMessageEdited> = MutableSharedFlow()
    override val messageContent: Flow<TdApi.UpdateMessageContent> = MutableSharedFlow()
    override val messageSendSucceeded: Flow<TdApi.UpdateMessageSendSucceeded> = MutableSharedFlow()
    override val messageSendFailed: Flow<TdApi.UpdateMessageSendFailed> = MutableSharedFlow()
    override val messageDeleted: Flow<TdApi.UpdateDeleteMessages> = MutableSharedFlow()
    override val messagePinned: Flow<TdApi.UpdateChatLastMessage> = MutableSharedFlow()
    override val messageInteractionInfo: Flow<TdApi.UpdateMessageInteractionInfo> =
        MutableSharedFlow()
    override val chatLastMessage: Flow<TdApi.UpdateChatLastMessage> = MutableSharedFlow()
    override val chatPosition: Flow<TdApi.UpdateChatPosition> = MutableSharedFlow()
    override val chatReadInbox: Flow<TdApi.UpdateChatReadInbox> = MutableSharedFlow()
    override val chatReadOutbox: Flow<TdApi.UpdateChatReadOutbox> = MutableSharedFlow()
    override val chatUnreadMentionCount: Flow<TdApi.UpdateChatUnreadMentionCount> =
        MutableSharedFlow()
    override val chatNotificationSettings: Flow<TdApi.UpdateChatNotificationSettings> =
        MutableSharedFlow()
    override val chatTitle: Flow<TdApi.UpdateChatTitle> = MutableSharedFlow()
    override val chatPhoto: Flow<TdApi.UpdateChatPhoto> = MutableSharedFlow()
    override val chatPermissions: Flow<TdApi.UpdateChatPermissions> = MutableSharedFlow()
    override val chatDraftMessage: Flow<TdApi.UpdateChatDraftMessage> = MutableSharedFlow()
    override val chatAction: Flow<TdApi.UpdateChatAction> = MutableSharedFlow()
    override val chatOnlineMemberCount: Flow<TdApi.UpdateChatOnlineMemberCount> =
        MutableSharedFlow()
    override val chatFolders: Flow<TdApi.UpdateChatFolders> = MutableSharedFlow()
    override val userStatus: Flow<TdApi.UpdateUserStatus> = MutableSharedFlow()
    override val user: Flow<TdApi.UpdateUser> = MutableSharedFlow()
    override val userPrivacySettingRules: Flow<TdApi.UpdateUserPrivacySettingRules> =
        MutableSharedFlow()
    override val file: Flow<TdApi.UpdateFile> = MutableSharedFlow()
    override val option: Flow<TdApi.UpdateOption> = MutableSharedFlow()
    override val connectionState: Flow<TdApi.UpdateConnectionState> = MutableSharedFlow()
    override val installedStickerSets: Flow<TdApi.UpdateInstalledStickerSets> = MutableSharedFlow()
    override val newChat: Flow<TdApi.UpdateNewChat> = MutableSharedFlow()
    override val attachmentMenuBots: Flow<TdApi.UpdateAttachmentMenuBots> = MutableSharedFlow()
    override val chatsListUpdates: Flow<TdApi.Update> = MutableSharedFlow()
}
