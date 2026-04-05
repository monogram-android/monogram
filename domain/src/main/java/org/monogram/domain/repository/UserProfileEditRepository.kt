package org.monogram.domain.repository

import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel

interface UserProfileEditRepository {
    suspend fun setName(firstName: String, lastName: String)
    suspend fun setBio(bio: String)
    suspend fun setUsername(username: String)
    suspend fun setEmojiStatus(customEmojiId: Long?)
    suspend fun setProfilePhoto(path: String)
    suspend fun setBirthdate(birthdate: BirthdateModel?)
    suspend fun setPersonalChat(chatId: Long)
    suspend fun setBusinessBio(bio: String)
    suspend fun setBusinessLocation(address: String, latitude: Double = 0.0, longitude: Double = 0.0)
    suspend fun setBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?)
    suspend fun toggleUsernameIsActive(username: String, isActive: Boolean)
    suspend fun reorderActiveUsernames(usernames: List<String>)
}