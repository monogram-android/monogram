package org.monogram.domain.models

data class BotCommandModel(
    val command: String,
    val description: String
)

sealed interface BotMenuButtonModel {
    object Commands : BotMenuButtonModel
    data class WebApp(val text: String, val url: String) : BotMenuButtonModel
    object Default : BotMenuButtonModel
}

data class BotInfoModel(
    val commands: List<BotCommandModel>,
    val menuButton: BotMenuButtonModel
)

data class InlineQueryResultModel(
    val id: String,
    val type: String,
    val title: String?,
    val description: String?,
    val thumbUrl: String?,
    val thumbFileId: Int = 0,
    val content: MessageContent? = null,
    val replyMarkup: ReplyMarkupModel? = null,
    val width: Int = 0,
    val height: Int = 0
)
