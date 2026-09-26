package org.monogram.feature.dialog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.feature.dialog.DialogStore

class MessageMenuActionsTest {
    @Test
    fun chatWideProtectionHidesForward() {
        val actions = messageMenuActions(state(canForward = false), message())
        assertFalse(actions.canForward)
        assertFalse(actions.forwardRestricted)
        assertFalse(actions.canSelectForForwarding)
    }

    @Test
    fun perMessageNoforwardsShowsDisabledForward() {
        val actions = messageMenuActions(state(canForward = true), message(noforwards = true))
        assertFalse(actions.canForward)
        assertTrue(actions.forwardRestricted)
        assertFalse(actions.canSelectForForwarding)
    }

    @Test
    fun pendingAndServiceHideForward() {
        val pending = messageMenuActions(state(), message(pending = true))
        val service = messageMenuActions(state(), message(mediaKind = "service"))
        assertFalse(pending.canForward)
        assertFalse(pending.forwardRestricted)
        assertFalse(service.canForward)
        assertFalse(service.forwardRestricted)
    }

    @Test
    fun ordinaryMessageCanForwardAndSelect() {
        val actions = messageMenuActions(state(), message())
        assertTrue(actions.canForward)
        assertFalse(actions.forwardRestricted)
        assertTrue(actions.canSelectForForwarding)
    }

    private fun state(canForward: Boolean = true) = DialogStore.State(
        chatId = PeerId(1),
        canForward = canForward,
    )

    private fun message(
        pending: Boolean = false,
        mediaKind: String? = null,
        noforwards: Boolean = false,
    ) = Message(
        id = MessageId(PeerId(1), 7),
        senderId = PeerId(1),
        text = "hi",
        date = 0,
        outgoing = false,
        pending = pending,
        mediaKind = mediaKind,
        noforwards = noforwards,
    )
}
