package org.monogram.domain.models

import java.util.Date

private const val OCULUS = "OculusQuest"
private const val CHROME = "Chrome"

data class SessionModel(
    val id: Long,
    val isCurrent: Boolean,
    val isPasswordPending: Boolean,
    val isUnconfirmed: Boolean,
    val applicationName: String,
    val applicationVersion: String,
    val deviceModel: String,
    val platform: String,
    val systemVersion: String,
    val logInDate: Int,
    val lastActiveDate: Date,
    val ipAddress: String,
    val location: String,
    val isOfficial: Boolean,
    val type: SessionType
) {
    val isOculus: Boolean = type == SessionType.Android && deviceModel.contains(OCULUS, ignoreCase = true)

    val isChrome : Boolean = type == SessionType.Chrome || deviceModel.contains(CHROME, ignoreCase = true)
}

enum class SessionType {
    Android, Apple, Brave, Chrome, Edge, Firefox,
    Ipad, Iphone, Linux, Mac, Opera, Safari,
    Ubuntu, Unknown, Vivaldi, Windows, Xbox
}