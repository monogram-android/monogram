package org.monogram.network.bridge

data class ClientInitInfo(
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val systemLangCode: String,
    val langPack: String = "android",
    val langCode: String,
)
