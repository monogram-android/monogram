package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildInputPhoto(file: TdApi.InputFile): TdApi.InputPhoto =
    TdApi.InputPhoto(file, null, null, intArrayOf(), 0, 0)

internal fun buildInputVideo(file: TdApi.InputFile): TdApi.InputVideo =
    TdApi.InputVideo(file, null, null, 0, intArrayOf(), 0, 0, 0, false)

internal fun buildInputDocument(
    file: TdApi.InputFile,
    disableContentTypeDetection: Boolean
): TdApi.InputDocument = TdApi.InputDocument(file, null, disableContentTypeDetection)

internal fun buildInputAnimation(file: TdApi.InputFile): TdApi.InputAnimation =
    TdApi.InputAnimation(file, null, intArrayOf(), 0, 0, 0)

internal fun buildInputSticker(
    file: TdApi.InputFile,
    width: Int,
    height: Int
): TdApi.InputSticker = TdApi.InputSticker(file, null, width, height)

internal fun buildInputVideoNote(
    file: TdApi.InputFile,
    duration: Int,
    length: Int
): TdApi.InputVideoNote = TdApi.InputVideoNote(file, null, duration, length)

internal fun buildInputVoiceNote(
    file: TdApi.InputFile,
    duration: Int,
    waveform: ByteArray
): TdApi.InputVoiceNote = TdApi.InputVoiceNote(file, duration, waveform)
