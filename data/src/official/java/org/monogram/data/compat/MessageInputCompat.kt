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
