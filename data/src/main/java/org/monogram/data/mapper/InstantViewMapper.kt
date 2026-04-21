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

private fun TdApi.WebPageInstantView.toInstantViewModel(url: String): InstantViewModel {
    return InstantViewModel(
        pageBlocks = pageBlocks.mapNotNull { it.toPageBlock() },
        viewCount = viewCount,
        version = version,
        isRtl = isRtl,
        isFull = isFull,
        url = url
    )
}

private fun TdApi.PageBlock.toPageBlock(): PageBlock? {
    return when (this) {
        is TdApi.PageBlockTitle -> PageBlock.Title(title.toRichText())
        is TdApi.PageBlockSubtitle -> PageBlock.Subtitle(subtitle.toRichText())
        is TdApi.PageBlockAuthorDate -> PageBlock.AuthorDate(author.toRichText(), publishDate)
        is TdApi.PageBlockHeader -> PageBlock.Header(header.toRichText())
        is TdApi.PageBlockSubheader -> PageBlock.Subheader(subheader.toRichText())
        is TdApi.PageBlockKicker -> PageBlock.Kicker(kicker.toRichText())
        is TdApi.PageBlockParagraph -> PageBlock.Paragraph(text.toRichText())
        is TdApi.PageBlockPreformatted -> PageBlock.Preformatted(text.toRichText(), language)
        is TdApi.PageBlockFooter -> PageBlock.Footer(footer.toRichText())
        is TdApi.PageBlockDivider -> PageBlock.Divider
        is TdApi.PageBlockAnchor -> PageBlock.Anchor(name)
        is TdApi.PageBlockList -> PageBlock.ListBlock(items.map { it.toPageBlockListItem() })
        is TdApi.PageBlockBlockQuote -> PageBlock.BlockQuote(text.toRichText(), credit.toRichText())
        is TdApi.PageBlockPullQuote -> PageBlock.PullQuote(text.toRichText(), credit.toRichText())
        is TdApi.PageBlockAnimation -> animation?.let { PageBlock.AnimationBlock(it.toAnimation(), caption.toCaption(), needAutoplay) }
        is TdApi.PageBlockAudio -> audio?.let { PageBlock.AudioBlock(it.toAudio(), caption.toCaption()) }
        is TdApi.PageBlockPhoto -> photo?.let { PageBlock.PhotoBlock(it.toPhoto(), caption.toCaption(), url) }
        is TdApi.PageBlockVideo -> video?.let { PageBlock.VideoBlock(it.toVideo(), caption.toCaption(), needAutoplay, isLooped) }
        is TdApi.PageBlockCover -> PageBlock.Cover(cover.toPageBlock() ?: PageBlock.Divider)
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
            pageBlocks = pageBlocks.mapNotNull { it.toPageBlock() },
            caption = caption.toCaption()
        )
        is TdApi.PageBlockCollage -> PageBlock.Collage(pageBlocks.mapNotNull { it.toPageBlock() }, caption.toCaption())
        is TdApi.PageBlockSlideshow -> PageBlock.Slideshow(pageBlocks.mapNotNull { it.toPageBlock() }, caption.toCaption())
        is TdApi.PageBlockChatLink -> PageBlock.ChatLink(title, username)
        is TdApi.PageBlockTable -> PageBlock.Table(
            caption = caption.toRichText(),
            cells = cells.map { row -> row.map { it.toTableCell() } },
            isBordered = isBordered,
            isStriped = isStriped
        )
        is TdApi.PageBlockDetails -> PageBlock.Details(header.toRichText(), pageBlocks.mapNotNull { it.toPageBlock() }, isOpen)
        is TdApi.PageBlockRelatedArticles -> PageBlock.RelatedArticles(header.toRichText(), articles.map { it.toRelatedArticle() })
        is TdApi.PageBlockMap -> PageBlock.MapBlock(
            location = Location(location.latitude, location.longitude),
            zoom = zoom,
            width = width,
            height = height,
            caption = caption.toCaption()
        )
        else -> null
    }
}

private fun TdApi.RichText.toRichText(): RichText {
    return when (this) {
        is TdApi.RichTextPlain -> RichText.Plain(text)
        is TdApi.RichTextBold -> RichText.Bold(text.toRichText())
        is TdApi.RichTextItalic -> RichText.Italic(text.toRichText())
        is TdApi.RichTextUnderline -> RichText.Underline(text.toRichText())
        is TdApi.RichTextStrikethrough -> RichText.Strikethrough(text.toRichText())
        is TdApi.RichTextFixed -> RichText.Fixed(text.toRichText())
        is TdApi.RichTextUrl -> RichText.Url(text.toRichText(), url, isCached)
        is TdApi.RichTextEmailAddress -> RichText.EmailAddress(text.toRichText(), emailAddress)
        is TdApi.RichTextSubscript -> RichText.Subscript(text.toRichText())
        is TdApi.RichTextSuperscript -> RichText.Superscript(text.toRichText())
        is TdApi.RichTextMarked -> RichText.Marked(text.toRichText())
        is TdApi.RichTextPhoneNumber -> RichText.PhoneNumber(text.toRichText(), phoneNumber)
        is TdApi.RichTextIcon -> document?.let { RichText.Icon(it.toDocument(), width, height) } ?: RichText.Plain("")
        is TdApi.RichTextReference -> RichText.Reference(text.toRichText(), anchorName, url)
        is TdApi.RichTextAnchor -> RichText.Anchor(name)
        is TdApi.RichTextAnchorLink -> RichText.AnchorLink(text.toRichText(), anchorName, url)
        is TdApi.RichTexts -> RichText.Texts(texts.map { it.toRichText() })
        else -> RichText.Plain("")
    }
}

private fun TdApi.PageBlockListItem.toPageBlockListItem() = PageBlockListItem(label, pageBlocks.mapNotNull { it.toPageBlock() })

private fun TdApi.PageBlockCaption.toCaption() = PageBlockCaption(text.toRichText(), credit.toRichText())

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
    val size = sizes.lastOrNull()
    return WebPage.Photo(
        path = size?.photo?.local?.path?.takeIf { isValidFilePath(it) },
        width = size?.width ?: 0,
        height = size?.height ?: 0,
        fileId = size?.photo?.id ?: 0,
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
