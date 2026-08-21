package org.monogram.data.mtproto

import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerSettings_936a3e31f4
import org.monogram.mtproto.tl.generated.cloud.layer223.UserFull_c1c6b6f92b
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.AddContact
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.DeleteContacts
import org.monogram.mtproto.tl.generated.cloud.layer223.users.GetFullUser
import org.monogram.mtproto.tl.generated.cloud.layer223.users.GetUsers
import org.monogram.mtproto.tl.generated.cloud.layer223.users.UserFull_a7968baaa4
import org.monogram.mtproto.tl.generated.cloud.layer223.Contact_fd1b8c949c
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Contacts_9469c223cd
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.GetContacts
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Found_bc39b7fc74
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Search
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.domain.repository.UserProfileSnapshotRepository

internal interface MtProtoUserProfileReader {
    suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel?
    suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel?
    suspend fun getContacts(accountId: String): List<UserProfileSnapshotModel>
    suspend fun searchContacts(accountId: String, query: String): List<UserProfileSnapshotModel> {
        throw UnsupportedOperationException("MTProto contact search is not available")
    }
    suspend fun addContact(
        accountId: String,
        user: UserProfileSnapshotModel,
        sharePhoneNumber: Boolean = true,
    ) {
        throw UnsupportedOperationException("MTProto contact mutation is not available")
    }
    suspend fun removeContact(accountId: String, userId: Long) {
        throw UnsupportedOperationException("MTProto contact mutation is not available")
    }
    suspend fun getNeedPhoneNumberPrivacyException(accountId: String, userId: Long): Boolean {
        throw UnsupportedOperationException("MTProto contact privacy read is not available")
    }
}

