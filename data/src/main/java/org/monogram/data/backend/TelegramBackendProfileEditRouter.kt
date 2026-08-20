package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.UserProfileEditRepository

internal class TelegramBackendProfileEditRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> UserProfileEditRepository,
    private val mtProtoFactory: () -> UserProfileEditRepository,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : UserProfileEditRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } }
    }

    override suspend fun setName(firstName: String, lastName: String) = selected().setName(firstName, lastName)
    override suspend fun setBio(bio: String) = selected().setBio(bio)
    override suspend fun setUsername(username: String) = selected().setUsername(username)
    override suspend fun setEmojiStatus(customEmojiId: Long?) = selected().setEmojiStatus(customEmojiId)
    override suspend fun setProfilePhoto(path: String) = selected().setProfilePhoto(path)
    override suspend fun setBirthdate(birthdate: BirthdateModel?) = selected().setBirthdate(birthdate)
    override suspend fun setPersonalChat(chatId: Long) = selected().setPersonalChat(chatId)
    override suspend fun setBusinessBio(bio: String) = selected().setBusinessBio(bio)
    override suspend fun setBusinessLocation(address: String, latitude: Double, longitude: Double) =
        selected().setBusinessLocation(address, latitude, longitude)
    override suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) =
        selected().setBusinessOpeningHours(openingHours)
    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) =
        selected().toggleUsernameIsActive(username, isActive)
    override suspend fun reorderActiveUsernames(usernames: List<String>) = selected().reorderActiveUsernames(usernames)

    private fun selected(): UserProfileEditRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
