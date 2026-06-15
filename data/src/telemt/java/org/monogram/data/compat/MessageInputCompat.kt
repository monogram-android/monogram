package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildInputPhoto(file: TdApi.InputFile): TdApi.InputFile = file

internal fun buildInputVideo(file: TdApi.InputFile): TdApi.InputFile = file

internal fun buildInputDocument(
    file: TdApi.InputFile,
    disableContentTypeDetection: Boolean
): TdApi.InputFile = file

internal fun buildInputAnimation(file: TdApi.InputFile): TdApi.InputFile = file
