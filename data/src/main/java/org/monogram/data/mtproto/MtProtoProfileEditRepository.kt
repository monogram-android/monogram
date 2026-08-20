package org.monogram.data.mtproto

import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.Birthday_aa6c995ca2
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiStatusEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiStatus_c46bf14186
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBirthday
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateEmojiStatus
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ReorderUsernames
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ToggleUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdatePersonalChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateProfile
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.photos.UploadProfilePhoto

internal class MtProtoProfileEditRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val uploader: MtProtoFileUploader = MtProtoFileUploader {
        throw UnsupportedOperationException("MTProto file upload is not configured")
    },
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

    override suspend fun setEmojiStatus(customEmojiId: Long?) {
        val status = customEmojiId?.let { EmojiStatus_c46bf14186(it, null) } ?: EmojiStatusEmpty
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdateEmojiStatus(status))) { "MTProto emoji status update was rejected" }
        }
    }
    override suspend fun setProfilePhoto(path: String) {
        val uploaded = uploader.upload(path)
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(
                UploadProfilePhoto(
                    fallback = false,
                    bot = null,
                    file_ = uploaded,
                    video = null,
                    videoStartTs = null,
                    videoEmojiMarkup = null,
                )
            )
        }
    }
    override suspend fun setBirthdate(birthdate: BirthdateModel?) {
        require(birthdate == null || birthdate.day in 1..31) { "MTProto birthday day is invalid" }
        require(birthdate == null || birthdate.month in 1..12) { "MTProto birthday month is invalid" }
        val birthday = birthdate?.let { Birthday_aa6c995ca2(it.day, it.month, it.year) }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdateBirthday(birthday))) { "MTProto birthday update was rejected" }
        }
    }
    override suspend fun setPersonalChat(chatId: Long) {
        require(chatId <= -CHANNEL_OFFSET - 1L) { "MTProto personal chat must be a channel or supergroup" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val channelId = -(chatId + CHANNEL_OFFSET)
        val channel = requireNotNull(chats.get(scope, channelId)) {
            "Missing MTProto personal channel projection: $channelId"
        }
        val accessHash = requireNotNull(channel.accessHash) {
            "Missing MTProto personal channel access hash: $channelId"
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdatePersonalChannel(InputChannel_d22292516d(channelId, accessHash)))) {
                "MTProto personal channel update was rejected"
            }
        }
    }
    override suspend fun setBusinessBio(bio: String) = unsupported()
    override suspend fun setBusinessLocation(address: String, latitude: Double, longitude: Double) = unsupported()
    override suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) = unsupported()
    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) {
        require(username.isNotBlank()) { "MTProto username must not be blank" }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(ToggleUsername(username, isActive))) {
                "MTProto username activation update was rejected"
            }
        }
    }

    override suspend fun reorderActiveUsernames(usernames: List<String>) {
        require(usernames.all(String::isNotBlank)) { "MTProto usernames must not be blank" }
        require(usernames.distinct().size == usernames.size) { "MTProto usernames must be unique" }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(ReorderUsernames(usernames))) {
                "MTProto username reorder was rejected"
            }
        }
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto profile edit is not available")

    private companion object {
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
