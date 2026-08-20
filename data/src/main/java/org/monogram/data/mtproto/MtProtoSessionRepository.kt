package org.monogram.data.mtproto

import java.util.Date
import org.monogram.domain.models.SessionModel
import org.monogram.domain.models.SessionType
import org.monogram.domain.repository.SessionRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.Authorization_dbb1508a1d
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Authorizations_38b29faeb6
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetAuthorizations
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ResetAuthorization

internal class MtProtoSessionRepository(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : SessionRepository {
    override suspend fun getActiveSessions(): List<SessionModel> {
        val transport = transportFactory.open(accountSlot)
        return try {
            val result = transport.execute(GetAuthorizations) as Authorizations_38b29faeb6
            result.authorizations.map {
                (it as? Authorization_dbb1508a1d)?.toDomain()
                    ?: error("Unsupported MTProto authorization constructor")
            }
        } finally {
            transport.close()
        }
    }

    override suspend fun terminateSession(sessionId: Long): Boolean {
        val transport = transportFactory.open(accountSlot)
        return try {
            transport.execute(ResetAuthorization(sessionId))
        } finally {
            transport.close()
        }
    }

    override suspend fun confirmQrCode(link: String): Boolean = throw UnsupportedOperationException(
        "MTProto QR authorization confirmation is not available"
    )

    private fun Authorization_dbb1508a1d.toDomain() = SessionModel(
        id = hash,
        isCurrent = current,
        isPasswordPending = passwordPending,
        isUnconfirmed = unconfirmed,
        applicationName = appName,
        applicationVersion = appVersion,
        deviceModel = deviceModel,
        platform = platform,
        systemVersion = systemVersion,
        logInDate = dateCreated,
        lastActiveDate = Date(dateActive * 1000L),
        ipAddress = ip,
        location = listOf(country, region).filter(String::isNotBlank).joinToString(", "),
        isOfficial = officialApp,
        type = platform.toSessionType(deviceModel),
    )

    private fun String.toSessionType(device: String): SessionType {
        val value = "$this $device".lowercase()
        return when {
            "android" in value -> SessionType.Android
            "ios" in value -> SessionType.Iphone
            "ipad" in value -> SessionType.Ipad
            "iphone" in value -> SessionType.Iphone
            "windows" in value -> SessionType.Windows
            "mac" in value || "os x" in value -> SessionType.Mac
            "linux" in value -> SessionType.Linux
            "chrome" in value -> SessionType.Chrome
            "firefox" in value -> SessionType.Firefox
            "safari" in value -> SessionType.Safari
            else -> SessionType.Unknown
        }
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
