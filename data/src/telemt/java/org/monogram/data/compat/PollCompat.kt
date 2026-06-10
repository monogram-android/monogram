package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildInputPollOption(text: TdApi.FormattedText): TdApi.InputPollOption =
    TdApi.InputPollOption(text)

internal fun buildInputPollTypeQuiz(
    correctOptionIds: IntArray,
    explanation: TdApi.FormattedText
): TdApi.InputPollTypeQuiz = TdApi.InputPollTypeQuiz(correctOptionIds, explanation)
