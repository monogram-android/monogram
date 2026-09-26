use tellers_mtproto::latest::api::{
    Page, PageBlock, PageCaption, Photo, PhotoSize, RichText, WebPage,
};

use super::thumbs::photo_size_type;
use crate::peers::vector_boxed_items;

pub(crate) fn webpage_plain(page: &WebPage) -> Option<String> {
    let WebPage::WebPage(p) = page else {
        return None;
    };
    let mut out = String::new();
    if let Some(title) = p.title.as_ref().filter(|s| !s.is_empty()) {
        out.push_str(title);
    }
    if let Some(description) = p.description.as_ref().filter(|s| !s.is_empty()) {
        if !out.is_empty() {
            out.push_str("\n\n");
        }
        out.push_str(description);
    }
    if let Some(cached) = p.cached_page.as_ref() {
        let body = page_plain(cached);
        if !body.is_empty() {
            if !out.is_empty() {
                out.push_str("\n\n");
            }
            out.push_str(&body);
        }
    }
    if out.is_empty() { None } else { Some(out) }
}

#[allow(dead_code)]
pub(crate) fn page_plain(page: &Page) -> String {
    let Page::Page(p) = page else {
        return String::new();
    };
    page_blocks_formatted(vector_boxed_items(&p.blocks)).text
}

#[derive(Clone, Debug, Default)]
pub(crate) struct FormattedText {
    pub text: String,
    /// Format entities on page blocks are few and small per block. Stated inline: 4.
    pub entities: crate::SmallVec<[FormatEntity; 4]>,
}

#[derive(Clone, Debug, serde::Serialize)]
pub(crate) struct FormatEntity {
    pub kind: String,
    pub offset: i32,
    pub length: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
}

pub(crate) fn page_blocks_plain<'a>(blocks: impl Iterator<Item = &'a PageBlock>) -> String {
    page_blocks_formatted(blocks).text
}

pub(crate) fn page_blocks_formatted<'a>(
    blocks: impl Iterator<Item = &'a PageBlock>,
) -> FormattedText {
    page_blocks_formatted_media(blocks, &[])
}

pub(crate) fn page_blocks_formatted_media<'a>(
    blocks: impl Iterator<Item = &'a PageBlock>,
    photos: &'a [Photo],
) -> FormattedText {
    let mut out = FormattedText::default();
    for block in blocks {
        if out.text.len() >= 12_000 {
            break;
        }
        let start = out.text.len();
        push_block(block, &mut out, photos);
        if out.text.len() > start && !out.text.ends_with('\n') {
            out.text.push('\n');
        }
    }
    while out.text.ends_with('\n') {
        out.text.pop();
    }
    out
}

pub(crate) fn utf16_len(text: &str) -> i32 {
    text.encode_utf16().count() as i32
}

fn mark(out: &mut FormattedText, start_utf16: i32, kind: &str, url: Option<String>) {
    let length = utf16_len(&out.text) - start_utf16;
    if length > 0 {
        out.entities.push(FormatEntity {
            kind: kind.to_string(),
            offset: start_utf16,
            length,
            url,
        });
    }
}

