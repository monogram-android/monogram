package org.monogram.feature.dialog.store

import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.models.*

class PollVotePolicyTest {
    private val first = byteArrayOf(0, -1)
    private val second = byteArrayOf(3, 0, 7)
    private val poll = Poll(
        "Fixture",
        listOf(PollAnswer("First", option = first), PollAnswer("Second", option = second))
    )

    private fun message(value: Poll = poll): Message {
        val answers = value.answers.joinToString(",") {
            """{"t":"Fixture answer","c":${it.chosen},"o":"${pollOptionHex(it.option)}"}"""
        }
        val payload =
            """{"q":"Fixture","z":${value.isQuiz},"m":${value.multipleChoice},"x":${value.closed},"r":${value.revotingDisabled},"a":[$answers]}"""
        return Message(
            MessageId(PeerId(7), 37), null, null, 100, outgoing = false,
            mediaKind = "poll", fileName = payload
        )
    }

    @Test
    fun revotingDisabledStillAllowsFirstVoteButForbidsRetractionAndReplacement() {
        val locked = poll.copy(revotingDisabled = true)
        assertTrue(pollVoteAllowed(message(locked), listOf(first)))
        val answered = locked.copy(
            answers = listOf(
                locked.answers.first().copy(chosen = true),
                locked.answers.last()
            )
        )
        assertFalse(pollVoteAllowed(message(answered), emptyList()))
        assertFalse(pollVoteAllowed(message(answered), listOf(second)))
        assertTrue(message(locked).poll!!.revotingDisabled)
        assertFalse(message().poll!!.revotingDisabled)
    }

    @Test
    fun ordinaryAnsweredPollCanRetractButUnansweredCannot() {
        assertFalse(pollVoteAllowed(message(), emptyList()))
        val answered = poll.copy(
            answers = listOf(
                poll.answers.first().copy(chosen = true),
                poll.answers.last()
            )
        )
        assertTrue(pollVoteAllowed(message(answered), emptyList()))
        assertFalse(pollVoteAllowed(message(answered.copy(isQuiz = true)), emptyList()))
    }

    @Test
    fun multipleChoiceSubmitsEveryOpaqueOptionButSingleChoiceRejectsSeveral() {
        val options = listOf(first.copyOf(), second.copyOf())
        assertFalse(pollVoteAllowed(message(), options))
        assertTrue(pollVoteAllowed(message(poll.copy(multipleChoice = true)), options))
        assertTrue(pollVoteAllowed(message(), listOf(first.copyOf())))
    }

    @Test
    fun duplicatesAndUnknownOptionsAreRejectedByByteContent() {
        val multiple = message(poll.copy(multipleChoice = true))
        assertFalse(pollVoteAllowed(multiple, listOf(first, first.copyOf())))
        assertFalse(pollVoteAllowed(multiple, listOf(byteArrayOf(5))))
    }

    @Test
    fun closedPendingMissingAndLocalMessagesCannotVote() {
        assertFalse(pollVoteAllowed(message(poll.copy(closed = true)), listOf(first)))
        assertFalse(pollVoteAllowed(message().copy(pending = true), listOf(first)))
        assertFalse(pollVoteAllowed(message().copy(fileName = null), listOf(first)))
        assertFalse(pollVoteAllowed(message().copy(id = MessageId(PeerId(7), -1)), listOf(first)))
        val quiz = poll.copy(
            isQuiz = true,
            answers = listOf(poll.answers.first().copy(chosen = true), poll.answers.last())
        )
        assertFalse(pollVoteAllowed(message(quiz), listOf(second)))
    }
}
