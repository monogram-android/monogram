package org.monogram.feature.chats

data class AccountMenuItem(
    val userId: Long,
    val title: String,
    val needsSignIn: Boolean = false,
    val active: Boolean = false,
    val unavailable: Boolean = false,
)