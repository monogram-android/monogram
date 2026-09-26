package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.NotifySettings

class PeerNotificationModeTest {
    @Test
    fun roundTripsEveryField() {
        val mode = PeerNotificationMode(muteUntil = 1_700_000_000, preview = false, sound = false, popup = false)
        val entry = PeerNotificationModeCodec.encode(-1001234567890L, mode)
        val decoded = PeerNotificationModeCodec.decode(entry)
        assertEquals(-1001234567890L, decoded?.first)
        assertEquals(mode, decoded?.second)
    }

    @Test
    fun defaultModeRoundTripsAndDetectsItself() {
        val decoded = PeerNotificationModeCodec.decode(
            PeerNotificationModeCodec.encode(7L, PeerNotificationMode.Default),
        )
        assertEquals(PeerNotificationMode.Default, decoded?.second)
        assertTrue(PeerNotificationMode.Default.isDefault)
        assertFalse(PeerNotificationMode(popup = false).isDefault)
    }

    @Test
    fun malformedAndShortEntriesAreRejectedOrDefaulted() {
        assertNull(PeerNotificationModeCodec.decode(null))
        assertNull(PeerNotificationModeCodec.decode(""))
        assertNull(PeerNotificationModeCodec.decode("not-a-peer"))
        assertNull(PeerNotificationModeCodec.decode("7"))
        // Missing trailing flags default to enabled so older entries keep notifications.
        val legacy = PeerNotificationModeCodec.decode("7|0")
        assertEquals(7L, legacy?.first)
        assertEquals(PeerNotificationMode.Default, legacy?.second)
    }

    @Test
    fun upsertAddsReplacesAndClearsStoredEntries() {
        var entries = PeerNotificationModeCodec.upsert(emptySet(), 7L, PeerNotificationMode(sound = false))
        assertEquals(1, entries.size)
        // Replacing the same peer keeps a single entry.
        entries = PeerNotificationModeCodec.upsert(entries, 7L, PeerNotificationMode(popup = false))
        assertEquals(1, entries.size)
        assertEquals(PeerNotificationMode(popup = false), PeerNotificationModeCodec.decode(entries.first())?.second)
        // Other peers are preserved.
        entries = PeerNotificationModeCodec.upsert(entries, 9L, PeerNotificationMode(preview = false))
        assertEquals(2, entries.size)
        // null and default modes clear the entry, so defaults are never persisted.
        val withoutSeven = PeerNotificationModeCodec.upsert(entries, 7L, null)
        assertEquals(1, withoutSeven.size)
        entries = PeerNotificationModeCodec.upsert(withoutSeven, 9L, PeerNotificationMode.Default)
        assertEquals(0, entries.size)
    }

    @Test
    fun muteWinsUntilItsDeadline() {
        val mode = PeerNotificationMode(muteUntil = 100)
        assertTrue(mode.isMuted(nowSeconds = 50))
        assertFalse(mode.isMuted(nowSeconds = 100))
        assertFalse(mode.isMuted(nowSeconds = 101))
    }

    @Test
    fun chatModeOverridesCategoryDefaults() {
        val payload = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":1}}""")
        val quiet = NotificationPolicyState(
            peerModes = mapOf(7L to PeerNotificationMode(preview = false, sound = false, popup = false)),
        )
        val decision = decideNotification(payload, quiet, nowSeconds = 10, appInForeground = false)
        assertTrue(decision.show)
        assertFalse(decision.preview)
        assertFalse(decision.sound)
        assertFalse(decision.popup)
    }

    @Test
    fun chatMuteHidesUnlessMentioned() {
        val muted = NotificationPolicyState(peerModes = mapOf(7L to PeerNotificationMode(muteUntil = Int.MAX_VALUE)))
        val plain = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":1}}""")
        assertFalse(decideNotification(plain, muted, 10, false).show)
        val mention = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":1,"mention":1}}""")
        assertTrue(decideNotification(mention, muted, 10, false).show)
    }

    @Test
    fun chatModeSoundCannotReviveASilentPayloadOrServerSilence() {
        val payload = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":1,"silent":1}}""")
        val loud = NotificationPolicyState(peerModes = mapOf(7L to PeerNotificationMode(sound = true)))
        assertFalse(decideNotification(payload, loud, 10, false).sound)
        val serverSilent = NotificationPolicyState(
            exceptions = mapOf(7L to NotifySettings(silent = true)),
            peerModes = mapOf(7L to PeerNotificationMode(sound = true)),
        )
        assertFalse(decideNotification(payload.copy(silent = false), serverSilent, 10, false).sound)
    }

    @Test
    fun folderMuteOutranksChatModeAndMentions() {
        // A folder mute is applied before the peer mode is consulted, so a chat mode cannot revive it
        // and a mention does not bypass it either (documented behaviour of decideNotification).
        val state = NotificationPolicyState(
            folderMutedChatIds = setOf(7L),
            peerModes = mapOf(7L to PeerNotificationMode(preview = true, sound = true, popup = true)),
        )
        val plain = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":1}}""")
        assertFalse(decideNotification(plain, state, 10, false).show)
        val mention = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":1,"mention":1}}""")
        assertFalse(decideNotification(mention, state, 10, false).show)
        // Without the folder mute the same mode is honoured.
        assertTrue(decideNotification(mention, state.copy(folderMutedChatIds = emptySet()), 10, false).show)
    }

    @Test
    fun servicePayloadsHaveNoPopupFlag() {
        val decision = decideNotification(
            parsePushPayload("""{"loc_key":"MESSAGE_DELETED","custom":{"from_id":7,"messages":"1"}}"""),
            NotificationPolicyState(),
            nowSeconds = 10,
            appInForeground = false,
        )
        assertFalse(decision.show)
        assertFalse(decision.popup)
    }
}
