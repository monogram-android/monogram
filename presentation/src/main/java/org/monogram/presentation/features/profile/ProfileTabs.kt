package org.monogram.presentation.features.profile

import androidx.annotation.StringRes
import org.monogram.domain.models.ProfileTabType
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.domain.repository.ProfileMediaFilter
import org.monogram.presentation.R

enum class ProfileTabKey {
    STORIES,
    MEDIA,
    MEMBERS,
    ADMINS,
    RESTRICTED,
    BANNED,
    SIMILAR,
    FILES,
    MUSIC,
    VOICE,
    LINKS,
    GIFS
}

enum class ProfileTabContentType {
    STORIES_LIST,
    MEDIA_GRID,
    MESSAGE_LIST,
    MEMBERS_LIST,
    CHAT_LIST
}

data class ProfileTabSpec(
    val key: ProfileTabKey,
    @StringRes val titleRes: Int,
    val contentType: ProfileTabContentType,
    val isVisible: Boolean = true,
    val isSupported: Boolean = true,
    val initiallySelected: Boolean = false
)

fun buildProfileTabSpecs(
    isGroupOrChannel: Boolean,
    preferredTabKey: ProfileTabKey?,
    showMembers: Boolean = isGroupOrChannel,
    showAdministrators: Boolean = false,
    showRestricted: Boolean = false,
    showBanned: Boolean = false,
    showSimilarChats: Boolean = false,
    showMedia: Boolean = true,
    showFiles: Boolean = true,
    showMusic: Boolean = true,
    showVoice: Boolean = true,
    showLinks: Boolean = true,
    showGifs: Boolean = true
): List<ProfileTabSpec> {
    val supportedKeys = buildList {
        add(ProfileTabKey.STORIES)
        if (showMedia) {
            add(ProfileTabKey.MEDIA)
        }
        if (showMembers) {
            add(ProfileTabKey.MEMBERS)
        }
        if (showAdministrators) {
            add(ProfileTabKey.ADMINS)
        }
        if (showRestricted) {
            add(ProfileTabKey.RESTRICTED)
        }
        if (showBanned) {
            add(ProfileTabKey.BANNED)
        }
        if (showSimilarChats) {
            add(ProfileTabKey.SIMILAR)
        }
        if (showFiles) {
            add(ProfileTabKey.FILES)
        }
        if (showMusic) {
            add(ProfileTabKey.MUSIC)
        }
        if (showVoice) {
            add(ProfileTabKey.VOICE)
        }
        if (showLinks) {
            add(ProfileTabKey.LINKS)
        }
        if (showGifs) {
            add(ProfileTabKey.GIFS)
        }
    }.let { keys ->
        if (isGroupOrChannel) {
            keys
        } else {
            keys.filterNot(ProfileTabKey::isMemberTab)
        }
    }

    val initialKey = preferredTabKey?.takeIf { it in supportedKeys } ?: ProfileTabKey.STORIES

    return supportedKeys.map { key ->
        ProfileTabSpec(
            key = key,
            titleRes = key.titleRes(),
            contentType = key.contentType(),
            initiallySelected = key == initialKey
        )
    }
}

fun buildInitialVisibleProfileTabSpecs(
    supportedTabs: List<ProfileTabSpec>,
    preferredTabKey: ProfileTabKey?
): List<ProfileTabSpec> {
    val visibleKeys = buildSet {
        add(ProfileTabKey.STORIES)
        if (supportedTabs.any { it.key == ProfileTabKey.MEDIA }) {
            add(ProfileTabKey.MEDIA)
        }
        supportedTabs
            .map(ProfileTabSpec::key)
            .filter(ProfileTabKey::isMemberTab)
            .forEach(::add)
        if (supportedTabs.any { it.key == ProfileTabKey.SIMILAR }) {
            add(ProfileTabKey.SIMILAR)
        }
        preferredTabKey
            ?.takeIf { preferred -> supportedTabs.any { it.key == preferred } }
            ?.let(::add)
    }

    return supportedTabs.filter { it.key in visibleKeys }
}

