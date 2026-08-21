package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoUserProfileReader
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.ContactEditRepository
import org.monogram.domain.repository.UserRepository

/** Routes contact editing without creating TDLib for selected MTProto accounts. */
internal class TelegramBackendContactEditRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ContactEditRepository,
    private val users: UserRepository,
    private val mtProtoProfiles: MtProtoUserProfileReader,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ContactEditRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getContact(userId: Long): UserModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getContact(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> users.getUser(userId)?.takeIf(UserModel::isContact)
    }

    override suspend fun getNeedPhoneNumberPrivacyException(userId: Long): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getNeedPhoneNumberPrivacyException(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.getNeedPhoneNumberPrivacyException(accountId, userId)
    }

    override suspend fun upsertContact(user: UserModel, sharePhoneNumber: Boolean): UserModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.upsertContact(user, sharePhoneNumber)
        TelegramBackendKind.KOTLIN_MTPROTO -> {
            mtProtoProfiles.addContact(accountId, user.toProfileSnapshot(), sharePhoneNumber)
            users.getUser(user.id)
        }
    }

    override suspend fun removeContact(userId: Long) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.removeContact(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.removeContact(accountId, userId)
    }

    override suspend fun setCloseFriend(userId: Long, isCloseFriend: Boolean): UserModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setCloseFriend(userId, isCloseFriend)
        TelegramBackendKind.KOTLIN_MTPROTO -> {
            mtProtoProfiles.setCloseFriend(accountId, userId, isCloseFriend)
            users.getUser(userId)
        }
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun UserModel.toProfileSnapshot() = UserProfileSnapshotModel(
        userId = id,
        firstName = firstName,
        lastName = lastName,
        username = username,
        phoneNumber = phoneNumber,
        isCurrentUser = false,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isDeleted = type == org.monogram.domain.models.UserTypeEnum.DELETED,
        isBot = type == org.monogram.domain.models.UserTypeEnum.BOT,
        isVerified = isVerified,
        isRestricted = false,
        isScam = isScam,
        isFake = isFake,
        isPremium = isPremium,
        isPartial = false,
    )

    private fun unsupported(operation: String = "close-friend editing"): Nothing = throw UnsupportedOperationException(
        "MTProto $operation is not available"
    )

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
