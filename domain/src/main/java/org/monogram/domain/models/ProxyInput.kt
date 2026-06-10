package org.monogram.domain.models

data class ProxyInput(
    val server: String,
    val port: Int,
    val comment: String? = null,
    val type: ProxyType
)