internal class MtProtoUserProfileSnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val userStore: MtProtoUserProjectionStore,
    private val chatStore: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val sessionFactory: MtProtoSessionTransportFactory? = null,
) : UserProfileSnapshotRepository, MtProtoUserProfileReader {
    override suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel? {
        val scope = scope(accountId)
        if (sessionFactory != null) {
            sessionFactory.open(accountId).use { transport ->
                userStore.upsert(scope, transport.execute(GetUsers(listOf(InputUserSelf))))
            }
        }
        return userStore.getSelf(scope)?.toDomain()
    }

    override suspend fun getContacts(accountId: String): List<UserProfileSnapshotModel> {
        val scope = scope(accountId)
        if (sessionFactory != null) {
            sessionFactory.open(accountId).use { transport ->
                return refreshContacts(scope, transport)
            }
        }
        return userStore.getAll(scope).filter { it.isContact }.map { it.toDomain() }
    }

    override suspend fun searchContacts(accountId: String, query: String): List<UserProfileSnapshotModel> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return getContacts(accountId)
        val scope = scope(accountId)
        val transport = requireNotNull(sessionFactory) { "MTProto session factory is unavailable" }.open(accountId)
        val response = try {
            transport.execute(Search(normalized, CONTACT_SEARCH_LIMIT)) as? Found_bc39b7fc74
                ?: error("Unsupported MTProto contact search response")
        } finally {
            transport.close()
        }
        userStore.upsert(scope, response.users)
        chatStore.upsert(scope, response.chats)
        return (response.myResults + response.results)
            .filterIsInstance<PeerUser>()
            .map { it.userId }
            .distinct()
            .mapNotNull { userStore.get(scope, it)?.toDomain() }
    }

    override suspend fun addContact(
        accountId: String,
        user: UserProfileSnapshotModel,
        sharePhoneNumber: Boolean,
    ) {
        val scope = scope(accountId)
        val accessHash = requireNotNull(userStore.get(scope, user.userId)?.accessHash) {
            "Missing MTProto user access hash: ${user.userId}"
        }
        val transport = requireNotNull(sessionFactory) { "MTProto session factory is unavailable" }.open(accountId)
        try {
            transport.execute(
                AddContact(
                    addPhonePrivacyException = sharePhoneNumber,
                    id = InputUser_4020eae812(user.userId, accessHash),
                    firstName = user.firstName.orEmpty(),
                    lastName = user.lastName.orEmpty(),
                    phone = user.phoneNumber.orEmpty(),
                    note = null,
                )
            )
            refreshContacts(scope, transport)
        } finally {
            transport.close()
        }
    }

    override suspend fun getNeedPhoneNumberPrivacyException(accountId: String, userId: Long): Boolean {
        require(userId > 0L) { "MTProto user ID must be positive" }
        val scope = scope(accountId)
        val input = requireNotNull(inputUser(scope, userId)) { "Missing MTProto user projection: $userId" }
        val transport = requireNotNull(sessionFactory) { "MTProto session factory is unavailable" }.open(accountId)
        val result = try {
            transport.execute(GetFullUser(input)) as? UserFull_a7968baaa4
                ?: error("Unsupported MTProto full user response")
        } finally {
            transport.close()
        }
        userStore.upsert(scope, result.users)
        chatStore.upsert(scope, result.chats)
        val full = result.fullUser as? UserFull_c1c6b6f92b
            ?: error("Unsupported MTProto full user payload")
        return (full.settings as? PeerSettings_936a3e31f4)?.needContactsException
            ?: error("Unsupported MTProto peer settings payload")
    }

    override suspend fun removeContact(accountId: String, userId: Long) {
        val scope = scope(accountId)
        val accessHash = requireNotNull(userStore.get(scope, userId)?.accessHash) {
            "Missing MTProto user access hash: $userId"
        }
        val transport = requireNotNull(sessionFactory) { "MTProto session factory is unavailable" }.open(accountId)
        try {
            transport.execute(DeleteContacts(listOf(InputUser_4020eae812(userId, accessHash))))
            refreshContacts(scope, transport)
        } finally {
            transport.close()
        }
    }

    private suspend fun refreshContacts(
        scope: MtProtoAuthKeyScope,
        transport: MtProtoRpcTransport,
    ): List<UserProfileSnapshotModel> {
        val response = transport.execute(GetContacts(0L)) as? Contacts_9469c223cd ?: return emptyList()
        userStore.upsert(scope, response.users)
        val contactIds = response.contacts.filterIsInstance<Contact_fd1b8c949c>().mapTo(hashSetOf()) { it.userId }
        return userStore.getAll(scope).filter { it.userId in contactIds }.map { it.toDomain() }
    }

    override suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel? {
        val scope = scope(accountId)
        val cached = userStore.get(scope, userId)
        if (sessionFactory != null && cached?.accessHash != null) {
            sessionFactory.open(accountId).use { transport ->
                userStore.upsert(
                    scope,
                    transport.execute(
                        GetUsers(listOf(InputUser_4020eae812(userId, cached.accessHash))),
                    ),
                )
            }
        }
        return userStore.get(scope, userId)?.toDomain()
    }

    private suspend fun inputUser(
        scope: MtProtoAuthKeyScope,
        userId: Long,
    ): org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_0bd9c3151c? {
        val user = userStore.get(scope, userId) ?: return null
        return if (user.isSelf) InputUserSelf else user.accessHash?.let { InputUser_4020eae812(userId, it) }
    }

    private suspend fun scope(accountId: String): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountId)
        return MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }

    private fun MtProtoUserReadModel.toDomain() = UserProfileSnapshotModel(
        userId = userId,
        firstName = firstName,
        lastName = lastName,
        username = username,
        phoneNumber = phone,
        isCurrentUser = isSelf,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isDeleted = isDeleted,
        isBot = isBot,
        isVerified = isVerified,
        isRestricted = isRestricted,
        isScam = isScam,
        isFake = isFake,
        isPremium = isPremium,
        isPartial = isMin,
    )

    private companion object {
        const val CONTACT_SEARCH_LIMIT = 100
    }
}
