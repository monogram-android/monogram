package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoAccountStateResetter
import org.monogram.data.mtproto.MtProtoAuthSessionResetter
import org.monogram.data.mtproto.MtProtoEnvironment
import org.monogram.data.mtproto.MtProtoLiveSessionResetter
import org.monogram.data.mtproto.MtProtoUserProfileReader
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.UserRepository

internal class TelegramBackendUserRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> UserRepository,
    private val mtProtoProfiles: MtProtoUserProfileReader,
    private val scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    private val mtProtoAccountStateResetter: MtProtoAccountStateResetter = MtProtoAccountStateResetter { _, _ -> },
    private val mtProtoAuthSessionResetter: MtProtoAuthSessionResetter = MtProtoAuthSessionResetter {},
    private val mtProtoLiveSessionResetter: MtProtoLiveSessionResetter = MtProtoLiveSessionResetter {},
    private val mtProtoUserUpdates: Flow<Long> = emptyFlow(),
    private val mtProtoUserFullInfo: suspend (Long) -> ChatFullInfoModel? = {
        throw UnsupportedOperationException("MTProto user full info is not configured")
    },
) : UserRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private var legacyUserCollection: Job? = null
    private var legacyUpdateCollection: Job? = null
    private val _currentUserFlow = MutableStateFlow<UserModel?>(null)
    override val currentUserFlow = _currentUserFlow
    private val _userUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    override val anyUserUpdateFlow: Flow<Long> = _userUpdates

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { backend ->
                selectedBackend.value = backend
                legacyUserCollection?.cancel()
                legacyUpdateCollection?.cancel()
                legacyUserCollection = when (backend) {
                    TelegramBackendKind.LEGACY -> scope.launch {
                        legacy.currentUserFlow.collect { _currentUserFlow.value = it }
                    }
                    TelegramBackendKind.KOTLIN_MTPROTO -> scope.launch {
                        _currentUserFlow.value = mtProtoProfiles.getCurrentUser(accountId)?.toUserModel()
                    }
                }
                if (backend == TelegramBackendKind.LEGACY) {
                    legacyUpdateCollection = scope.launch {
                        legacy.anyUserUpdateFlow.collect { _userUpdates.emit(it) }
                    }
                } else {
                    legacyUpdateCollection = scope.launch {
                        mtProtoUserUpdates.collect { _userUpdates.emit(it) }
                    }
                }
            }
        }
    }

    override suspend fun getMe(): UserModel = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getMe()
        TelegramBackendKind.KOTLIN_MTPROTO -> requireNotNull(mtProtoProfiles.getCurrentUser(accountId)) {
            "MTProto current user is unavailable"
        }.toUserModel().also { _currentUserFlow.value = it }
    }

    override suspend fun getUser(userId: Long): UserModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getUser(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.getUser(accountId, userId)?.toUserModel()
    }

    override suspend fun getUserFullInfo(userId: Long): UserModel? = getUser(userId)
    override suspend fun refreshUserFullInfo(userId: Long) { getUser(userId) }
    override suspend fun resolveUserChatFullInfo(userId: Long): ChatFullInfoModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.resolveUserChatFullInfo(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoUserFullInfo(userId)
    }
    override fun getUserFlow(userId: Long): Flow<UserModel?> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getUserFlow(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> flow { emit(getUser(userId)) }
    }
    override fun logOut() {
        when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.logOut()
            TelegramBackendKind.KOTLIN_MTPROTO -> {
                _currentUserFlow.value = null
                scope.launch {
                    runCatching {
                        mtProtoLiveSessionResetter.resetLiveSession()
                        mtProtoAuthSessionResetter.resetAuthSession()
                        mtProtoAccountStateResetter.deleteAccount(
                            accountSlot = accountId,
                            environment = MtProtoEnvironment.PRODUCTION,
                        )
                    }
                }
            }
        }
    }
    override suspend fun getContacts(): List<UserModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getContacts()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.getContacts(accountId).map { it.toUserModel() }
    }
    override suspend fun searchContacts(query: String): List<UserModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.searchContacts(query)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.searchContacts(accountId, query).map { it.toUserModel() }
    }
    override suspend fun addContact(user: UserModel) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.addContact(user)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.addContact(accountId, user.toProfileSnapshot())
    }
    override suspend fun removeContact(userId: Long) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.removeContact(userId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoProfiles.removeContact(accountId, userId)
    }
    override suspend fun setCachedSimCountryIso(iso: String?) {
        when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.setCachedSimCountryIso(iso)
            TelegramBackendKind.KOTLIN_MTPROTO -> Unit
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

    private fun UserProfileSnapshotModel.toUserModel() = UserModel(
        id = userId,
        firstName = firstName.orEmpty(),
        lastName = lastName,
        username = username,
        phoneNumber = phoneNumber,
        isPremium = isPremium,
        isVerified = isVerified,
        isScam = isScam,
        isFake = isFake,
        isContact = isContact,
        isMutualContact = isMutualContact,
        type = if (isDeleted) org.monogram.domain.models.UserTypeEnum.DELETED
        else if (isBot) org.monogram.domain.models.UserTypeEnum.BOT
        else org.monogram.domain.models.UserTypeEnum.REGULAR,
    )

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto user operation is not available")

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
