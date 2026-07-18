package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.WebPage
import org.monogram.domain.models.webapp.HorizontalAlignment
import org.monogram.domain.models.webapp.InstantViewModel
import org.monogram.domain.models.webapp.Location
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.PageBlockCaption
import org.monogram.domain.models.webapp.PageBlockListItem
import org.monogram.domain.models.webapp.PageBlockRelatedArticle
import org.monogram.domain.models.webapp.PageBlockTableCell
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.models.webapp.VerticalAlignment

fun map(iv: TdApi.WebPageInstantView, url: String): InstantViewModel {
    return iv.toInstantViewModel(url)
}

fun TdApi.RichMessage.toDomainRichMessage(
    chatId: Long,
    messageId: Long,
    markdownSource: String? = null
) = org.monogram.domain.models.MessageContent.RichMessage(
    blocks = blocks.orEmpty().map { it.toPageBlock() },
    isRtl = isRtl,
    isFull = isFull,
    chatId = chatId,
    messageId = messageId,
    markdownSource = markdownSource
)

private fun TdApi.WebPageInstantView.toInstantViewModel(url: String): InstantViewModel {
    return InstantViewModel(
        pageBlocks = blocks.orEmpty().map { it.toPageBlock() },
        viewCount = viewCount,
        version = version,
        isRtl = isRtl,
        isFull = isFull,
        url = url
    )
}

fun TdApi.PageBlock?.toPageBlock(): PageBlock {
    this ?: return PageBlock.Unsupported("null")
    return when (this) {
        is TdApi.PageBlockTitle -> PageBlock.Title(title.toRichText())
        is TdApi.PageBlockSubtitle -> PageBlock.Subtitle(subtitle.toRichText())
        is TdApi.PageBlockAuthorDate -> PageBlock.AuthorDate(author.toRichText(), publishDate)
        is TdApi.PageBlockHeader -> PageBlock.Header(header.toRichText())
        is TdApi.PageBlockSubheader -> PageBlock.Subheader(subheader.toRichText())
        is TdApi.PageBlockSectionHeading -> PageBlock.SectionHeading(text.toRichText(), size)
        is TdApi.PageBlockKicker -> PageBlock.Kicker(kicker.toRichText())
        is TdApi.PageBlockParagraph -> PageBlock.Paragraph(text.toRichText())
        is TdApi.PageBlockPreformatted -> PageBlock.Preformatted(text.toRichText(), language)
        is TdApi.PageBlockFooter -> PageBlock.Footer(footer.toRichText())
        is TdApi.PageBlockThinking -> PageBlock.Thinking(text.toRichText())
        is TdApi.PageBlockDivider -> PageBlock.Divider
        is TdApi.PageBlockMathematicalExpression -> PageBlock.MathematicalExpression(expression)
        is TdApi.PageBlockAnchor -> PageBlock.Anchor(name)
        is TdApi.PageBlockList -> PageBlock.ListBlock(
            items.orEmpty().map { it.toPageBlockListItem() })
        is TdApi.PageBlockBlockQuote -> PageBlock.BlockQuote(
            blocks.orEmpty().firstOrNull().toPageBlock().let { block ->
                when (block) {
                    is PageBlock.Paragraph -> block.text
                    is PageBlock.Preformatted -> block.text
                    else -> RichText.Plain("")
                }
            },
            credit.toRichText()
        )
        is TdApi.PageBlockPullQuote -> PageBlock.PullQuote(text.toRichText(), credit.toRichText())
        is TdApi.PageBlockAnimation -> animation?.let { PageBlock.AnimationBlock(it.toAnimation(), caption.toCaption(), needAutoplay) }
            ?: PageBlock.Unsupported(this::class.simpleName.orEmpty())
        is TdApi.PageBlockAudio -> audio?.let { PageBlock.AudioBlock(it.toAudio(), caption.toCaption()) }
            ?: PageBlock.Unsupported(this::class.simpleName.orEmpty())
        is TdApi.PageBlockVoiceNote -> voiceNote?.let {
            PageBlock.VoiceNoteBlock(
                it.toVoiceNote(),
                caption.toCaption()
            )
        }
            ?: PageBlock.Unsupported(this::class.simpleName.orEmpty())
        is TdApi.PageBlockPhoto -> photo?.let { PageBlock.PhotoBlock(it.toPhoto(), caption.toCaption(), url) }
            ?: PageBlock.Unsupported(this::class.simpleName.orEmpty())
        is TdApi.PageBlockVideo -> video?.let { PageBlock.VideoBlock(it.toVideo(), caption.toCaption(), needAutoplay, isLooped) }
            ?: PageBlock.Unsupported(this::class.simpleName.orEmpty())

        is TdApi.PageBlockCover -> PageBlock.Cover(cover.toPageBlock())
        is TdApi.PageBlockEmbedded -> PageBlock.Embedded(
            url = url,
            html = html,
            posterPhoto = posterPhoto?.toPhoto(),
            width = width,
            height = height,
            caption = caption.toCaption(),
            isFullWidth = isFullWidth,
            allowScrolling = allowScrolling
        )
        is TdApi.PageBlockEmbeddedPost -> PageBlock.EmbeddedPost(
            url = url,
            author = author,
            authorPhoto = authorPhoto?.toPhoto(),
            date = date,
            pageBlocks = blocks.orEmpty().map { it.toPageBlock() },
            caption = caption.toCaption()
        )

        is TdApi.PageBlockCollage -> PageBlock.Collage(
            blocks.orEmpty().map { it.toPageBlock() },
            caption.toCaption()
        )

        is TdApi.PageBlockSlideshow -> PageBlock.Slideshow(
            blocks.orEmpty().map { it.toPageBlock() },
            caption.toCaption()
        )
        is TdApi.PageBlockChatLink -> PageBlock.ChatLink(title, username)
        is TdApi.PageBlockTable -> PageBlock.Table(
            caption = caption.toRichText(),
            cells = cells.orEmpty().map { row -> row.orEmpty().map { it.toTableCell() } },
            isBordered = isBordered,
            isStriped = isStriped
        )
        is TdApi.PageBlockDetails -> PageBlock.Details(
            header.toRichText(),
            blocks.orEmpty().map { it.toPageBlock() },
            isOpen
        )

        is TdApi.PageBlockRelatedArticles -> PageBlock.RelatedArticles(
            header.toRichText(),
            articles.orEmpty().map { it.toRelatedArticle() })
        is TdApi.PageBlockMap -> PageBlock.MapBlock(
            location = Location(location.latitude, location.longitude),
            zoom = zoom,
            width = width,
            height = height,
            caption = caption.toCaption()
        )
        else -> PageBlock.Unsupported(this::class.simpleName.orEmpty())
    }
}