fun ProfileTabType?.toProfileTabKeyOrNull(): ProfileTabKey? =
    when (this) {
        ProfileTabType.MEDIA -> ProfileTabKey.MEDIA
        ProfileTabType.FILES -> ProfileTabKey.FILES
        ProfileTabType.MUSIC -> ProfileTabKey.MUSIC
        ProfileTabType.VOICE -> ProfileTabKey.VOICE
        ProfileTabType.LINKS -> ProfileTabKey.LINKS
        ProfileTabType.GIFS -> ProfileTabKey.GIFS
        else -> null
    }

fun ProfileTabKey.isMemberTab(): Boolean =
    when (this) {
        ProfileTabKey.MEMBERS,
        ProfileTabKey.ADMINS,
        ProfileTabKey.RESTRICTED,
        ProfileTabKey.BANNED -> true

        else -> false
    }

fun ProfileTabKey.isMediaTab(): Boolean =
    when (this) {
        ProfileTabKey.MEDIA,
        ProfileTabKey.FILES,
        ProfileTabKey.MUSIC,
        ProfileTabKey.VOICE,
        ProfileTabKey.LINKS,
        ProfileTabKey.GIFS -> true

        else -> false
    }

fun ProfileTabKey.toChatMembersFilterOrNull(): ChatMembersFilter? =
    when (this) {
        ProfileTabKey.MEMBERS -> ChatMembersFilter.Recent
        ProfileTabKey.ADMINS -> ChatMembersFilter.Administrators
        ProfileTabKey.RESTRICTED -> ChatMembersFilter.Restricted
        ProfileTabKey.BANNED -> ChatMembersFilter.Banned
        else -> null
    }

fun ProfileTabKey.toProfileMediaFilter(): ProfileMediaFilter? =
    when (this) {
        ProfileTabKey.STORIES -> null
        ProfileTabKey.MEDIA -> ProfileMediaFilter.MEDIA
        ProfileTabKey.FILES -> ProfileMediaFilter.FILES
        ProfileTabKey.MUSIC -> ProfileMediaFilter.AUDIO
        ProfileTabKey.VOICE -> ProfileMediaFilter.VOICE
        ProfileTabKey.LINKS -> ProfileMediaFilter.LINKS
        ProfileTabKey.GIFS -> ProfileMediaFilter.GIFS
        ProfileTabKey.MEMBERS,
        ProfileTabKey.ADMINS,
        ProfileTabKey.RESTRICTED,
        ProfileTabKey.BANNED,
        ProfileTabKey.SIMILAR -> null
    }

fun ProfileTabKey.contentType(): ProfileTabContentType =
    when (this) {
        ProfileTabKey.STORIES -> ProfileTabContentType.STORIES_LIST
        ProfileTabKey.MEDIA,
        ProfileTabKey.GIFS -> ProfileTabContentType.MEDIA_GRID

        ProfileTabKey.MEMBERS,
        ProfileTabKey.ADMINS,
        ProfileTabKey.RESTRICTED,
        ProfileTabKey.BANNED -> ProfileTabContentType.MEMBERS_LIST

        ProfileTabKey.SIMILAR -> ProfileTabContentType.CHAT_LIST
        ProfileTabKey.FILES,
        ProfileTabKey.MUSIC,
        ProfileTabKey.VOICE,
        ProfileTabKey.LINKS -> ProfileTabContentType.MESSAGE_LIST
    }

@StringRes
fun ProfileTabKey.titleRes(): Int =
    when (this) {
        ProfileTabKey.STORIES -> R.string.tab_stories
        ProfileTabKey.MEDIA -> R.string.tab_media
        ProfileTabKey.MEMBERS -> R.string.tab_members
        ProfileTabKey.ADMINS -> R.string.tab_admins
        ProfileTabKey.RESTRICTED -> R.string.tab_restricted
        ProfileTabKey.BANNED -> R.string.tab_banned
        ProfileTabKey.SIMILAR -> R.string.tab_similar
        ProfileTabKey.FILES -> R.string.tab_files
        ProfileTabKey.MUSIC -> R.string.tab_music
        ProfileTabKey.VOICE -> R.string.tab_voice
        ProfileTabKey.LINKS -> R.string.tab_links
        ProfileTabKey.GIFS -> R.string.tab_gifs
    }
