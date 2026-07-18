package org.monogram.domain.models.webapp

fun List<PageBlock>.toEditorMarkdown(): String {
    return joinToString("\n\n") { it.toEditorMarkdown() }.trim()
}

private fun PageBlock.toEditorMarkdown(): String {
    return when (this) {
        is PageBlock.Title -> "# ${title.toEditorMarkdown()}"
        is PageBlock.Subtitle -> "## ${subtitle.toEditorMarkdown()}"
        is PageBlock.AuthorDate -> buildString {
            append(author.toEditorMarkdown())
            if (publishDate > 0) {
                if (isNotBlank()) append(" • ")
                append(publishDate)
            }
        }

        is PageBlock.Header -> "## ${header.toEditorMarkdown()}"
        is PageBlock.Subheader -> "### ${subheader.toEditorMarkdown()}"
        is PageBlock.SectionHeading -> "${
            "#".repeat(
                size.coerceIn(
                    1,
                    3
                )
            )
        } ${text.toEditorMarkdown()}"

        is PageBlock.Kicker -> kicker.toEditorMarkdown()
        is PageBlock.Paragraph -> text.toEditorMarkdown()
        is PageBlock.Preformatted -> buildString {
            append("```")
            append(language.trim())
            appendLine()
            append(text.toEditorMarkdownPlainText())
            appendLine()
            append("```")
        }

        is PageBlock.Footer -> footer.toEditorMarkdown()
        is PageBlock.Thinking -> text.toEditorMarkdown()
        is PageBlock.Divider -> "---"
        is PageBlock.MathematicalExpression -> buildString {
            append("$$")
            append(expression)
            append("$$")
        }

        is PageBlock.Anchor -> ""
        is PageBlock.ListBlock -> items.mapIndexed { index, item ->
            val itemText = item.pageBlocks.joinToString(" ") { it.toEditorMarkdown() }
                .replace("\n", " ")
                .trim()
            val marker = if (item.label.any { it.isDigit() }) "${index + 1}." else "-"
            if (itemText.isBlank()) marker else "$marker $itemText"
        }.joinToString("\n")

        is PageBlock.BlockQuote -> quoteToMarkdown(pageBlocks, credit)
        is PageBlock.PullQuote -> quoteToMarkdown(text, credit)
        is PageBlock.AnimationBlock -> caption.toEditorMarkdown()
        is PageBlock.AudioBlock -> caption.toEditorMarkdown()
        is PageBlock.VoiceNoteBlock -> caption.toEditorMarkdown()
        is PageBlock.PhotoBlock -> caption.toEditorMarkdown()
        is PageBlock.VideoBlock -> caption.toEditorMarkdown()
        is PageBlock.Cover -> cover.toEditorMarkdown()
        is PageBlock.Embedded -> listOf(caption.toEditorMarkdown(), url)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.EmbeddedPost -> listOf(
            author,
            pageBlocks.toEditorMarkdown(),
            caption.toEditorMarkdown()
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        is PageBlock.Collage -> listOf(pageBlocks.toEditorMarkdown(), caption.toEditorMarkdown())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.Slideshow -> listOf(pageBlocks.toEditorMarkdown(), caption.toEditorMarkdown())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.ChatLink -> if (username.isNotBlank()) {
            formatMarkdownLink(title, "https://t.me/$username")
        } else {
            title
        }

        is PageBlock.Table -> buildString {
            val captionText = caption.toEditorMarkdown()
            if (captionText.isNotBlank()) appendLine(captionText)
            append(cells.toEditorMarkdownTable())
        }.trim()

        is PageBlock.Details -> listOf(header.toEditorMarkdown(), pageBlocks.toEditorMarkdown())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.RelatedArticles -> buildString {
            val headerText = header.toEditorMarkdown()
            if (headerText.isNotBlank()) appendLine(headerText)
            articles.forEach { article ->
                appendLine(
                    listOf(article.title, article.description)
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                )
            }
        }.trim()

        is PageBlock.MapBlock -> caption.toEditorMarkdown()
        is PageBlock.Unsupported -> ""
    }
}

