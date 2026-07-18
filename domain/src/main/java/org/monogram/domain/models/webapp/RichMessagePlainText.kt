package org.monogram.domain.models.webapp

fun List<PageBlock>.toEditorPlainText(): String {
    return joinToString("\n\n") { it.toEditorPlainText() }.trim()
}

private fun PageBlock.toEditorPlainText(): String {
    return when (this) {
        is PageBlock.Title -> title.toEditorPlainText()
        is PageBlock.Subtitle -> subtitle.toEditorPlainText()
        is PageBlock.AuthorDate -> buildString {
            append(author.toEditorPlainText())
            if (publishDate > 0) {
                if (isNotBlank()) append(" • ")
                append(publishDate)
            }
        }

        is PageBlock.Header -> header.toEditorPlainText()
        is PageBlock.Subheader -> subheader.toEditorPlainText()
        is PageBlock.SectionHeading -> text.toEditorPlainText()
        is PageBlock.Kicker -> kicker.toEditorPlainText()
        is PageBlock.Paragraph -> text.toEditorPlainText()
        is PageBlock.Preformatted -> text.toEditorPlainText()
        is PageBlock.Footer -> footer.toEditorPlainText()
        is PageBlock.Thinking -> text.toEditorPlainText()
        is PageBlock.Divider -> "---"
        is PageBlock.MathematicalExpression -> expression
        is PageBlock.Anchor -> ""
        is PageBlock.ListBlock -> items.joinToString("\n") { item ->
            val text = item.pageBlocks.joinToString(" ") { it.toEditorPlainText() }.trim()
            if (text.isBlank()) item.label else listOf(item.label, text).filter { it.isNotBlank() }
                .joinToString(" ")
        }

        is PageBlock.BlockQuote -> listOf(
            pageBlocks.toEditorPlainText(),
            credit.toEditorPlainText()
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")

        is PageBlock.PullQuote -> listOf(text.toEditorPlainText(), credit.toEditorPlainText())
            .filter { it.isNotBlank() }
            .joinToString("\n")

        is PageBlock.AnimationBlock -> caption.toEditorPlainText()
        is PageBlock.AudioBlock -> caption.toEditorPlainText()
        is PageBlock.VoiceNoteBlock -> caption.toEditorPlainText()
        is PageBlock.PhotoBlock -> caption.toEditorPlainText()
        is PageBlock.VideoBlock -> caption.toEditorPlainText()
        is PageBlock.Cover -> cover.toEditorPlainText()
        is PageBlock.Embedded -> listOf(caption.toEditorPlainText(), url)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        is PageBlock.EmbeddedPost -> listOf(
            author,
            pageBlocks.toEditorPlainText(),
            caption.toEditorPlainText()
        )
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.Collage -> listOf(pageBlocks.toEditorPlainText(), caption.toEditorPlainText())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.Slideshow -> listOf(
            pageBlocks.toEditorPlainText(),
            caption.toEditorPlainText()
        )
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.ChatLink -> listOf(title, "@$username")
            .filter { it.isNotBlank() }
            .joinToString(" ")

        is PageBlock.Table -> buildString {
            val captionText = caption.toEditorPlainText()
            if (captionText.isNotBlank()) appendLine(captionText)
            cells.forEach { row ->
                appendLine(row.joinToString(" | ") { it.text.toEditorPlainText() })
            }
        }.trim()

        is PageBlock.Details -> listOf(header.toEditorPlainText(), pageBlocks.toEditorPlainText())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        is PageBlock.RelatedArticles -> buildString {
            val headerText = header.toEditorPlainText()
            if (headerText.isNotBlank()) appendLine(headerText)
            articles.forEach { article ->
                appendLine(listOf(article.title, article.description).filter { it.isNotBlank() }
                    .joinToString(" - "))
            }
        }.trim()

        is PageBlock.MapBlock -> caption.toEditorPlainText()
        is PageBlock.Unsupported -> ""
    }
}

private fun PageBlockCaption.toEditorPlainText(): String {
    return listOf(text.toEditorPlainText(), credit.toEditorPlainText())
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun RichText.toEditorPlainText(): String {
    return when (this) {
        is RichText.Plain -> text
        is RichText.Bold -> text.toEditorPlainText()
        is RichText.Italic -> text.toEditorPlainText()
        is RichText.Underline -> text.toEditorPlainText()
        is RichText.Strikethrough -> text.toEditorPlainText()
        is RichText.Spoiler -> text.toEditorPlainText()
        is RichText.DateTime -> text.toEditorPlainText()
        is RichText.Mention -> text.toEditorPlainText()
        is RichText.Hashtag -> text.toEditorPlainText()
        is RichText.Cashtag -> text.toEditorPlainText()
        is RichText.BotCommand -> text.toEditorPlainText()
        is RichText.Fixed -> text.toEditorPlainText()
        is RichText.MentionName -> text.toEditorPlainText()
        is RichText.Url -> text.toEditorPlainText()
        is RichText.EmailAddress -> text.toEditorPlainText()
        is RichText.BankCardNumber -> text.toEditorPlainText()
        is RichText.Subscript -> text.toEditorPlainText()
        is RichText.Superscript -> text.toEditorPlainText()
        is RichText.Marked -> text.toEditorPlainText()
        is RichText.PhoneNumber -> text.toEditorPlainText()
        is RichText.CustomEmoji -> alternativeText
        is RichText.Icon -> ""
        is RichText.MathematicalExpression -> expression
        is RichText.Reference -> text.toEditorPlainText()
        is RichText.ReferenceLink -> text.toEditorPlainText()
        is RichText.Diff -> text.toEditorPlainText()
        is RichText.Anchor -> ""
        is RichText.AnchorLink -> text.toEditorPlainText()
        is RichText.Texts -> texts.joinToString("") { it.toEditorPlainText() }
    }
}
