package org.monogram.network.bridge.message

import org.monogram.core.models.ScheduledMessage
import uniffi.monogram_mtproto.ScheduledMessageDto

internal fun ScheduledMessageDto.toModel(): ScheduledMessage =
    ScheduledMessage(message.toModel(), hasUnsupportedContent)
