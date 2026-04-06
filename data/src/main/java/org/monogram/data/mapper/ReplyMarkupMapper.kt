package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.*

internal fun TdApi.ReplyMarkup?.toDomainReplyMarkup(): ReplyMarkupModel? {
    return when (this) {
        is TdApi.ReplyMarkupInlineKeyboard -> {
            ReplyMarkupModel.InlineKeyboard(
                rows = rows.map { row ->
                    row.map { button ->
                        InlineKeyboardButtonModel(
                            text = button.text,
                            type = when (val type = button.type) {
                                is TdApi.InlineKeyboardButtonTypeUrl -> InlineKeyboardButtonType.Url(type.url)
                                is TdApi.InlineKeyboardButtonTypeCallback -> InlineKeyboardButtonType.Callback(type.data)
                                is TdApi.InlineKeyboardButtonTypeWebApp -> InlineKeyboardButtonType.WebApp(type.url)
                                is TdApi.InlineKeyboardButtonTypeLoginUrl -> InlineKeyboardButtonType.LoginUrl(
                                    type.url,
                                    type.id
                                )

                                is TdApi.InlineKeyboardButtonTypeSwitchInline -> InlineKeyboardButtonType.SwitchInline(
                                    query = type.query
                                )

                                is TdApi.InlineKeyboardButtonTypeBuy -> InlineKeyboardButtonType.Buy()
                                is TdApi.InlineKeyboardButtonTypeUser -> InlineKeyboardButtonType.User(type.userId)
                                else -> InlineKeyboardButtonType.Unsupported
                            }
                        )
                    }
                }
            )
        }

        is TdApi.ReplyMarkupShowKeyboard -> {
            ReplyMarkupModel.ShowKeyboard(
                rows = rows.map { row ->
                    row.map { button ->
                        KeyboardButtonModel(
                            text = button.text,
                            type = when (val type = button.type) {
                                is TdApi.KeyboardButtonTypeText -> KeyboardButtonType.Text
                                is TdApi.KeyboardButtonTypeRequestPhoneNumber -> KeyboardButtonType.RequestPhoneNumber
                                is TdApi.KeyboardButtonTypeRequestLocation -> KeyboardButtonType.RequestLocation
                                is TdApi.KeyboardButtonTypeRequestPoll -> KeyboardButtonType.RequestPoll(
                                    type.forceQuiz,
                                    type.forceRegular
                                )

                                is TdApi.KeyboardButtonTypeWebApp -> KeyboardButtonType.WebApp(type.url)
                                is TdApi.KeyboardButtonTypeRequestUsers -> KeyboardButtonType.RequestUsers(type.id)
                                is TdApi.KeyboardButtonTypeRequestChat -> KeyboardButtonType.RequestChat(type.id)
                                else -> KeyboardButtonType.Unsupported
                            }
                        )
                    }
                },
                isPersistent = isPersistent,
                resizeKeyboard = resizeKeyboard,
                oneTime = oneTime,
                isPersonal = isPersonal,
                inputFieldPlaceholder = inputFieldPlaceholder
            )
        }

        is TdApi.ReplyMarkupRemoveKeyboard -> ReplyMarkupModel.RemoveKeyboard(isPersonal)
        is TdApi.ReplyMarkupForceReply -> ReplyMarkupModel.ForceReply(isPersonal, inputFieldPlaceholder)
        else -> null
    }
}