package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.core.date.toDate
import org.monogram.domain.models.SessionModel
import org.monogram.domain.models.SessionType

fun TdApi.Session.toDomain(): SessionModel {
    return SessionModel(
        id = this.id,
        isCurrent = this.isCurrent,
        isPasswordPending = this.isPasswordPending,
        isUnconfirmed = this.isUnconfirmed,
        applicationName = this.applicationName,
        applicationVersion = this.applicationVersion,
        deviceModel = this.deviceModel,
        platform = this.platform,
        systemVersion = this.systemVersion,
        logInDate = this.logInDate,
        lastActiveDate = this.lastActiveDate.toDate(),
        ipAddress = this.ipAddress,
        location = this.location,
        isOfficial = this.isOfficialApplication,
        type = this.deviceType.toDomain()
    )
}

fun TdApi.SessionDeviceType.toDomain(): SessionType {
    return when (this) {
        is TdApi.SessionDeviceTypeAndroid -> SessionType.Android
        is TdApi.SessionDeviceTypeApple -> SessionType.Apple
        is TdApi.SessionDeviceTypeBrave -> SessionType.Brave
        is TdApi.SessionDeviceTypeChrome -> SessionType.Chrome
        is TdApi.SessionDeviceTypeEdge -> SessionType.Edge
        is TdApi.SessionDeviceTypeFirefox -> SessionType.Firefox
        is TdApi.SessionDeviceTypeIpad -> SessionType.Ipad
        is TdApi.SessionDeviceTypeIphone -> SessionType.Iphone
        is TdApi.SessionDeviceTypeLinux -> SessionType.Linux
        is TdApi.SessionDeviceTypeMac -> SessionType.Mac
        is TdApi.SessionDeviceTypeOpera -> SessionType.Opera
        is TdApi.SessionDeviceTypeSafari -> SessionType.Safari
        is TdApi.SessionDeviceTypeUbuntu -> SessionType.Ubuntu
        is TdApi.SessionDeviceTypeVivaldi -> SessionType.Vivaldi
        is TdApi.SessionDeviceTypeWindows -> SessionType.Windows
        is TdApi.SessionDeviceTypeXbox -> SessionType.Xbox
        else -> SessionType.Unknown
    }
}
