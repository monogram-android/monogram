package org.monogram.core.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dialog without its own mute setting inherits the peer type default from
 * `account.getNotifySettings`, which is what Telegram's chat list icon and the `exclude_muted`
 * folder filter use.
 */
class NotifyDefaultsTest {
    private val now = nowSeconds()

    private fun settings(until: Int) = NotifySettings(muteUntil = until)

    private val muted = settings(now + 3600)
    private val forever = settings(Int.MAX_VALUE)
    private val expired = settings(now - 60)
    private val open = settings(0)

    private fun chat(
        id: Long = 1,
        isChannel: Boolean = false,
        isGroup: Boolean = false,
        muted: Boolean = false,
        override: Boolean = false,
    ) = Chat(
        id = PeerId(id),
        title = "c$id",
        isChannel = isChannel,
        isGroup = isGroup,
        muted = muted,
        muteOverride = override,
    )

    @Test
    fun dialogWithoutOverrideInheritsTheTypeDefault() {
        val defaults = NotifyDefaults(users = open, chats = open, broadcasts = muted)
        assertTrue(defaults.mutes(chat(isChannel = true)))
        assertFalse(defaults.mutes(chat(isGroup = true)))
        assertFalse(defaults.mutes(chat()))
    }

    @Test
    fun supergroupUsesTheGroupDefaultNotBroadcasts() {
        val defaults = NotifyDefaults(users = open, chats = muted, broadcasts = open)
        assertTrue(defaults.mutes(chat(isChannel = true, isGroup = true)))
    }

    @Test
    fun ownMuteSettingWinsOverTheTypeDefault() {
        val defaults = NotifyDefaults(users = open, chats = open, broadcasts = muted)
        // Explicitly unmuted dialog stays unmuted even though channels are muted by default.
        assertFalse(defaults.mutes(chat(isChannel = true, muted = false, override = true)))
        // And an explicitly muted chat stays muted while the type default is open.
        val openDefaults = NotifyDefaults(users = open, chats = open, broadcasts = open)
        assertTrue(openDefaults.mutes(chat(isChannel = true, muted = true, override = true)))
    }

    @Test
    fun expiredAndForeverMutesFollowMuteUntil() {
        val defaults = NotifyDefaults(users = expired, chats = expired, broadcasts = expired)
        assertFalse(defaults.mutes(chat()))
        val foreverDefaults = NotifyDefaults(users = forever, chats = forever, broadcasts = forever)
        assertTrue(foreverDefaults.mutes(chat()))
    }
}
