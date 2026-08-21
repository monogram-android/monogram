package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetContactSignUpNotification
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetContactSignUpNotification

internal interface MtProtoClientOptionsRepository {
    suspend fun getContactJoinedNotificationsEnabled(): Boolean
    suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean)
}

internal class MtProtoClientOptionsRepositoryImpl(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoClientOptionsRepository {
    override suspend fun getContactJoinedNotificationsEnabled(): Boolean =
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetContactSignUpNotification)
        }

    override suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean) {
        val accepted = transportFactory.open(accountSlot).use { transport ->
            transport.execute(SetContactSignUpNotification(silent = !enabled))
        }
        check(accepted) { "MTProto contact sign-up notification update was rejected" }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
