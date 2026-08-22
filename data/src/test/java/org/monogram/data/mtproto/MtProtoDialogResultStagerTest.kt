package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogsNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogsSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_d319adbade

class MtProtoDialogResultStagerTest {
    @Test
    fun `stages ordinary dialogs and accepts empty result`() = runBlocking {
        val store = RecordingStore()
        val stager = MtProtoDialogResultStager(store)
        val result = Dialogs_d319adbade(emptyList(), emptyList(), emptyList(), emptyList())

        assertTrue(stager.stage(scope(), result))
        assertTrue(store.calls == 1)
        assertTrue(store.dialogs.isEmpty())
    }

    @Test
    fun `stages dialogs slice`() = runBlocking {
        val store = RecordingStore()

        assertTrue(MtProtoDialogResultStager(store).stage(scope(), DialogsSlice(3, emptyList(), emptyList(), emptyList(), emptyList())))
        assertTrue(store.calls == 1)
    }

    @Test
    fun `does not fabricate state for not modified result`() = runBlocking {
        val store = RecordingStore()

        assertFalse(MtProtoDialogResultStager(store).stage(scope(), DialogsNotModified(1)))
        assertTrue(store.calls == 0)
    }

    private fun scope() = MtProtoAuthKeyScope("account", MtProtoEnvironment.PRODUCTION, 2)

    private class RecordingStore : MtProtoDialogStore {
        var calls = 0
        var dialogs = emptyList<Dialog_cf9860a8bd>()
        override suspend fun getAll(scope: MtProtoAuthKeyScope) = emptyList<MtProtoDialogReadModel>()
        override suspend fun getByFolder(scope: MtProtoAuthKeyScope, folderId: Int) = emptyList<MtProtoDialogReadModel>()
        override suspend fun upsert(scope: MtProtoAuthKeyScope, dialogs: List<Dialog_cf9860a8bd>) {
            calls++
            this.dialogs = dialogs
        }
    }
}
