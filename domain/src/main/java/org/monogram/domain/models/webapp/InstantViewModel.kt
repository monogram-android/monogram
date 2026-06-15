package org.monogram.domain.models.webapp

import org.monogram.domain.models.WebPage

data class InstantViewModel(
    val pageBlocks: List<PageBlock>,
    val viewCount: Int,
    val version: Int,
    val isRtl: Boolean,
    val isFull: Boolean,
    val url: String
)

sealed interface PageBlock {
    data class Title(val title: RichText) : PageBlock
    data class Subtitle(val subtitle: RichText) : PageBlock
    data class AuthorDate(val author: RichText, val publishDate: Int) : PageBlock
    data class Header(val header: RichText) : PageBlock
    data class Subheader(val subheader: RichText) : PageBlock
    data class SectionHeading(val text: RichText, val size: Int) : PageBlock
    data class Kicker(val kicker: RichText) : PageBlock
    data class Paragraph(val text: RichText) : PageBlock
    data class Preformatted(val text: RichText, val language: String) : PageBlock
    data class Footer(val footer: RichText) : PageBlock
    data class Thinking(val text: RichText) : PageBlock
    data object Divider : PageBlock
    data class MathematicalExpression(val expression: String) : PageBlock
    data class Anchor(val name: String) : PageBlock
    data class ListBlock(val items: List<PageBlockListItem>) : PageBlock
    data class BlockQuote(val text: RichText, val credit: RichText) : PageBlock
    data class PullQuote(val text: RichText, val credit: RichText) : PageBlock
    data class AnimationBlock(
        val animation: WebPage.Animation,
        val caption: PageBlockCaption,
        val needAutoplay: Boolean
    ) : PageBlock

    data class AudioBlock(val audio: WebPage.Audio, val caption: PageBlockCaption) : PageBlock
    data class PhotoBlock(val photo: WebPage.Photo, val caption: PageBlockCaption, val url: String) : PageBlock
    data class VideoBlock(
        val video: WebPage.Video,
        val caption: PageBlockCaption,
        val needAutoplay: Boolean,
        val isLooped: Boolean
    ) : PageBlock

    data class Cover(val cover: PageBlock) : PageBlock
    data class Embedded(
        val url: String,
        val html: String,
        val posterPhoto: WebPage.Photo?,
        val width: Int,
        val height: Int,
        val caption: PageBlockCaption,
        val isFullWidth: Boolean,
        val allowScrolling: Boolean
    ) : PageBlock

    data class EmbeddedPost(
        val url: String,
        val author: String,
        val authorPhoto: WebPage.Photo?,
        val date: Int,
        val pageBlocks: List<PageBlock>,
        val caption: PageBlockCaption
    ) : PageBlock

    data class Collage(val pageBlocks: List<PageBlock>, val caption: PageBlockCaption) : PageBlock
    data class Slideshow(val pageBlocks: List<PageBlock>, val caption: PageBlockCaption) : PageBlock
    data class ChatLink(val title: String, val username: String) : PageBlock
    data class Table(
        val caption: RichText,
        val cells: List<List<PageBlockTableCell>>,
        val isBordered: Boolean,
        val isStriped: Boolean
    ) : PageBlock

    data class Details(val header: RichText, val pageBlocks: List<PageBlock>, val isOpen: Boolean) : PageBlock
    data class RelatedArticles(val header: RichText, val articles: List<PageBlockRelatedArticle>) : PageBlock
    data class MapBlock(
        val location: Location,
        val zoom: Int,
        val width: Int,
        val height: Int,
        val caption: PageBlockCaption
    ) : PageBlock

    data class Unsupported(val typeName: String) : PageBlock
}

sealed interface RichText {
    data class Plain(val text: String) : RichText
    data class Bold(val text: RichText) : RichText
    data class Italic(val text: RichText) : RichText
    data class Underline(val text: RichText) : RichText
    data class Strikethrough(val text: RichText) : RichText
    data class Spoiler(val text: RichText) : RichText
    data class DateTime(val text: RichText, val unixTime: Int) : RichText
    data class Mention(val text: RichText, val username: String) : RichText
    data class Hashtag(val text: RichText, val hashtag: String) : RichText
    data class Cashtag(val text: RichText, val cashtag: String) : RichText
    data class BotCommand(val text: RichText, val botCommand: String) : RichText
    data class Fixed(val text: RichText) : RichText
    data class MentionName(val text: RichText, val userId: Long) : RichText
    data class Url(val text: RichText, val url: String, val isCached: Boolean) : RichText
    data class EmailAddress(val text: RichText, val emailAddress: String) : RichText
    data class BankCardNumber(val text: RichText, val bankCardNumber: String) : RichText
    data class Subscript(val text: RichText) : RichText
    data class Superscript(val text: RichText) : RichText
    data class Marked(val text: RichText) : RichText
    data class PhoneNumber(val text: RichText, val phoneNumber: String) : RichText
    data class CustomEmoji(val customEmojiId: Long, val alternativeText: String) : RichText
    data class Icon(val document: WebPage.Document, val width: Int, val height: Int) : RichText
    data class MathematicalExpression(val expression: String) : RichText
    data class Reference(val text: RichText, val anchorName: String, val url: String) : RichText
    data class Anchor(val name: String) : RichText
    data class AnchorLink(val text: RichText, val anchorName: String, val url: String) : RichText
    data class Texts(val texts: List<RichText>) : RichText
}

data class PageBlockListItem(val label: String, val pageBlocks: List<PageBlock>)
data class PageBlockCaption(val text: RichText, val credit: RichText)
data class PageBlockRelatedArticle(
    val url: String,
    val title: String,
    val description: String,
    val photo: WebPage.Photo?,
    val author: String,
    val publishDate: Int
)

data class PageBlockTableCell(
    val text: RichText,
    val isHeader: Boolean,
    val colspan: Int,
    val rowspan: Int,
    val align: HorizontalAlignment,
    val valign: VerticalAlignment
)

enum class HorizontalAlignment { LEFT, CENTER, RIGHT }
enum class VerticalAlignment { TOP, MIDDLE, BOTTOM }

data class Location(val latitude: Double, val longitude: Double)
