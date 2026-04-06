package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.UserProfileEditRepository

class UserProfileEditRepositoryImpl(
    private val remote: UserRemoteDataSource
) : UserProfileEditRepository {
    override suspend fun setName(firstName: String, lastName: String) =
        remote.setName(firstName, lastName)

    override suspend fun setBio(bio: String) =
        remote.setBio(bio)

    override suspend fun setUsername(username: String) =
        remote.setUsername(username)

    override suspend fun setEmojiStatus(customEmojiId: Long?) =
        remote.setEmojiStatus(customEmojiId)

    override suspend fun setProfilePhoto(path: String) =
        remote.setProfilePhoto(path)

    override suspend fun setBirthdate(birthdate: BirthdateModel?) =
        remote.setBirthdate(birthdate?.let { TdApi.Birthdate(it.day, it.month, it.year ?: 0) })

    override suspend fun setPersonalChat(chatId: Long) =
        remote.setPersonalChat(chatId)

    override suspend fun setBusinessBio(bio: String) =
        remote.setBusinessBio(bio)

    override suspend fun setBusinessLocation(address: String, latitude: Double, longitude: Double) =
        remote.setBusinessLocation(
            if (address.isNotEmpty()) TdApi.BusinessLocation(
                TdApi.Location(latitude, longitude, 0.0),
                address
            ) else null
        )

    override suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) =
        remote.setBusinessOpeningHours(
            openingHours?.let {
                TdApi.BusinessOpeningHours(
                    it.timeZoneId,
                    it.intervals.map { interval ->
                        TdApi.BusinessOpeningHoursInterval(interval.startMinute, interval.endMinute)
                    }.toTypedArray()
                )
            }
        )

    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) =
        remote.toggleUsernameIsActive(username, isActive)

    override suspend fun reorderActiveUsernames(usernames: List<String>) =
        remote.reorderActiveUsernames(usernames.toTypedArray())
}