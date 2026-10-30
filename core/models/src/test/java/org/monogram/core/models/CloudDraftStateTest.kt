package org.monogram.core.models

import org.junit.Assert.*
import org.junit.Test

class CloudDraftStateTest {
    private val initial = CloudDraft(PeerId(7), text = "server", date = 100)

    @Test
    fun remoteUpdatesDoNotOverwriteUnsentLocalEdits() {
        val local = CloudDraftState(initial).localEdit(initial.copy(text = "local"))
        val merged = local.mergeRemote(initial.copy(text = "other device", date = 200))
        assertEquals("local", merged.draft.text)
        assertTrue(merged.dirty)
        assertEquals(local.revision, merged.revision)
        assertEquals(200, merged.lastRemoteDate)
    }

    @Test
    fun staleAcknowledgmentDoesNotClearANewerEdit() {
        val first = CloudDraftState(initial).localEdit(initial.copy(text = "first"))
        val second = first.localEdit(initial.copy(text = "second"))
        assertEquals(second, second.acknowledge(first.revision))
        val saved = second.acknowledge(second.revision)
        assertFalse(saved.dirty)
        assertEquals("second", saved.draft.text)
    }

    @Test
    fun oldSnapshotsAreIgnoredAndAnUndatedClearIsApplied() {
        val state = CloudDraftState(initial)
        assertEquals(state, state.mergeRemote(initial.copy(text = "old", date = 99)))
        val cleared = state.mergeRemote(initial.copy(text = "", date = 0))
        assertEquals("", cleared.draft.text)
        assertEquals(100, cleared.lastRemoteDate)
        assertEquals(1L, cleared.revision)
    }

    @Test
    fun unsupportedRemoteContentBlocksTextAutosavesUntilExplicitDiscard() {
        val local = CloudDraftState(initial).localEdit(initial.copy(text = "local"))
        val rich = initial.copy(text = "rich remote", date = 200, hasUnsupportedContent = true)
        val conflict = local.mergeRemote(rich)
        assertTrue(conflict.draft.hasUnsupportedContent)
        assertEquals("local", conflict.draft.text)
        assertTrue(conflict.acknowledge(local.revision).dirty)
        assertTrue(conflict.localEdit(initial.copy(text = "more typing")).draft.hasUnsupportedContent)
        val discarded = conflict.localEdit(CloudDraft(initial.chatId), discardUnsupported = true)
        assertFalse(discarded.draft.hasUnsupportedContent)
        assertTrue(discarded.discardingUnsupported)
        assertFalse(discarded.mergeRemote(rich).draft.hasUnsupportedContent)
        assertFalse(discarded.acknowledge(discarded.revision).discardingUnsupported)
    }

    @Test
    fun localMarkdownModeSurvivesAcknowledgmentAndMatchingRemoteUpdates() {
        val local = CloudDraftState(initial).localEdit(initial, markdown = true)
        val saved = local.acknowledge(local.revision)
        assertTrue(saved.markdown)
        assertTrue(saved.mergeRemote(initial.copy(date = 200)).markdown)
        assertFalse(saved.mergeRemote(initial.copy(text = "different", date = 200)).markdown)
        assertFalse(local.localEdit(CloudDraft(initial.chatId), discardUnsupported = true).markdown)
    }

    @Test
    fun contextsCannotBeMixed() {
        val state = CloudDraftState(initial)
        assertThrows(IllegalArgumentException::class.java) {
            state.localEdit(initial.copy(threadId = 99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.mergeRemote(initial.copy(chatId = PeerId(8)))
        }
    }
}
