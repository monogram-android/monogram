package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.UserRepository

internal class MtProtoUserRepository(
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
    private val _currentUserFlow = MutableStateFlow<UserModel?>(null)
    override val currentUserFlow = _currentUserFlow
    private val _userUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    override val anyUserUpdateFlow: Flow<Long> = _userUpdates

    init {
        scope.launch {
            _currentUserFlow.value = mtProtoProfiles.getCurrentUser(accountId)?.toUserModel()
        }
        scope.launch {
            mtProtoUserUpdates.collect { _userUpdates.emit(it) }
        }
    }

    override suspend fun getMe(): UserModel = requireNotNull(mtProtoProfiles.getCurrentUser(accountId)) {
        "MTProto current user is unavailable"
    }.toUserModel().also { _currentUserFlow.value = it }

    override suspend fun getUser(userId: Long): UserModel? = mtProtoProfiles.getUser(accountId, userId)?.toUserModel()

    override suspend fun getUserFullInfo(userId: Long): UserModel? = getUser(userId)
    override suspend fun refreshUserFullInfo(userId: Long) { getUser(userId) }
    override suspend fun resolveUserChatFullInfo(userId: Long): ChatFullInfoModel? = mtProtoUserFullInfo(userId)
    override fun getUserFlow(userId: Long): Flow<UserModel?> = flow { emit(getUser(userId)) }
    override fun logOut() {
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
    override suspend fun getContacts(): List<UserModel> = mtProtoProfiles.getContacts(accountId).map { it.toUserModel() }
    override suspend fun searchContacts(query: String): List<UserModel> = mtProtoProfiles.searchContacts(accountId, query).map { it.toUserModel() }
    override suspend fun addContact(user: UserModel) = mtProtoProfiles.addContact(accountId, user.toProfileSnapshot())
    override suspend fun removeContact(userId: Long) = mtProtoProfiles.removeContact(accountId, userId)
    override suspend fun setCachedSimCountryIso(iso: String?) = Unit

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

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
