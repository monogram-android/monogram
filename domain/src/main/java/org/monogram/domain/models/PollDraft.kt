package org.monogram.domain.models

data class PollDraft(
    val question: String,
    val options: List<String>,
    val description: String? = null,
    val isAnonymous: Boolean = true,
    val allowsMultipleAnswers: Boolean = false,
    val allowsRevoting: Boolean = true,
    val shuffleOptions: Boolean = false,
    val hideResultsUntilCloses: Boolean = false,
    val openPeriod: Int = 0,
    val closeDate: Int = 0,
    val isClosed: Boolean = false,
    val isQuiz: Boolean = false,
    val correctOptionIds: List<Int> = emptyList(),
    val explanation: String? = null
)