private fun TdApi.PageBlockListItem.toPageBlockListItem() =
    PageBlockListItem(label, blocks.orEmpty().map { it.toPageBlock() })

private fun TdApi.PageBlockCaption?.toCaption() = PageBlockCaption(
    text = this?.text.toRichText(),
    credit = this?.credit.toRichText()
)

fun TdApi.RichText?.toRichText(): RichText = when (this) {
    null -> RichText.Plain("")
    else -> when (this) {
        is TdApi.RichTextPlain -> RichText.Plain(text)
        is TdApi.RichTextBold -> RichText.Bold(text.toRichText())
        is TdApi.RichTextItalic -> RichText.Italic(text.toRichText())
        is TdApi.RichTextUnderline -> RichText.Underline(text.toRichText())
        is TdApi.RichTextStrikethrough -> RichText.Strikethrough(text.toRichText())
        is TdApi.RichTextSpoiler -> RichText.Spoiler(text.toRichText())
        is TdApi.RichTextDateTime -> RichText.DateTime(text.toRichText(), unixTime)
        is TdApi.RichTextMention -> RichText.Mention(text.toRichText(), username)
        is TdApi.RichTextHashtag -> RichText.Hashtag(text.toRichText(), hashtag)
        is TdApi.RichTextCashtag -> RichText.Cashtag(text.toRichText(), cashtag)
        is TdApi.RichTextBotCommand -> RichText.BotCommand(text.toRichText(), botCommand)
        is TdApi.RichTextFixed -> RichText.Fixed(text.toRichText())
        is TdApi.RichTextMentionName -> RichText.MentionName(text.toRichText(), userId)
        is TdApi.RichTextUrl -> RichText.Url(text.toRichText(), url, isCached)
        is TdApi.RichTextEmailAddress -> RichText.EmailAddress(text.toRichText(), emailAddress)
        is TdApi.RichTextBankCardNumber -> RichText.BankCardNumber(
            text.toRichText(),
            bankCardNumber
        )
        is TdApi.RichTextSubscript -> RichText.Subscript(text.toRichText())
        is TdApi.RichTextSuperscript -> RichText.Superscript(text.toRichText())
        is TdApi.RichTextMarked -> RichText.Marked(text.toRichText())
        is TdApi.RichTextPhoneNumber -> RichText.PhoneNumber(text.toRichText(), phoneNumber)
        is TdApi.RichTextCustomEmoji -> RichText.CustomEmoji(customEmojiId, alternativeText)
        is TdApi.RichTextIcon -> document?.let { RichText.Icon(it.toDocument(), width, height) }
            ?: RichText.Plain("")

        is TdApi.RichTextMathematicalExpression -> RichText.MathematicalExpression(expression)
        is TdApi.RichTextReference -> RichText.Reference(text.toRichText(), name, "")
        is TdApi.RichTextReferenceLink -> RichText.ReferenceLink(
            text = text.toRichText(),
            referenceName = referenceName,
            url = url
        )

        is TdApi.RichTextDiff -> RichText.Diff(text.toRichText(), oldText.toRichText())
        is TdApi.RichTextAnchor -> RichText.Anchor(name)
        is TdApi.RichTextAnchorLink -> RichText.AnchorLink(text.toRichText(), anchorName, url)
        is TdApi.RichTexts -> RichText.Texts(texts.orEmpty().map { it.toRichText() })
        else -> RichText.Plain("")
    }
}

