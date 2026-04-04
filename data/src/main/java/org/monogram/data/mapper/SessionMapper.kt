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
        type = this.type.toDomain()
    )
}

fun TdApi.SessionType.toDomain(): SessionType {
    return when (this) {
        is TdApi.SessionTypeAndroid -> SessionType.Android
        is TdApi.SessionTypeApple -> SessionType.Apple
        is TdApi.SessionTypeBrave -> SessionType.Brave
        is TdApi.SessionTypeChrome -> SessionType.Chrome
        is TdApi.SessionTypeEdge -> SessionType.Edge
        is TdApi.SessionTypeFirefox -> SessionType.Firefox
        is TdApi.SessionTypeIpad -> SessionType.Ipad
        is TdApi.SessionTypeIphone -> SessionType.Iphone
        is TdApi.SessionTypeLinux -> SessionType.Linux
        is TdApi.SessionTypeMac -> SessionType.Mac
        is TdApi.SessionTypeOpera -> SessionType.Opera
        is TdApi.SessionTypeSafari -> SessionType.Safari
        is TdApi.SessionTypeUbuntu -> SessionType.Ubuntu
        is TdApi.SessionTypeVivaldi -> SessionType.Vivaldi
        is TdApi.SessionTypeWindows -> SessionType.Windows
        is TdApi.SessionTypeXbox -> SessionType.Xbox
        else -> SessionType.Unknown
    }
}
