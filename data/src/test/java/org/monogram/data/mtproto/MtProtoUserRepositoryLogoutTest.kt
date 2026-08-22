package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.UserProfileSnapshotModel

class MtProtoUserRepositoryLogoutTest {
    @Test
    fun `logout tombstones resets sessions revokes server then cleans local state`() {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val authorizationStore = RecordingAuthorizationStore(events)
        try {
            val repository = MtProtoUserRepository(
                mtProtoProfiles = NoOpMtProtoUserProfileReader,
                scope = scope,
                mtProtoAuthorizationStore = authorizationStore,
                mtProtoAccountStateResetter = MtProtoAccountStateResetter { slot, _ ->
                    events += "cleanup:$slot"
                    // Mirror the real cleaner: the marker clears only after successful deletion.
                    authorizationStore.clear(slot)
                },
                mtProtoAuthSessionResetter = MtProtoAuthSessionResetter { events += "auth-reset" },
                mtProtoLiveSessionResetter = MtProtoLiveSessionResetter { events += "live-reset" },
                mtProtoServerLogOut = MtProtoServerLogOut { events += "server-logout" },
            )

            repository.logOut()
            // The logout coroutine runs on Dispatchers.Unconfined and completes inline.
        } finally {
            scope.cancel()
        }

        assertEquals(
            listOf(
                "tombstone",
                "live-reset",
                "auth-reset",
                "server-logout",
                "cleanup:default",
                "clear",
            ),
            events,
        )
    }

    @Test
    fun `failed server logout still completes local cleanup`() = runBlocking {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val authorizationStore = RecordingAuthorizationStore(events)
        try {
            val repository = MtProtoUserRepository(
                mtProtoProfiles = NoOpMtProtoUserProfileReader,
                scope = scope,
                mtProtoAuthorizationStore = authorizationStore,
                mtProtoAccountStateResetter = MtProtoAccountStateResetter { slot, _ ->
                    events += "cleanup"
                    authorizationStore.clear(slot)
                },
                mtProtoServerLogOut = MtProtoServerLogOut {
                    events += "server-logout-attempt"
                    error("offline")
                },
            )

            repository.logOut()
        } finally {
            scope.cancel()
        }

        assertTrue("server-logout-attempt" in events)
        assertTrue("cleanup" in events)
        assertEquals("clear", events.last())
    }

    private object NoOpMtProtoUserProfileReader : MtProtoUserProfileReader {
        override suspend fun getCurrentUser(accountId: String) = null
        override suspend fun getUser(accountId: String, userId: Long) = null
        override suspend fun getContacts(accountId: String) = emptyList<UserProfileSnapshotModel>()
    }

    private class RecordingAuthorizationStore(
        private val events: MutableList<String>,
    ) : MtProtoAccountAuthorizationStore {
        override suspend fun isAuthorized(accountSlot: String) = false
        override suspend fun isLogoutPending(accountSlot: String) = false
        override suspend fun markAuthorized(accountSlot: String) { events += "authorized" }
        override suspend fun markLogoutPending(accountSlot: String) { events += "tombstone" }
        override suspend fun clear(accountSlot: String) { events += "clear" }
    }
}
