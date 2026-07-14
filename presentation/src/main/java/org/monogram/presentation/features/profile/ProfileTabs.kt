package org.monogram.presentation.features.profile

import androidx.annotation.StringRes
import org.monogram.domain.models.ProfileTabType
import org.monogram.domain.repository.ProfileMediaFilter
import org.monogram.presentation.R

enum class ProfileTabKey {
    STORIES,
    MEDIA,
    MEMBERS,
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
    MEMBERS_LIST
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
    preferredTabKey: ProfileTabKey?
): List<ProfileTabSpec> {
    val supportedKeys = if (isGroupOrChannel) {
        listOf(
            ProfileTabKey.STORIES,
            ProfileTabKey.MEDIA,
            ProfileTabKey.MEMBERS,
            ProfileTabKey.FILES,
            ProfileTabKey.MUSIC,
            ProfileTabKey.VOICE,
            ProfileTabKey.LINKS,
            ProfileTabKey.GIFS
        )
    } else {
        listOf(
            ProfileTabKey.STORIES,
            ProfileTabKey.MEDIA,
            ProfileTabKey.FILES,
            ProfileTabKey.MUSIC,
            ProfileTabKey.VOICE,
            ProfileTabKey.LINKS,
            ProfileTabKey.GIFS
        )
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

fun ProfileTabKey.toProfileMediaFilter(): ProfileMediaFilter? =
    when (this) {
        ProfileTabKey.STORIES -> null
        ProfileTabKey.MEDIA -> ProfileMediaFilter.MEDIA
        ProfileTabKey.FILES -> ProfileMediaFilter.FILES
        ProfileTabKey.MUSIC -> ProfileMediaFilter.AUDIO
        ProfileTabKey.VOICE -> ProfileMediaFilter.VOICE
        ProfileTabKey.LINKS -> ProfileMediaFilter.LINKS
        ProfileTabKey.GIFS -> ProfileMediaFilter.GIFS
        ProfileTabKey.MEMBERS -> null
    }

fun ProfileTabKey.contentType(): ProfileTabContentType =
    when (this) {
        ProfileTabKey.STORIES -> ProfileTabContentType.STORIES_LIST
        ProfileTabKey.MEDIA,
        ProfileTabKey.GIFS -> ProfileTabContentType.MEDIA_GRID

        ProfileTabKey.MEMBERS -> ProfileTabContentType.MEMBERS_LIST
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
        ProfileTabKey.FILES -> R.string.tab_files
        ProfileTabKey.MUSIC -> R.string.tab_music
        ProfileTabKey.VOICE -> R.string.tab_voice
        ProfileTabKey.LINKS -> R.string.tab_links
        ProfileTabKey.GIFS -> R.string.tab_gifs
    }
