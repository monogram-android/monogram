package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class MessageForwardSelectionTest {
    @Test
    fun albumToggleSelectsEveryEligibleMemberInMessageOrder() {
        val album = listOf(message(31, groupedId = 8), message(29, groupedId = 8))

        assertEquals(listOf(29, 31), toggleForwardSelection(emptyList(), album))
        assertEquals(emptyList<Int>(), toggleForwardSelection(listOf(29, 31), album))
    }

    @Test
    fun pendingAndServiceRowsCannotEnterSelection() {
        assertEquals(
            emptyList<Int>(),
            toggleForwardSelection(
                emptyList(),
                listOf(message(7, pending = true), message(8, mediaKind = "service")),
            ),
        )
    }

    @Test
    fun albumDoesNotPartiallySelectPastLimit() {
        val selected = (1..99).toList()

        assertEquals(
            selected,
            toggleForwardSelection(selected, listOf(message(100, groupedId = 9), message(101, groupedId = 9))),
        )
    }

    @Test
    fun forwardingExcludesRestrictedOrIncompleteAlbumsAndSortsIds() {
        val messages = listOf(
            message(12),
            message(10, groupedId = 4),
            message(9, groupedId = 4, noforwards = true),
            message(7),
        )

        assertEquals(
            listOf(7, 12),
            selectedForwardableMessages(messages, listOf(7, 9, 10, 12)) { !it.noforwards }
                .map { it.id.id },
        )
    }

    @Test
    fun protectedChatAndNoforwardsRowsCannotEnterSelection() {
        assertEquals(
            emptyList<Int>(),
            toggleForwardSelection(emptyList(), listOf(message(7)), chatCanForward = false),
        )
        assertEquals(
            emptyList<Int>(),
            toggleForwardSelection(emptyList(), listOf(message(7, noforwards = true))),
        )
    }

    @Test
    fun pruningRemovesDeletedSelectionIds() {
        assertEquals(
            listOf(4),
            pruneForwardSelection(listOf(4, 8), listOf(message(4))),
        )
    }

    private fun message(
        id: Int,
        groupedId: Long? = null,
        pending: Boolean = false,
        mediaKind: String? = null,
        noforwards: Boolean = false,
    ) = Message(
        id = MessageId(PeerId(1), id),
        senderId = PeerId(1),
        text = null,
        date = 0,
        outgoing = false,
        groupedId = groupedId,
        pending = pending,
        mediaKind = mediaKind,
        noforwards = noforwards,
    )
}
