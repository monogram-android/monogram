package org.monogram.root

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletNavigationTest {
    @Test
    fun selectingChatFromVisibleListReplacesDetailAndItsNestedScreens() {
        val initial = listOf(
            RootComponent.Config.Home,
            RootComponent.Config.Dialog(10),
            RootComponent.Config.Dialog(10, threadTopMsgId = 15),
            RootComponent.Config.Profile(20),
        )
        assertEquals(
            listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(30)),
            chatSelectionStack(initial, RootComponent.Config.Dialog(30), listDetailVisible = true),
        )
    }

    @Test
    fun reselectingCurrentChatRetainsConfigurationAndBackReturnsToList() {
        val initial = listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(10))
        val selected = chatSelectionStack(initial, RootComponent.Config.Dialog(10), true)
        assertEquals(initial, selected)
        assertEquals(listOf(RootComponent.Config.Home), selected.dropLast(1))
    }

    @Test
    fun compactNavigationPreservesPreviousDestination() {
        val initial = listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(10))
        assertEquals(
            initial + RootComponent.Config.Dialog(20),
            chatSelectionStack(initial, RootComponent.Config.Dialog(20), false),
        )
    }

    @Test
    fun compactReopeningAnOpenChatReturnsToIt() {
        // Chat 10 -> profile -> "open chat": the chat is still below the profile.
        val initial = listOf(
            RootComponent.Config.Home,
            RootComponent.Config.Dialog(10),
            RootComponent.Config.Profile(10),
        )
        val selected = chatSelectionStack(initial, RootComponent.Config.Dialog(10), false)
        assertEquals(
            listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(10)),
            selected,
        )
        assertEquals(selected.distinct().size, selected.size)
    }

    @Test
    fun compactReopeningTheCurrentChatKeepsItsInstance() {
        val initial = listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(10))
        assertEquals(initial, chatSelectionStack(initial, RootComponent.Config.Dialog(10), false))
    }

    @Test
    fun uniqueStackReturnsToAnEqualConfigurationAndDropsTheRest() {
        val initial = listOf(
            RootComponent.Config.Home,
            RootComponent.Config.Dialog(10),
            RootComponent.Config.Profile(10),
            RootComponent.Config.Profile(20),
        )
        assertEquals(
            listOf(RootComponent.Config.Home, RootComponent.Config.Dialog(10)),
            uniqueStack(initial, RootComponent.Config.Dialog(10)),
        )
        assertEquals(
            initial + RootComponent.Config.Profile(30),
            uniqueStack(initial, RootComponent.Config.Profile(30)),
        )
    }
}
