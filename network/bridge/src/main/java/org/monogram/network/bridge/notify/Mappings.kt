package org.monogram.network.bridge.notify

import org.monogram.core.models.NotifyException
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import uniffi.monogram_mtproto.NotifyExceptionDto
import uniffi.monogram_mtproto.NotifySettingsDto

internal fun NotifySettingsDto.toModel(): NotifySettings = NotifySettings(
    showPreviews = showPreviews,
    silent = silent,
    muteUntil = muteUntil,
    storiesMuted = storiesMuted,
    storiesHideSender = storiesHideSender,
    sound = sound.ifBlank { "default" },
)

internal fun NotifyExceptionDto.toModel(): NotifyException = NotifyException(
    peerKind = peerKind,
    chatId = PeerId(chatId),
    settings = NotifySettings(
        showPreviews = showPreviews,
        silent = silent,
        muteUntil = muteUntil,
        storiesMuted = storiesMuted,
        storiesHideSender = storiesHideSender,
        sound = sound.ifBlank { "default" },
    ),
)
