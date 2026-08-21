package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.GlobalPrivacySettings_bf108a109d
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetContactSignUpNotification
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetGlobalPrivacySettings
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetContactSignUpNotification
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetGlobalPrivacySettings
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoClientOptionsRepositoryTest {
    @Test
    fun `maps enabled contact notifications to inverse silent request`() = runBlocking {
        val transport = Transport(true, true, true)
        val repository = MtProtoClientOptionsRepositoryImpl(MtProtoSessionTransportFactory { transport })

        assertTrue(repository.getContactJoinedNotificationsEnabled())
        repository.setContactJoinedNotificationsEnabled(true)
        repository.setContactJoinedNotificationsEnabled(false)

        assertEquals(
            listOf(
                GetContactSignUpNotification,
                SetContactSignUpNotification(silent = false),
                SetContactSignUpNotification(silent = true),
            ),
            transport.requests,
        )
        assertTrue(transport.closed)
    }

    @Test
    fun `preserves unrelated global privacy settings when toggling archive and mute`() = runBlocking {
        val current = privacy(archiveAndMute = false)
        val accepted = privacy(archiveAndMute = true)
        val transport = Transport(current, current, accepted)
        val repository = MtProtoClientOptionsRepositoryImpl(MtProtoSessionTransportFactory { transport })

        assertEquals(false, repository.getArchiveAndMuteNewChatsFromUnknownUsersEnabled())
        repository.setArchiveAndMuteNewChatsFromUnknownUsersEnabled(true)

        assertEquals(GetGlobalPrivacySettings, transport.requests[0])
        assertEquals(GetGlobalPrivacySettings, transport.requests[1])
        val update = transport.requests[2] as SetGlobalPrivacySettings
        assertEquals(true, (update.settings as GlobalPrivacySettings_bf108a109d).archiveAndMuteNewNoncontactPeers)
        assertEquals(true, (update.settings as GlobalPrivacySettings_bf108a109d).keepArchivedUnmuted)
        assertTrue(transport.closed)
    }

    private fun privacy(archiveAndMute: Boolean) = GlobalPrivacySettings_bf108a109d(
        archiveAndMuteNewNoncontactPeers = archiveAndMute,
        keepArchivedUnmuted = true,
        keepArchivedFolders = false,
        hideReadMarks = false,
        newNoncontactPeersRequirePremium = false,
        displayGiftsButton = false,
        noncontactPeersPaidStars = null,
        disallowedGifts = null,
    )

    private class Transport(private vararg val results: Any) : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        private var index = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return results[index++] as R
        }

        override fun close() {
            closed = true
        }
    }
}
