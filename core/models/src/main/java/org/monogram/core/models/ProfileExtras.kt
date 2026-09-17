package org.monogram.core.models

data class ProfileExtra(
    val phone: String? = null,
    val membersCount: Int? = null,
    val onlineCount: Int? = null,
    val commonChatsCount: Int? = null,
    val isBot: Boolean = false,
    val isVerified: Boolean = false,
    val isScam: Boolean = false,
    val isPremium: Boolean = false,
    val emojiStatusDocumentId: Long? = null,
    /** `null` = unknown (not a channel or not fetched). */
    val canViewParticipants: Boolean? = null,
)

object ProfileExtras {
    fun fromProfile(profile: Profile): ProfileExtra = ProfileExtra(
        phone = profile.phone,
        membersCount = profile.membersCount,
        onlineCount = profile.onlineCount,
        commonChatsCount = profile.commonChatsCount,
        isBot = profile.isBot,
        isVerified = profile.isVerified,
        isScam = profile.isScam,
        isPremium = profile.isPremium,
        emojiStatusDocumentId = profile.emojiStatusDocumentId,
        canViewParticipants = profile.canViewParticipants,
    )

    fun applyTo(profile: Profile, extra: ProfileExtra): Profile = profile.copy(
        phone = extra.phone ?: profile.phone,
        membersCount = extra.membersCount ?: profile.membersCount,
        onlineCount = extra.onlineCount ?: profile.onlineCount,
        commonChatsCount = extra.commonChatsCount ?: profile.commonChatsCount,
        isBot = extra.isBot,
        isVerified = extra.isVerified,
        isScam = extra.isScam,
        isPremium = extra.isPremium,
        emojiStatusDocumentId = extra.emojiStatusDocumentId ?: profile.emojiStatusDocumentId,
        canViewParticipants = extra.canViewParticipants ?: profile.canViewParticipants,
    )

    /** Full getProfile row: booleans from incoming, nullable fields keep prior when omitted. */
    fun mergeFull(prior: ProfileExtra, incoming: ProfileExtra): ProfileExtra = ProfileExtra(
        phone = incoming.phone ?: prior.phone,
        membersCount = incoming.membersCount ?: prior.membersCount,
        onlineCount = incoming.onlineCount ?: prior.onlineCount,
        commonChatsCount = incoming.commonChatsCount ?: prior.commonChatsCount,
        isBot = incoming.isBot,
        isVerified = incoming.isVerified,
        isScam = incoming.isScam,
        isPremium = incoming.isPremium,
        emojiStatusDocumentId = incoming.emojiStatusDocumentId ?: prior.emojiStatusDocumentId,
        canViewParticipants = incoming.canViewParticipants ?: prior.canViewParticipants,
    )

    fun serialize(extra: ProfileExtra): String? {
        if (extra == ProfileExtra()) return null
        val parts = mutableListOf<String>()
        extra.phone?.takeIf { it.isNotBlank() }?.let {
            parts += "\"phone\":\"${CompactJson.escape(it)}\""
        }
        extra.membersCount?.takeIf { it > 0 }?.let { parts += "\"members\":$it" }
        extra.onlineCount?.takeIf { it > 0 }?.let { parts += "\"online\":$it" }
        extra.commonChatsCount?.takeIf { it > 0 }?.let { parts += "\"common\":$it" }
        if (extra.isBot) parts += "\"bot\":true"
        if (extra.isVerified) parts += "\"verified\":true"
        if (extra.isScam) parts += "\"scam\":true"
        if (extra.isPremium) parts += "\"premium\":true"
        extra.emojiStatusDocumentId?.let { parts += "\"emoji\":$it" }
        extra.canViewParticipants?.let { parts += "\"participants\":$it" }
        if (parts.isEmpty()) return null
        return "{${parts.joinToString(",")}}"
    }

    fun parse(json: String?): ProfileExtra {
        if (json.isNullOrBlank()) return ProfileExtra()
        val root = CompactJson.parse(json) as? Map<*, *> ?: return ProfileExtra()
        return ProfileExtra(
            phone = root.jsonString("phone")?.takeIf { it.isNotBlank() },
            membersCount = root.jsonInt("members")?.takeIf { it > 0 },
            onlineCount = root.jsonInt("online")?.takeIf { it > 0 },
            commonChatsCount = root.jsonInt("common")?.takeIf { it > 0 },
            isBot = root.jsonBool("bot") == true,
            isVerified = root.jsonBool("verified") == true,
            isScam = root.jsonBool("scam") == true,
            isPremium = root.jsonBool("premium") == true,
            emojiStatusDocumentId = root.jsonLong("emoji"),
            canViewParticipants = root.jsonBool("participants"),
        )
    }
}
