package org.monogram.data.mtproto

import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.Birthday_aa6c995ca2
import org.monogram.mtproto.tl.generated.cloud.layer223.BusinessWeeklyOpen_dc4067a144
import org.monogram.mtproto.tl.generated.cloud.layer223.BusinessWorkHours_bd00fc5ee4
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiStatusEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputBusinessIntro_7df76090c9
import org.monogram.mtproto.tl.generated.cloud.layer223.InputGeoPoint_ca056caf04
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannelEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.EmojiStatus_c46bf14186
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBirthday
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateEmojiStatus
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ReorderUsernames
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBusinessIntro
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBusinessLocation
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateBusinessWorkHours
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
        val inputChannel = if (chatId == 0L) {
            InputChannelEmpty
        } else {
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
            InputChannel_d22292516d(channelId, accessHash)
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdatePersonalChannel(inputChannel))) {
                "MTProto personal channel update was rejected"
            }
        }
    }
    override suspend fun setBusinessBio(bio: String) {
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdateBusinessIntro(InputBusinessIntro_7df76090c9("", bio, null)))) {
                "MTProto business bio update was rejected"
            }
        }
    }
    override suspend fun setBusinessLocation(address: String, latitude: Double, longitude: Double) {
        val location = if (address.isBlank()) {
            null
        } else {
            require(latitude in -90.0..90.0) { "MTProto business latitude is invalid" }
            require(longitude in -180.0..180.0) { "MTProto business longitude is invalid" }
            InputGeoPoint_ca056caf04(latitude, longitude, null)
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdateBusinessLocation(location, address.takeIf { it.isNotBlank() }))) {
                "MTProto business location update was rejected"
            }
        }
    }
    override suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) {
        openingHours?.let { hours ->
            require(hours.timeZoneId.isNotBlank()) { "MTProto business timezone must not be blank" }
            require(hours.intervals.all { interval ->
                interval.startMinute in 0 until WEEK_MINUTES &&
                    interval.endMinute in 1..WEEK_MINUTES &&
                    interval.startMinute < interval.endMinute
            }) { "MTProto business opening-hour interval is invalid" }
        }
        val workHours = openingHours?.let { hours ->
            BusinessWorkHours_bd00fc5ee4(
                openNow = false,
                timezoneId = hours.timeZoneId,
                weeklyOpen = hours.intervals.map { interval ->
                    BusinessWeeklyOpen_dc4067a144(interval.startMinute, interval.endMinute)
                },
            )
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdateBusinessWorkHours(workHours))) {
                "MTProto business opening-hours update was rejected"
            }
        }
    }
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
        const val WEEK_MINUTES = 7 * 24 * 60
    }
}
