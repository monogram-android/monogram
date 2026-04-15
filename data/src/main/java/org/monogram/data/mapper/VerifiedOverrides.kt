package org.monogram.data.mapper

private val VERIFIED_CHAT_IDS = setOf(
    -1003615282448L, // @monogram_discuss
    -1003768707135L, // @monogram_android
    -1003566234286L, // @monogram_apks
    -1001270834900L, // private chat
    -1001336987857L // @LBOGD
)

private val VERIFIED_USER_IDS = setOf(
    453024846L, // @gdlbo - Lead developer
    665275967L, // @Rozetka_img - Lead developer
    1250144551L, // @toxyxd - Contributor
    454755463L, // @recodius - Contributor
    1374434073L, // @the8055u - Logo designer
    646667177L // @ramedon - Big thanks
)

fun isForcedVerifiedChat(chatId: Long): Boolean = chatId in VERIFIED_CHAT_IDS

fun isForcedVerifiedUser(userId: Long): Boolean = userId in VERIFIED_USER_IDS
