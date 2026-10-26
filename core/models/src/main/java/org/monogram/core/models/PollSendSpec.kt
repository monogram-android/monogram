package org.monogram.core.models

data class PollSendSpec(
    val question: String,
    val answers: List<String>,
    val anonymous: Boolean = true,
    val multipleChoice: Boolean = false,
    val quiz: Boolean = false,
    val correctAnswer: Int? = null,
    val solution: String? = null,
) {
    fun requireValid() {
        require(question.isNotBlank() && question.length <= 255)
        require(answers.size in 2..10 && answers.all { it.isNotBlank() && it.length <= 100 })
        require(!quiz || !multipleChoice && correctAnswer != null)
        require(quiz || correctAnswer == null && solution == null)
        require(correctAnswer == null || correctAnswer in answers.indices)
        require(solution == null || solution.isNotBlank() && solution.length <= 200)
    }
}
