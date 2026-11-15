package org.monogram.feature.chats

import org.monogram.network.http.MediaRepository

data class AccountMenuItem(
    val userId: Long,
    val title: String,
    val needsSignIn: Boolean = false,
    val active: Boolean = false,
    val unavailable: Boolean = false,
    val avatarCacheKey: String? = null,
    val mediaRepository: MediaRepository? = null,
)