private fun PageBlockCaption.toEditorMarkdown(): String {
    return listOf(text.toEditorMarkdown(), credit.toEditorMarkdown())
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun List<List<PageBlockTableCell>>.toEditorMarkdownTable(): String {
    if (isEmpty()) return ""

    val rows = map { row ->
        row.map { cell ->
            cell.text.toEditorMarkdownPlainText()
                .replace("\n", " ")
                .replace("|", "\\|")
                .trim()
        }
    }
    val columnCount = rows.maxOf { it.size }
    val normalizedRows = rows.map { row -> row + List(columnCount - row.size) { "" } }

    fun renderRow(cells: List<String>): String {
        return cells.joinToString(" | ", prefix = "| ", postfix = " |")
    }

    return buildString {
        appendLine(renderRow(normalizedRows.first()))
        appendLine(renderRow(List(columnCount) { "---" }))
        normalizedRows.drop(1).forEachIndexed { index, row ->
            append(renderRow(row))
            if (index != normalizedRows.drop(1).lastIndex) appendLine()
        }
    }
}

private fun quoteToMarkdown(pageBlocks: List<PageBlock>, credit: RichText): String {
    val quoteText = pageBlocks.toEditorMarkdown()
    val quote =
        if (quoteText.isBlank()) "" else quoteText.lineSequence().joinToString("\n") { "> $it" }
    val quoteCredit = credit.toEditorMarkdown().takeIf { it.isNotBlank() }
        ?.let { "\n> — $it" }
        .orEmpty()
    return (quote + quoteCredit).trim()
}

private fun quoteToMarkdown(text: RichText, credit: RichText): String {
    val quoteText = text.toEditorMarkdown()
    val quote =
        if (quoteText.isBlank()) "" else quoteText.lineSequence().joinToString("\n") { "> $it" }
    val quoteCredit = credit.toEditorMarkdown().takeIf { it.isNotBlank() }
        ?.let { "\n> — $it" }
        .orEmpty()
    return (quote + quoteCredit).trim()
}

private fun RichText.toEditorMarkdown(): String {
    return when (this) {
        is RichText.Plain -> escapeMarkdownText(text)
        is RichText.Bold -> "**${text.toEditorMarkdown()}**"
        is RichText.Italic -> "*${text.toEditorMarkdown()}*"
        is RichText.Underline -> "__${text.toEditorMarkdown()}__"
        is RichText.Strikethrough -> "~~${text.toEditorMarkdown()}~~"
        is RichText.Spoiler -> "||${text.toEditorMarkdown()}||"
        is RichText.DateTime -> text.toEditorMarkdown()
        is RichText.Mention -> text.toEditorMarkdown()
        is RichText.Hashtag -> text.toEditorMarkdown()
        is RichText.Cashtag -> text.toEditorMarkdown()
        is RichText.BotCommand -> text.toEditorMarkdown()
        is RichText.Fixed -> "`" + text.toEditorMarkdownPlainText().replace("`", "\\`") + "`"
        is RichText.MentionName -> text.toEditorMarkdown()
        is RichText.Url -> if (text.toEditorMarkdown().isBlank()) {
            formatMarkdownLink(url, url)
        } else {
            formatMarkdownLink(text.toEditorMarkdown(), url)
        }

        is RichText.EmailAddress -> formatMarkdownLink(
            text.toEditorMarkdown(),
            "mailto:$emailAddress"
        )

        is RichText.BankCardNumber -> text.toEditorMarkdown()
        is RichText.Subscript -> text.toEditorMarkdown()
        is RichText.Superscript -> text.toEditorMarkdown()
        is RichText.Marked -> text.toEditorMarkdown()
        is RichText.PhoneNumber -> text.toEditorMarkdown()
        is RichText.CustomEmoji -> escapeMarkdownText(alternativeText)
        is RichText.Icon -> ""
        is RichText.MathematicalExpression -> buildString {
            append('$')
            append(expression)
            append('$')
        }

        is RichText.Reference -> text.toEditorMarkdown()
        is RichText.ReferenceLink -> if (url.isBlank()) {
            text.toEditorMarkdown()
        } else {
            formatMarkdownLink(text.toEditorMarkdown(), url)
        }

        is RichText.Diff -> text.toEditorMarkdown()
        is RichText.Anchor -> ""
        is RichText.AnchorLink -> if (url.isBlank()) {
            text.toEditorMarkdown()
        } else {
            formatMarkdownLink(text.toEditorMarkdown(), url)
        }

        is RichText.Texts -> texts.joinToString("") { it.toEditorMarkdown() }
    }
}

private fun RichText.toEditorMarkdownPlainText(): String {
    return when (this) {
        is RichText.Plain -> text
        is RichText.Bold -> text.toEditorMarkdownPlainText()
        is RichText.Italic -> text.toEditorMarkdownPlainText()
        is RichText.Underline -> text.toEditorMarkdownPlainText()
        is RichText.Strikethrough -> text.toEditorMarkdownPlainText()
        is RichText.Spoiler -> text.toEditorMarkdownPlainText()
        is RichText.DateTime -> text.toEditorMarkdownPlainText()
        is RichText.Mention -> text.toEditorMarkdownPlainText()
        is RichText.Hashtag -> text.toEditorMarkdownPlainText()
        is RichText.Cashtag -> text.toEditorMarkdownPlainText()
        is RichText.BotCommand -> text.toEditorMarkdownPlainText()
        is RichText.Fixed -> text.toEditorMarkdownPlainText()
        is RichText.MentionName -> text.toEditorMarkdownPlainText()
        is RichText.Url -> text.toEditorMarkdownPlainText()
        is RichText.EmailAddress -> text.toEditorMarkdownPlainText()
        is RichText.BankCardNumber -> text.toEditorMarkdownPlainText()
        is RichText.Subscript -> text.toEditorMarkdownPlainText()
        is RichText.Superscript -> text.toEditorMarkdownPlainText()
        is RichText.Marked -> text.toEditorMarkdownPlainText()
        is RichText.PhoneNumber -> text.toEditorMarkdownPlainText()
        is RichText.CustomEmoji -> alternativeText
        is RichText.Icon -> ""
        is RichText.MathematicalExpression -> expression
        is RichText.Reference -> text.toEditorMarkdownPlainText()
        is RichText.ReferenceLink -> text.toEditorMarkdownPlainText()
        is RichText.Diff -> text.toEditorMarkdownPlainText()
        is RichText.Anchor -> ""
        is RichText.AnchorLink -> text.toEditorMarkdownPlainText()
        is RichText.Texts -> texts.joinToString("") { it.toEditorMarkdownPlainText() }
    }
}

private fun escapeMarkdownText(value: String): String {
    if (value.isBlank()) return value
    return buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '!', '|', '>' -> {
                    append('\\')
                    append(char)
                }

                else -> append(char)
            }
        }
    }
}

private fun formatMarkdownLink(label: String, url: String): String {
    return if (label.isBlank()) {
        url
    } else {
        "[${label}]($url)"
    }
}
