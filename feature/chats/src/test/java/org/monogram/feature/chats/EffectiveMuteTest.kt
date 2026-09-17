package org.monogram.feature.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.NotifyDefaults
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId

class EffectiveMuteTest {
    private val mutedForever = NotifySettings(muteUntil = Int.MAX_VALUE)

    @Test
    fun cachedEffectiveFlagIsKeptUntilTheDefaultsAreKnown() {
        // Room stores the effective flag, so an offline start must not clear a muted chat just
        // because `account.getNotifySettings` has not answered yet.
        val cached = listOf(Chat(PeerId(7), "Ada", muted = true, muteOverride = false))

        val rows = withEffectiveMutes(cached, NotifyDefaults(), defaultsLoaded = false)

        assertTrue(rows.single().muted)
    }

    @Test
    fun inheritedMuteIsRecomputedOnceTheDefaultsArrive() {
        val defaults = NotifyDefaults(users = mutedForever)
        val chat = Chat(PeerId(7), "Ada", muted = false, muteOverride = false)

        val rows = withEffectiveMutes(listOf(chat), defaults, defaultsLoaded = true)

        assertTrue(rows.single().muted)
    }

    @Test
    fun ownSettingWinsOverThePeerTypeDefault() {
        val defaults = NotifyDefaults(users = mutedForever)
        val chat = Chat(PeerId(7), "Ada", muted = false, muteOverride = true)

        val rows = withEffectiveMutes(listOf(chat), defaults, defaultsLoaded = true)

        assertFalse(rows.single().muted)
    }

    @Test
    fun ownMuteMarksTheOverrideSoLaterDefaultChangesDoNotWin() {
        val now = 1_000

        val muted = Chat(PeerId(7), "Ada").withOwnMute(now + 60, now)
        assertTrue(muted.muted)
        assertTrue(muted.muteOverride)

        val unmuted = muted.withOwnMute(0, now)
        assertFalse(unmuted.muted)
        assertTrue(unmuted.muteOverride)
    }

    @Test
    fun aPastMuteUntilIsNotMutedAndForeverIs() {
        val now = 1_000
        val chat = Chat(PeerId(7), "Ada")

        assertFalse(chat.withOwnMute(now - 1, now).muted)
        assertTrue(chat.withOwnMute(Int.MAX_VALUE, now).muted)
    }
}
