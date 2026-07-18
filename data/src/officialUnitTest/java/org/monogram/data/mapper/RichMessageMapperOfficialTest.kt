package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.webapp.HorizontalAlignment
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.PageBlockTableCell
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.models.webapp.VerticalAlignment

class RichMessageMapperOfficialTest {

    @Test
    fun `rich message preserves blocks and fetch metadata`() {
        val tdRichMessage = TdApi.RichMessage(
            arrayOf(
                TdApi.PageBlockHeader(TdApi.RichTextPlain("Title")),
                TdApi.PageBlockParagraph(TdApi.RichTextBold(TdApi.RichTextPlain("Body")))
            ),
            true,
            false
        )

        val mapped = tdRichMessage.toDomainRichMessage(
            chatId = 10L,
            messageId = 20L
        )

        assertEquals(10L, mapped.chatId)
        assertEquals(20L, mapped.messageId)
        assertEquals("## Title\n\n**Body**", mapped.markdownSource)
        assertTrue(mapped.isRtl)
        assertEquals(false, mapped.isFull)
        assertEquals(PageBlock.Header(RichText.Plain("Title")), mapped.blocks[0])
        assertEquals(PageBlock.Paragraph(RichText.Bold(RichText.Plain("Body"))), mapped.blocks[1])
    }

    @Test
    fun `rich text mapper covers telegram rich text entities`() {
        val richText = TdApi.RichTexts(
            arrayOf(
                TdApi.RichTextSpoiler(TdApi.RichTextPlain("secret")),
                TdApi.RichTextDateTime(TdApi.RichTextPlain("today"), 123456, null),
                TdApi.RichTextMention(TdApi.RichTextPlain("@alice"), "alice"),
                TdApi.RichTextHashtag(TdApi.RichTextPlain("#tag"), "tag"),
                TdApi.RichTextCashtag(TdApi.RichTextPlain("${'$'}ABC"), "ABC"),
                TdApi.RichTextBotCommand(TdApi.RichTextPlain("/start"), "start"),
                TdApi.RichTextMentionName(TdApi.RichTextPlain("Bob"), 42L),
                TdApi.RichTextBankCardNumber(TdApi.RichTextPlain("card"), "1234"),
                TdApi.RichTextCustomEmoji(99L, ":smile:"),
                TdApi.RichTextMathematicalExpression("x^2"),
                TdApi.RichTextReference("note", TdApi.RichTextPlain("Footnote")),
                TdApi.RichTextReferenceLink(
                    TdApi.RichTextPlain("ref"),
                    "note",
                    "https://example.com/#note"
                )
            )
        )

        val mapped = richText.toRichText()

        assertEquals(
            RichText.Texts(
                listOf(
                    RichText.Spoiler(RichText.Plain("secret")),
                    RichText.DateTime(RichText.Plain("today"), 123456),
                    RichText.Mention(RichText.Plain("@alice"), "alice"),
                    RichText.Hashtag(RichText.Plain("#tag"), "tag"),
                    RichText.Cashtag(RichText.Plain("${'$'}ABC"), "ABC"),
                    RichText.BotCommand(RichText.Plain("/start"), "start"),
                    RichText.MentionName(RichText.Plain("Bob"), 42L),
                    RichText.BankCardNumber(RichText.Plain("card"), "1234"),
                    RichText.CustomEmoji(99L, ":smile:"),
                    RichText.MathematicalExpression("x^2"),
                    RichText.Reference(RichText.Plain("Footnote"), "note", ""),
                    RichText.Reference(RichText.Plain("ref"), "note", "https://example.com/#note")
                )
            ),
            mapped
        )
    }

    @Test
    fun `table preformatted block is mapped to table`() {
        val tdBlock = TdApi.PageBlockPreformatted(
            TdApi.RichTextPlain(
                """
                ┌──────────┬──────────┐
                │ Header 1 │ Header 2 │
                ├──────────┼──────────┤
                │ Value 1  │ Value 2  │
                └──────────┴──────────┘
                """.trimIndent()
            ),
            "table"
        )

        val mapped = tdBlock.toPageBlock()

        assertEquals(
            PageBlock.Table(
                caption = RichText.Plain(""),
                cells = listOf(
                    listOf(
                        PageBlockTableCell(
                            text = RichText.Plain("Header 1"),
                            isHeader = true,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        ),
                        PageBlockTableCell(
                            text = RichText.Plain("Header 2"),
                            isHeader = true,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        )
                    ),
                    listOf(
                        PageBlockTableCell(
                            text = RichText.Plain("Value 1"),
                            isHeader = false,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        ),
                        PageBlockTableCell(
                            text = RichText.Plain("Value 2"),
                            isHeader = false,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        )
                    )
                ),
                isBordered = true,
                isStriped = false
            ),
            mapped
        )
    }

    @Test
    fun `blockquote preserves nested blocks including table`() {
        val tdBlock = TdApi.PageBlockBlockQuote(
            arrayOf(
                TdApi.PageBlockParagraph(TdApi.RichTextBold(TdApi.RichTextPlain("Quoted title"))),
                TdApi.PageBlockPreformatted(
                    TdApi.RichTextPlain(
                        """
                        | A | B |
                        |---|---|
                        | 1 | 2 |
                        """.trimIndent()
                    ),
                    "table"
                )
            ),
            TdApi.RichTextPlain("Author")
        )

        val mapped = tdBlock.toPageBlock()

        assertEquals(
            PageBlock.BlockQuote(
                pageBlocks = listOf(
                    PageBlock.Paragraph(RichText.Bold(RichText.Plain("Quoted title"))),
                    PageBlock.Table(
                        caption = RichText.Plain(""),
                        cells = listOf(
                            listOf(
                                PageBlockTableCell(
                                    text = RichText.Plain("A"),
                                    isHeader = true,
                                    colspan = 1,
                                    rowspan = 1,
                                    align = HorizontalAlignment.LEFT,
                                    valign = VerticalAlignment.TOP
                                ),
                                PageBlockTableCell(
                                    text = RichText.Plain("B"),
                                    isHeader = true,
                                    colspan = 1,
                                    rowspan = 1,
                                    align = HorizontalAlignment.LEFT,
                                    valign = VerticalAlignment.TOP
                                )
                            ),
                            listOf(
                                PageBlockTableCell(
                                    text = RichText.Plain("1"),
                                    isHeader = false,
                                    colspan = 1,
                                    rowspan = 1,
                                    align = HorizontalAlignment.LEFT,
                                    valign = VerticalAlignment.TOP
                                ),
                                PageBlockTableCell(
                                    text = RichText.Plain("2"),
                                    isHeader = false,
                                    colspan = 1,
                                    rowspan = 1,
                                    align = HorizontalAlignment.LEFT,
                                    valign = VerticalAlignment.TOP
                                )
                            )
                        ),
                        isBordered = true,
                        isStriped = false
                    )
                ),
                credit = RichText.Plain("Author")
            ),
            mapped
        )
    }
}
