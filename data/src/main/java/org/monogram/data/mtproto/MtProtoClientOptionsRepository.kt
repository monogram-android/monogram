package org.monogram.data.mtproto

import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
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
    suspend fun getSentScheduledMessageNotificationsEnabled(): Boolean
    suspend fun setSentScheduledMessageNotificationsEnabled(enabled: Boolean)
    suspend fun getAnimatedEmojiEnabled(): Boolean
    suspend fun setAnimatedEmojiEnabled(enabled: Boolean)
    suspend fun canArchiveAndMuteNewChatsFromUnknownUsers(): Boolean
}

internal class MtProtoClientOptionsRepositoryImpl(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val keyValueDao: KeyValueDao? = null,
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

    override suspend fun getSentScheduledMessageNotificationsEnabled(): Boolean =
        readLocalOption(KEY_SENT_SCHEDULED_NOTIFICATIONS, defaultEnabled = true)

    override suspend fun setSentScheduledMessageNotificationsEnabled(enabled: Boolean) {
        writeLocalOption(KEY_SENT_SCHEDULED_NOTIFICATIONS, enabled)
    }

    override suspend fun getAnimatedEmojiEnabled(): Boolean =
        readLocalOption(KEY_ANIMATED_EMOJI, defaultEnabled = true)

    override suspend fun setAnimatedEmojiEnabled(enabled: Boolean) {
        writeLocalOption(KEY_ANIMATED_EMOJI, enabled)
    }

    override suspend fun canArchiveAndMuteNewChatsFromUnknownUsers(): Boolean = true

    private suspend fun readLocalOption(key: String, defaultEnabled: Boolean): Boolean {
        val dao = keyValueDao ?: return defaultEnabled
        return dao.getValue(key)?.value?.toBooleanStrictOrNull() ?: defaultEnabled
    }

    private suspend fun writeLocalOption(key: String, enabled: Boolean) {
        val dao = keyValueDao ?: return
        dao.insertValue(KeyValueEntity(key, enabled.toString()))
    }

    private suspend fun globalPrivacySettings(): GlobalPrivacySettings_bf108a109d =
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetGlobalPrivacySettings) as? GlobalPrivacySettings_bf108a109d
        } ?: error("Unsupported MTProto global privacy settings response")

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val KEY_SENT_SCHEDULED_NOTIFICATIONS = "mtproto_client_option_v1_sent_scheduled_message_notifications"
        const val KEY_ANIMATED_EMOJI = "mtproto_client_option_v1_animated_emoji"
    }
}
