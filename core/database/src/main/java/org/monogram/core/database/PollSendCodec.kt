package org.monogram.core.database

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.monogram.core.models.PollSendSpec

fun encodePollSend(spec: PollSendSpec): String {
    spec.requireValid()
    return storedPayloadJson.encodeToString(
        StoredPollSend(
            question = spec.question,
            answers = spec.answers.mapIndexed { index, title ->
                StoredPollAnswer(
                    title,
                    (48 + index).toString(16)
                )
            },
            quiz = spec.quiz,
            multipleChoice = spec.multipleChoice,
            public = !spec.anonymous,
            send = StoredPollOptions(correct = spec.correctAnswer, solution = spec.solution),
        )
    )
}

fun decodePollSend(raw: String?): PollSendSpec = readStoredPayload {
    val value = storedPayloadJson.decodeFromString<StoredPollSend>(requireNotNull(raw))
    require(value.send.version == 1)
    require(value.answers.size in 2..10)
    value.answers.forEachIndexed { index, answer ->
        require(
            answer.option == (48 + index).toString(
                16
            )
        )
    }
    PollSendSpec(
        question = value.question,
        answers = value.answers.map { it.title },
        anonymous = !value.public,
        multipleChoice = value.multipleChoice,
        quiz = value.quiz,
        correctAnswer = value.send.correct,
        solution = value.send.solution,
    ).also { it.requireValid() }
}

@Serializable
private data class StoredPollSend(
    @SerialName("q") val question: String,
    @SerialName("a") val answers: List<StoredPollAnswer>,
    @SerialName("z") val quiz: Boolean,
    @SerialName("m") val multipleChoice: Boolean,
    @SerialName("o") val public: Boolean,
    val send: StoredPollOptions,
)

@Serializable
private data class StoredPollAnswer(
    @SerialName("t") val title: String,
    @SerialName("o") val option: String,
)

@Serializable
private data class StoredPollOptions(
    @Required val version: Int = 1,
    val correct: Int? = null,
    val solution: String? = null,
)