fn push_block(block: &PageBlock, out: &mut FormattedText, photos: &[Photo]) {
    match block {
        PageBlock::PageBlockTitle(b) => push_heading(&b.text, out, 1),
        PageBlock::PageBlockHeading1(b) => push_heading(&b.text, out, 1),
        PageBlock::PageBlockHeader(b) => push_heading(&b.text, out, 1),
        PageBlock::PageBlockHeading2(b) => push_heading(&b.text, out, 2),
        PageBlock::PageBlockSubheader(b) => push_heading(&b.text, out, 2),
        PageBlock::PageBlockHeading3(b) => push_heading(&b.text, out, 3),
        PageBlock::PageBlockHeading4(b) => push_heading(&b.text, out, 4),
        PageBlock::PageBlockHeading5(b) => push_heading(&b.text, out, 5),
        PageBlock::PageBlockHeading6(b) => push_heading(&b.text, out, 6),
        PageBlock::PageBlockKicker(b) => push_heading(&b.text, out, 2),
        PageBlock::PageBlockSubtitle(b) => push_rich(&b.text, out),
        PageBlock::PageBlockParagraph(b) => push_rich(&b.text, out),
        PageBlock::PageBlockFooter(b) => push_rich(&b.text, out),
        PageBlock::PageBlockPreformatted(b) => {
            let start = utf16_len(&out.text);
            push_rich(&b.text, out);
            let lang = b.language.trim();
            mark(
                out,
                start,
                "pre",
                (!lang.is_empty()).then(|| lang.to_string()),
            );
        }
        PageBlock::PageBlockBlockquote(b) => {
            let start = utf16_len(&out.text);
            push_rich(&b.text, out);
            mark(out, start, "blockquote", None);
        }
        PageBlock::PageBlockPullquote(b) => {
            let start = utf16_len(&out.text);
            push_rich(&b.text, out);
            mark(out, start, "blockquote", None);
        }
        PageBlock::PageBlockBlockquoteBlocks(b) => {
            let start = utf16_len(&out.text);
            push_nested_blocks(vector_boxed_items(&b.blocks), out, photos);
            if !matches!(b.caption.as_ref(), RichText::TextEmpty(_)) {
                ensure_nl(out);
                push_rich(&b.caption, out);
            }
            mark(out, start, "blockquote", None);
        }
        PageBlock::PageBlockList(b) => push_unordered_list(b, out, 0, photos),
        PageBlock::PageBlockOrderedList(b) => push_ordered_list(b, out, 0, photos),
        PageBlock::PageBlockTable(b) => {
            let start = utf16_len(&out.text);
            push_table(b, out);
            mark(out, start, "pre", Some("table".into()));
        }
        PageBlock::PageBlockDivider(_) => {
            ensure_nl(out);
            out.text.push_str("---");
        }
        PageBlock::PageBlockDetails(b) => {
            let start = utf16_len(&out.text);
            push_rich(&b.title, out);
            ensure_nl(out);
            push_nested_blocks(vector_boxed_items(&b.blocks), out, photos);
            mark(out, start, "details", None);
        }
        PageBlock::PageBlockPhoto(b) => push_page_photo(b, out, photos),
        PageBlock::PageBlockMath(b) => {
            let start = utf16_len(&out.text);
            out.text.push_str(&b.source);
            mark(out, start, "code", None);
        }
        _ => {}
    }
}

fn ensure_nl(out: &mut FormattedText) {
    if !out.text.is_empty() && !out.text.ends_with('\n') {
        out.text.push('\n');
    }
}

fn push_nested_blocks<'a>(
    blocks: impl Iterator<Item = &'a PageBlock>,
    out: &mut FormattedText,
    photos: &[Photo],
) {
    for nested in blocks {
        ensure_nl(out);
        push_block(nested, out, photos);
    }
}

fn push_page_caption(caption: &PageCaption, out: &mut FormattedText) {
    let PageCaption::PageCaption(cap) = caption;
    if !matches!(cap.text.as_ref(), RichText::TextEmpty(_)) {
        push_rich(&cap.text, out);
    }
    if !matches!(cap.credit.as_ref(), RichText::TextEmpty(_)) {
        ensure_nl(out);
        push_rich(&cap.credit, out);
    }
}

pub(crate) fn photo_id_of(photo: &Photo) -> Option<i64> {
    match photo {
        Photo::Photo(p) => Some(p.id),
        _ => None,
    }
}

pub(crate) fn photo_wh(photo: &Photo) -> (i32, i32) {
    let Photo::Photo(ph) = photo else {
        return (0, 0);
    };
    let sizes: Vec<PhotoSize> = vector_boxed_items(&ph.sizes).cloned().collect();
    let mut best: Option<(i32, i32, i32)> = None;
    for size in &sizes {
        let Some((_, area, _, w, h)) = photo_size_type(size) else {
            continue;
        };
        match best {
            None => best = Some((area, w, h)),
            Some((best_area, _, _)) if area >= best_area => best = Some((area, w, h)),
            _ => {}
        }
    }
    (
        best.map(|(_, w, _)| w).unwrap_or(0),
        best.map(|(_, _, h)| h).unwrap_or(0),
    )
}

fn push_page_photo(
    block: &tellers_mtproto::latest::api::PageBlockPhotoConstructor,
    out: &mut FormattedText,
    photos: &[Photo],
) {
    if let Some(photo) = photos
        .iter()
        .find(|p| photo_id_of(p) == Some(block.photo_id))
    {
        ensure_nl(out);
        let start = utf16_len(&out.text);
        out.text.push('\u{FFFC}');
        let (w, h) = photo_wh(photo);
        mark(
            out,
            start,
            "photo",
            Some(format!("photo:{}:{}x{}", block.photo_id, w, h)),
        );
        ensure_nl(out);
    }
    push_page_caption(&block.caption, out);
}

