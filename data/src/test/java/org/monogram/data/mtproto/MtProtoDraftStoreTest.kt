package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.MtProtoDraftProjectionDao
import org.monogram.data.db.model.MtProtoDraftProjectionEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.DraftMessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.DraftMessage_3aaf32dfa6
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDraftMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShort

class MtProtoDraftStoreTest {
    @Test
    fun `stages and clears main dialog drafts`() = runTest {
        val dao = FakeDraftDao()
        val store = MtProtoRoomDraftStore(dao) { 1L }

        store.stageLive(scope(), update(DraftMessage_3aaf32dfa6(false, false, null, "draft", null, null, 1, null, null)))
        assertEquals("draft", store.get(scope(), 42L))

        store.stageLive(scope(), update(DraftMessageEmpty(null)))
        assertEquals("", store.get(scope(), 42L))
    }

    @Test
    fun `deletes all drafts during account cleanup`() = runTest {
        val dao = FakeDraftDao()
        val store = MtProtoRoomDraftStore(dao)
        store.upsert(scope(), 42L, "draft")

        store.deleteAccount("default", MtProtoEnvironment.PRODUCTION)

        assertNull(store.get(scope(), 42L))
    }

    private fun scope() = MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)

    private fun update(draft: org.monogram.mtproto.tl.generated.cloud.layer223.DraftMessage_888051f685) = UpdateShort(
        UpdateDraftMessage(PeerUser(42L), null, null, draft),
        1,
    )

    private class FakeDraftDao : MtProtoDraftProjectionDao {
        private val drafts = mutableMapOf<List<Any>, MtProtoDraftProjectionEntity>()

        override suspend fun get(accountSlot: String, environment: String, dcId: Int, peerType: String, peerId: Long) =
            drafts[listOf(accountSlot, environment, dcId, peerType, peerId)]?.text

        override suspend fun upsert(entity: MtProtoDraftProjectionEntity) {
            drafts[listOf(entity.accountSlot, entity.environment, entity.dcId, entity.peerType, entity.peerId)] = entity
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            drafts.entries.removeAll { (key, _) -> key[0] == accountSlot && key[1] == environment }
        }
    }
}
