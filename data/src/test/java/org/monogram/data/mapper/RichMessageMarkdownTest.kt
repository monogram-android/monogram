package org.monogram.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.models.webapp.HorizontalAlignment
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.PageBlockListItem
import org.monogram.domain.models.webapp.PageBlockTableCell
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.models.webapp.VerticalAlignment
import org.monogram.domain.models.webapp.toEditorMarkdown

class RichMessageMarkdownTest {

    @Test
    fun `converts instant view blocks to editor markdown`() {
        val markdown = listOf(
            PageBlock.SectionHeading(RichText.Plain("Lorem Ipsum: The Standard Dummy Text"), 1),
            PageBlock.SectionHeading(RichText.Plain("Introduction"), 2),
            PageBlock.Paragraph(
                RichText.Plain(
                    "Lorem Ipsum has been the industry's standard dummy text ever since the 1500s."
                )
            ),
            PageBlock.BlockQuote(
                pageBlocks = listOf(
                    PageBlock.Paragraph(
                        RichText.Plain(
                            "Contrary to popular belief, Lorem Ipsum is not simply random text."
                        )
                    )
                ),
                RichText.Plain("")
            ),
            PageBlock.SectionHeading(RichText.Plain("Key Characteristics"), 3),
            PageBlock.ListBlock(
                listOf(
                    PageBlockListItem(
                        label = "•",
                        pageBlocks = listOf(
                            PageBlock.Paragraph(
                                RichText.Texts(
                                    listOf(
                                        RichText.Bold(RichText.Plain("Standardization")),
                                        RichText.Plain(": It is the go-to placeholder.")
                                    )
                                )
                            )
                        )
                    ),
                    PageBlockListItem(
                        label = "•",
                        pageBlocks = listOf(
                            PageBlock.Paragraph(
                                RichText.Texts(
                                    listOf(
                                        RichText.Bold(RichText.Plain("Legibility")),
                                        RichText.Plain(
                                            ": Unlike \"Here is some text here is some text,\" it has a more-or-less normal distribution of letters."
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            PageBlock.Table(
                caption = RichText.Plain(""),
                cells = listOf(
                    listOf(
                        PageBlockTableCell(
                            text = RichText.Plain("Column A"),
                            isHeader = true,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        ),
                        PageBlockTableCell(
                            text = RichText.Plain("Column B"),
                            isHeader = true,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        )
                    ),
                    listOf(
                        PageBlockTableCell(
                            text = RichText.Plain("Alpha"),
                            isHeader = false,
                            colspan = 1,
                            rowspan = 1,
                            align = HorizontalAlignment.LEFT,
                            valign = VerticalAlignment.TOP
                        ),
                        PageBlockTableCell(
                            text = RichText.Plain("Beta"),
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
        ).toEditorMarkdown()

        assertEquals(
            """
            # Lorem Ipsum: The Standard Dummy Text

            ## Introduction

            Lorem Ipsum has been the industry's standard dummy text ever since the 1500s.

            > Contrary to popular belief, Lorem Ipsum is not simply random text.

            ### Key Characteristics

            - **Standardization**: It is the go-to placeholder.
            - **Legibility**: Unlike "Here is some text here is some text," it has a more-or-less normal distribution of letters.

            | Column A | Column B |
            | --- | --- |
            | Alpha | Beta |
            """.trimIndent(),
            markdown
        )
    }
}
