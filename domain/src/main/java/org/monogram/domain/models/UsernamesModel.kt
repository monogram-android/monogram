package org.monogram.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class UsernamesModel(
    val activeUsernames: List<String> = emptyList(),
    val disabledUsernames: List<String> = emptyList(),
    val collectibleUsernames: List<String> = emptyList()
)
