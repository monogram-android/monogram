package org.monogram.domain.models

data class ProxyInput(
    val server: String,
    val port: Int,
    val type: ProxyType
)
