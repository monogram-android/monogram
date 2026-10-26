package org.monogram.network.bridge.message

import org.monogram.core.models.MessageSendOptions
import org.monogram.core.models.OutgoingMessageResult

internal fun MessageSendOptions.toNative() = uniffi.monogram_mtproto.SendOptions(
    silent = silent, noWebpage = noWebpage, scheduleDate = scheduleDate,
)

internal fun uniffi.monogram_mtproto.OutgoingMessageResult.toModel(): OutgoingMessageResult =
    when (this) {
        is uniffi.monogram_mtproto.OutgoingMessageResult.Sent -> OutgoingMessageResult.Sent(message.toModel())
        is uniffi.monogram_mtproto.OutgoingMessageResult.Scheduled -> OutgoingMessageResult.Scheduled(
            message.toModel()
        )
    }
