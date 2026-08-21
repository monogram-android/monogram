package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.GlobalPrivacySettings_bf108a109d
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetContactSignUpNotification
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetGlobalPrivacySettings
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetContactSignUpNotification
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetGlobalPrivacySettings

internal interface MtProtoClientOptionsRepository {
    suspend fun getContactJoinedNotificationsEnabled(): Boolean
    suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean)
    suspend fun getArchiveAndMuteNewChatsFromUnknownUsersEnabled(): Boolean
    suspend fun setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled: Boolean)
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

    override suspend fun getArchiveAndMuteNewChatsFromUnknownUsersEnabled(): Boolean =
        globalPrivacySettings().archiveAndMuteNewNoncontactPeers

    override suspend fun setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled: Boolean) {
        val current = globalPrivacySettings()
        val updated = current.copy(archiveAndMuteNewNoncontactPeers = enabled)
        val accepted = transportFactory.open(accountSlot).use { transport ->
            transport.execute(SetGlobalPrivacySettings(updated)) as? GlobalPrivacySettings_bf108a109d
        } ?: error("Unsupported MTProto global privacy settings response")
        check(accepted.archiveAndMuteNewNoncontactPeers == enabled) {
            "MTProto archive-and-mute update was rejected"
        }
    }

    private suspend fun globalPrivacySettings(): GlobalPrivacySettings_bf108a109d =
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetGlobalPrivacySettings) as? GlobalPrivacySettings_bf108a109d
        } ?: error("Unsupported MTProto global privacy settings response")

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
