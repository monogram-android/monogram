package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.AdvertisementSponsorModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.SponsoredMessageModel
import org.monogram.domain.models.SponsoredMessagesFeedModel

class ConversationSponsoredPlacementTest {
    @Test
    fun `shows only first sponsored item until enough messages are loaded`() {
        val items = buildConversationListItems(
            groupedMessages = (1L..5L).map { GroupedMessageItem.Single(message(it)) },
            sponsoredFeed = SponsoredMessagesFeedModel(
                messages = listOf(sponsored(900L), sponsored(901L)),
                messagesBetween = 10
            )
        )

        val sponsoredIds = items.mapNotNull { item ->
            (item as? ConversationListItem.Sponsored)?.sponsoredMessage?.messageId
        }

        assertEquals(listOf(900L), sponsoredIds)
    }

    @Test
    fun `inserts next sponsored item after messagesBetween counting album messages`() {
        val items = buildConversationListItems(
            groupedMessages = listOf(
                GroupedMessageItem.Single(message(1L)),
                GroupedMessageItem.Album(
                    albumId = 77L,
                    messages = listOf(
                        message(2L, albumId = 77L),
                        message(3L, albumId = 77L),
                        message(4L, albumId = 77L)
                    )
                ),
                GroupedMessageItem.Single(message(5L))
            ),
            sponsoredFeed = SponsoredMessagesFeedModel(
                messages = listOf(sponsored(900L), sponsored(901L), sponsored(902L)),
                messagesBetween = 4
            )
        )

        assertTrue(items[0] is ConversationListItem.Sponsored)
        assertTrue(items[1] is ConversationListItem.Grouped)
        assertTrue(items[2] is ConversationListItem.Grouped)
        assertTrue(items[3] is ConversationListItem.Sponsored)
        assertTrue(items[4] is ConversationListItem.Grouped)
        assertEquals(
            listOf(900L, 901L),
            items.mapNotNull { (it as? ConversationListItem.Sponsored)?.sponsoredMessage?.messageId }
        )
    }

    @Test
    fun `stacks sponsored items first when messagesBetween is zero`() {
        val items = buildConversationListItems(
            groupedMessages = listOf(
                GroupedMessageItem.Single(message(1L)),
                GroupedMessageItem.Single(message(2L))
            ),
            sponsoredFeed = SponsoredMessagesFeedModel(
                messages = listOf(sponsored(900L), sponsored(901L)),
                messagesBetween = 0
            )
        )

        assertEquals(
            listOf(
                "channel_sponsored_message_900",
                "channel_sponsored_message_901",
                "msg_1",
                "msg_2"
            ),
            items.map(ConversationListItem::lazyItemKey)
        )
    }

    @Test
    fun `buildGroupedLazyIndexByFirstMessageId skips inline sponsored items`() {
        val items = buildConversationListItems(
            groupedMessages = listOf(
                GroupedMessageItem.Single(message(1L)),
                GroupedMessageItem.Single(message(2L)),
                GroupedMessageItem.Single(message(3L))
            ),
            sponsoredFeed = SponsoredMessagesFeedModel(
                messages = listOf(sponsored(900L), sponsored(901L)),
                messagesBetween = 2
            )
        )

        val lazyIndexes = buildGroupedLazyIndexByFirstMessageId(
            conversationItems = items,
            leadingItemsCount = 1
        )

        assertEquals(2, lazyIndexes[1L])
        assertEquals(3, lazyIndexes[2L])
        assertEquals(5, lazyIndexes[3L])
    }

    private fun message(id: Long, albumId: Long = 0L): MessageModel {
        return MessageModel(
            id = id,
            date = id.toInt(),
            isOutgoing = false,
            senderName = "sender",
            chatId = -100L,
            content = MessageContent.Text("message_$id"),
            mediaAlbumId = albumId
        )
    }

    private fun sponsored(id: Long): SponsoredMessageModel {
        return SponsoredMessageModel(
            messageId = id,
            isRecommended = false,
            canBeReported = false,
            title = "Sponsored $id",
            buttonText = "Open",
            content = MessageContent.Text("Sponsored content $id"),
            sponsor = AdvertisementSponsorModel(url = "https://example.com/$id")
        )
    }
}