fn checkbox_prefix(checkbox: bool, checked: bool) -> &'static str {
    if !checkbox {
        return "";
    }
    if checked { "☑ " } else { "☐ " }
}

fn is_list_block(block: &PageBlock) -> bool {
    matches!(
        block,
        PageBlock::PageBlockList(_) | PageBlock::PageBlockOrderedList(_)
    )
}

fn push_unordered_list(
    list: &tellers_mtproto::latest::api::PageBlockListConstructor,
    out: &mut FormattedText,
    indent: usize,
    photos: &[Photo],
) {
    for item in vector_boxed_items(&list.items) {
        ensure_nl(out);
        for _ in 0..indent {
            out.text.push_str("  ");
        }
        match item {
            tellers_mtproto::latest::api::PageListItem::PageListItemText(t) => {
                let prefix = checkbox_prefix(t.checkbox.is_some(), t.checked.is_some());
                out.text
                    .push_str(if prefix.is_empty() { "• " } else { prefix });
                push_rich(&t.text, out);
            }
            tellers_mtproto::latest::api::PageListItem::PageListItemBlocks(t) => {
                let prefix = checkbox_prefix(t.checkbox.is_some(), t.checked.is_some());
                out.text
                    .push_str(if prefix.is_empty() { "• " } else { prefix });
                for nested in vector_boxed_items(&t.blocks) {
                    if is_list_block(nested) {
                        match nested {
                            PageBlock::PageBlockList(inner) => {
                                push_unordered_list(inner, out, indent + 1, photos)
                            }
                            PageBlock::PageBlockOrderedList(inner) => {
                                push_ordered_list(inner, out, indent + 1, photos)
                            }
                            _ => push_block(nested, out, photos),
                        }
                    } else {
                        push_block(nested, out, photos);
                    }
                }
            }
        }
    }
}

fn push_ordered_list(
    list: &tellers_mtproto::latest::api::PageBlockOrderedListConstructor,
    out: &mut FormattedText,
    indent: usize,
    photos: &[Photo],
) {
    for (i, item) in vector_boxed_items(&list.items).enumerate() {
        ensure_nl(out);
        for _ in 0..indent {
            out.text.push_str("  ");
        }
        match item {
            tellers_mtproto::latest::api::PageListOrderedItem::PageListOrderedItemText(t) => {
                let prefix = checkbox_prefix(t.checkbox.is_some(), t.checked.is_some());
                if prefix.is_empty() {
                    let n = t.num.clone().unwrap_or_else(|| format!("{}.", i + 1));
                    out.text.push_str(&n);
                    if !n.ends_with(' ') {
                        out.text.push(' ');
                    }
                } else {
                    out.text.push_str(prefix);
                }
                push_rich(&t.text, out);
            }
            tellers_mtproto::latest::api::PageListOrderedItem::PageListOrderedItemBlocks(t) => {
                let prefix = checkbox_prefix(t.checkbox.is_some(), t.checked.is_some());
                if prefix.is_empty() {
                    let n = t.num.clone().unwrap_or_else(|| format!("{}.", i + 1));
                    out.text.push_str(&n);
                    if !n.ends_with(' ') {
                        out.text.push(' ');
                    }
                } else {
                    out.text.push_str(prefix);
                }
                for nested in vector_boxed_items(&t.blocks) {
                    if is_list_block(nested) {
                        match nested {
                            PageBlock::PageBlockList(inner) => {
                                push_unordered_list(inner, out, indent + 1, photos)
                            }
                            PageBlock::PageBlockOrderedList(inner) => {
                                push_ordered_list(inner, out, indent + 1, photos)
                            }
                            _ => push_block(nested, out, photos),
                        }
                    } else {
                        push_block(nested, out, photos);
                    }
                }
            }
        }
    }
}

fn push_heading(text: &RichText, out: &mut FormattedText, level: u8) {
    let start = utf16_len(&out.text);
    push_rich(text, out);
    mark(out, start, "heading", Some(level.to_string()));
}

