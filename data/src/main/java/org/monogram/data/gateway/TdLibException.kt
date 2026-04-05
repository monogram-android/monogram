package org.monogram.data.gateway

import org.drinkless.tdlib.TdApi

class TdLibException(val error: TdApi.Error) : Exception(error.message)

fun Throwable.toUserMessage(defaultMessage: String = "Unknown error"): String {
    val tdMessage = (this as? TdLibException)?.error?.message.orEmpty()
    return tdMessage.ifEmpty { message ?: defaultMessage }
}