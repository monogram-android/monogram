package org.monogram.domain.models

data class MessageViewerModel(
    val user: UserModel,
    val viewedDate: Int
)