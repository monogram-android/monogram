package org.monogram.data.mtproto

import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateProfile
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateUsername

internal class MtProtoProfileEditRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val accountSlot: String = "default",
) : UserProfileEditRepository {
    override suspend fun setName(firstName: String, lastName: String) = updateProfile(
        firstName = firstName,
        lastName = lastName,
        about = null,
    )

    override suspend fun setBio(bio: String) = updateProfile(
        firstName = null,
        lastName = null,
        about = bio,
    )

    private suspend fun updateProfile(firstName: String?, lastName: String?, about: String?) =
        updateUser { UpdateProfile(firstName, lastName, about) }

    override suspend fun setUsername(username: String) = updateUser { UpdateUsername(username) }

    private suspend fun updateUser(
        request: () -> org.monogram.mtproto.tl.runtime.TlMethod<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
    ) {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            users.upsert(scope, listOf(transport.execute(request())))
        } finally {
            transport.close()
        }
    }

    override suspend fun setEmojiStatus(customEmojiId: Long?) = unsupported()
    override suspend fun setProfilePhoto(path: String) = unsupported()
    override suspend fun setBirthdate(birthdate: BirthdateModel?) = unsupported()
    override suspend fun setPersonalChat(chatId: Long) = unsupported()
    override suspend fun setBusinessBio(bio: String) = unsupported()
    override suspend fun setBusinessLocation(address: String, latitude: Double, longitude: Double) = unsupported()
    override suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) = unsupported()
    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) = unsupported()
    override suspend fun reorderActiveUsernames(usernames: List<String>) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto profile edit is not available")
}
