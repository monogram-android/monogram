package org.monogram.core.models

/**
 * Shared-media tabs shown on a peer profile.
 *
 * [wire] matches the `messages.search` filter names mapped in
 * `native/mtproto-rs/src/messages_rpc.rs` and Telegram's profile tab order.
 */
enum class ProfileTab(val wire: String) {
    MEDIA("photo_video"),
    FILES("document"),
    LINKS("url"),
    GIFS("gif"),
    VOICE("voice"),
    MUSIC("music"),
    ;

    companion object {
        fun fromWire(value: String): ProfileTab? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Result counts per shared-media tab.
 *
 * A tab missing from [counts] was never answered by the server and stays visible; a known
 * `0` hides it (`hasMedia[i] == -1` / uninitialized).
 */
data class ProfileTabCounts(
    val counts: Map<ProfileTab, Int> = emptyMap(),
    val inexact: Set<ProfileTab> = emptySet(),
) {
    fun known(tab: ProfileTab): Int? = counts[tab]

    fun isVisible(tab: ProfileTab): Boolean = counts[tab]?.let { it > 0 } ?: true

    fun visibleTabs(): List<ProfileTab> = ProfileTab.entries.filter { isVisible(it) }

    fun merge(incoming: ProfileTabCounts): ProfileTabCounts = ProfileTabCounts(
        counts = counts + incoming.counts,
        inexact = inexact + incoming.inexact,
    )

    /** Compact cache form: `media=12;media~=1;files=0`. Inexact keys end with `~`. */
    fun serialize(): String? {
        if (counts.isEmpty() && inexact.isEmpty()) return null
        return ProfileTab.entries.mapNotNull { tab ->
            val count = counts[tab] ?: return@mapNotNull null
            val key = if (tab in inexact) "${tab.wire}~" else tab.wire
            "$key=$count"
        }.joinToString(";").ifEmpty { null }
    }

    companion object {
        fun parse(encoded: String?): ProfileTabCounts {
            if (encoded.isNullOrBlank()) return ProfileTabCounts()
            val counts = LinkedHashMap<ProfileTab, Int>()
            val inexact = LinkedHashSet<ProfileTab>()
            encoded.split(';').forEach { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@forEach
                val rawKey = part.substring(0, separator)
                val exact = !rawKey.endsWith("~")
                val key = if (exact) rawKey else rawKey.dropLast(1)
                val tab = ProfileTab.fromWire(key) ?: return@forEach
                val count = part.substring(separator + 1).toIntOrNull() ?: return@forEach
                counts[tab] = count
                if (!exact) inexact += tab
            }
            return ProfileTabCounts(counts = counts, inexact = inexact)
        }
    }
}

/**
 * One row in a group's member list or a channel's subscriber/administrator list.
 *
 * [role] is `owner`, `admin`, or `member`; [rank] is the free-form admin title set by the
 * group when present, which telegram clients render next to the name.
 */
data class ProfileMember(
    val id: PeerId,
    val title: String,
    val username: String? = null,
    val avatarCacheKey: String? = null,
    val status: String? = null,
    val statusAt: Long? = null,
    val role: String = ROLE_MEMBER,
    val rank: String? = null,
) {
    val isOwner: Boolean get() = role == ROLE_OWNER
    val isAdmin: Boolean get() = role == ROLE_ADMIN
    val hasBadge: Boolean get() = isOwner || isAdmin || !rank.isNullOrBlank()

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"
    }
}

/** A page of [ProfileMember] with the server-reported total. */
data class ProfileMemberPage(
    val count: Int,
    val members: List<ProfileMember>,
)