private fun TdApi.PageBlockTableCell.toTableCell() = PageBlockTableCell(
    text = text?.toRichText() ?: RichText.Plain(""),
    isHeader = isHeader,
    colspan = colspan,
    rowspan = rowspan,
    align = align.toHorizontalAlignment(),
    valign = valign.toVerticalAlignment()
)

private fun TdApi.PageBlockHorizontalAlignment.toHorizontalAlignment(): HorizontalAlignment {
    return when (this) {
        is TdApi.PageBlockHorizontalAlignmentCenter -> HorizontalAlignment.CENTER
        is TdApi.PageBlockHorizontalAlignmentRight -> HorizontalAlignment.RIGHT
        else -> HorizontalAlignment.LEFT
    }
}

private fun TdApi.PageBlockVerticalAlignment.toVerticalAlignment(): VerticalAlignment {
    return when (this) {
        is TdApi.PageBlockVerticalAlignmentMiddle -> VerticalAlignment.MIDDLE
        is TdApi.PageBlockVerticalAlignmentBottom -> VerticalAlignment.BOTTOM
        else -> VerticalAlignment.TOP
    }
}

private fun TdApi.PageBlockRelatedArticle.toRelatedArticle() = PageBlockRelatedArticle(
    url = url,
    title = title,
    description = description,
    photo = photo?.toPhoto(),
    author = author,
    publishDate = publishDate
)

private fun TdApi.Photo.toPhoto(): WebPage.Photo {
    val selection = WebPageMapper.selectPhotoSizes(sizes)
    val size = selection.preferredSize
    val thumbnailSize = selection.thumbnailSize
    val originalSize = selection.originalSize
    return WebPage.Photo(
        path = size?.photo?.local?.path?.takeIf { isValidFilePath(it) },
        thumbnailPath = thumbnailSize?.photo?.local?.path?.takeIf { isValidFilePath(it) },
        width = size?.width ?: 0,
        height = size?.height ?: 0,
        fileId = size?.photo?.id ?: 0,
        thumbnailFileId = thumbnailSize?.photo?.id ?: 0,
        originalFileId = originalSize?.photo?.id?.takeIf { it != size?.photo?.id } ?: 0,
        minithumbnail = minithumbnail?.data
    )
}

private fun TdApi.Animation.toAnimation() = WebPage.Animation(
    path = animation.local.path.takeIf { isValidFilePath(it) },
    width = width,
    height = height,
    duration = duration,
    fileId = animation.id,
    thumbnailPath = thumbnail?.file?.local?.path?.takeIf { isValidFilePath(it) },
    thumbnailFileId = thumbnail?.file?.id ?: 0,
    minithumbnail = minithumbnail?.data
)

private fun TdApi.Audio.toAudio() = WebPage.Audio(
    path = audio.local.path.takeIf { isValidFilePath(it) },
    duration = duration,
    title = title,
    performer = performer,
    fileId = audio.id
)

private fun TdApi.VoiceNote.toVoiceNote() = WebPage.VoiceNote(
    path = voice.local.path.takeIf { isValidFilePath(it) },
    duration = duration,
    mimeType = mimeType,
    fileId = voice.id
)

private fun TdApi.Video.toVideo() = WebPage.Video(
    path = video.local.path.takeIf { isValidFilePath(it) },
    width = width,
    height = height,
    duration = duration,
    fileId = video.id,
    thumbnailPath = thumbnail?.file?.local?.path?.takeIf { isValidFilePath(it) },
    thumbnailFileId = thumbnail?.file?.id ?: 0,
    minithumbnail = minithumbnail?.data,
    supportsStreaming = supportsStreaming
)

private fun TdApi.Document.toDocument() = WebPage.Document(
    path = document.local.path.takeIf { isValidFilePath(it) },
    fileName = fileName,
    mimeType = mimeType,
    size = document.size,
    fileId = document.id
)