fn push_table(
    table: &tellers_mtproto::latest::api::PageBlockTableConstructor,
    out: &mut FormattedText,
) {
    for row in vector_boxed_items(&table.rows) {
        let tellers_mtproto::latest::api::PageTableRow::PageTableRow(row) = row else {
            continue;
        };
        if !out.text.is_empty() && !out.text.ends_with('\n') {
            out.text.push('\n');
        }
        out.text.push('|');
        for cell in vector_boxed_items(&row.cells) {
            let tellers_mtproto::latest::api::PageTableCell::PageTableCell(cell) = cell else {
                continue;
            };
            out.text.push(' ');
            if let Some(text) = cell.text.as_ref() {
                push_rich(text, out);
            }
            out.text.push_str(" |");
        }
    }
}

pub(crate) fn rich_formatted(text: &RichText) -> FormattedText {
    let mut out = FormattedText::default();
    push_rich(text, &mut out);
    out
}

fn push_rich(text: &RichText, out: &mut FormattedText) {
    if out.text.len() >= 12_000 {
        return;
    }
    let start = utf16_len(&out.text);
    match text {
        RichText::TextEmpty(_) | RichText::TextImage(_) => {}
        RichText::TextPlain(t) => out.text.push_str(&t.text),
        RichText::TextConcat(t) => {
            for child in vector_boxed_items(&t.texts) {
                push_rich(child, out);
            }
        }
        RichText::TextBold(t) => {
            push_rich(&t.text, out);
            mark(out, start, "bold", None);
        }
        RichText::TextItalic(t) => {
            push_rich(&t.text, out);
            mark(out, start, "italic", None);
        }
        RichText::TextUnderline(t) => {
            push_rich(&t.text, out);
            mark(out, start, "underline", None);
        }
        RichText::TextStrike(t) => {
            push_rich(&t.text, out);
            mark(out, start, "strike", None);
        }
        RichText::TextFixed(t) => {
            push_rich(&t.text, out);
            mark(out, start, "code", None);
        }
        RichText::TextBankCard(t) => {
            push_rich(&t.text, out);
            mark(out, start, "code", None);
        }
        RichText::TextSpoiler(t) => {
            push_rich(&t.text, out);
            mark(out, start, "spoiler", None);
        }
        RichText::TextMarked(t) => {
            push_rich(&t.text, out);
            mark(out, start, "spoiler", None);
        }
        RichText::TextUrl(t) => {
            push_rich(&t.text, out);
            mark(out, start, "text_url", Some(t.url.clone()));
        }
        RichText::TextEmail(t) => {
            push_rich(&t.text, out);
            mark(out, start, "email", None);
        }
        RichText::TextAutoEmail(t) => {
            push_rich(&t.text, out);
            mark(out, start, "email", None);
        }
        RichText::TextPhone(t) => {
            push_rich(&t.text, out);
            mark(out, start, "phone", None);
        }
        RichText::TextAutoPhone(t) => {
            push_rich(&t.text, out);
            mark(out, start, "phone", None);
        }
        RichText::TextHashtag(t) => {
            push_rich(&t.text, out);
            mark(out, start, "hashtag", None);
        }
        RichText::TextMention(t) => {
            push_rich(&t.text, out);
            mark(out, start, "mention", None);
        }
        RichText::TextMentionName(t) => {
            push_rich(&t.text, out);
            mark(out, start, "mention", None);
        }
        RichText::TextBotCommand(t) => {
            push_rich(&t.text, out);
            mark(out, start, "bot_command", None);
        }
        RichText::TextCashtag(t) => {
            push_rich(&t.text, out);
            mark(out, start, "cashtag", None);
        }
        RichText::TextAutoUrl(t) => {
            push_rich(&t.text, out);
            mark(out, start, "url", None);
        }
        RichText::TextCustomEmoji(t) => out.text.push_str(&t.alt),
        RichText::TextSubscript(t) => {
            push_rich(&t.text, out);
            mark(out, start, "subscript", None);
        }
        RichText::TextSuperscript(t) => {
            push_rich(&t.text, out);
            mark(out, start, "superscript", None);
        }
        RichText::TextAnchor(t) => push_rich(&t.text, out),
        RichText::TextMath(t) => out.text.push_str(&t.source),
        RichText::TextDate(t) => push_rich(&t.text, out),
        RichText::TextDiff(t) => push_rich(&t.text, out),
        RichText::TextButton(t) => push_rich(&t.text, out),
    }
}
