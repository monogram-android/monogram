use serde_json::{Value, json};
use tellers_mtproto::latest::api::{
    Chat, ChatPhoto, Document, GeoPoint, PageBlock, PageButton, PageCaption, PageListItem,
    PageListOrderedItem, PageRelatedArticle, PageTableRow, Photo, PhotoSize, RichText,
};

use super::index::PageContext;
use super::rich::{formatted_json, inline_button_href, rich_json, rich_unbounded};
use crate::media;
use crate::peers::{chat_id_for_channel, chat_id_for_chat, vector_boxed_items};

pub(crate) fn map_block(block: &PageBlock, ctx: &PageContext<'_>) -> Value {
    match block {
        PageBlock::PageBlockUnsupported(_) => json!({"k": "unsupported"}),
        PageBlock::PageBlockTitle(b) => text_block("title", &b.text, 0),
        PageBlock::PageBlockSubtitle(b) => text_block("subtitle", &b.text, 0),
        PageBlock::PageBlockKicker(b) => text_block("kicker", &b.text, 0),
        PageBlock::PageBlockAuthorDate(b) => {
            let mut value = text_block("authorDate", &b.author, 0);
            value["d"] = json!(b.published_date);
            value
        }
        PageBlock::PageBlockHeader(b) => text_block("heading", &b.text, 1),
        PageBlock::PageBlockSubheader(b) => text_block("heading", &b.text, 2),
        PageBlock::PageBlockHeading1(b) => text_block("heading", &b.text, 1),
        PageBlock::PageBlockHeading2(b) => text_block("heading", &b.text, 2),
        PageBlock::PageBlockHeading3(b) => text_block("heading", &b.text, 3),
        PageBlock::PageBlockHeading4(b) => text_block("heading", &b.text, 4),
        PageBlock::PageBlockHeading5(b) => text_block("heading", &b.text, 5),
        PageBlock::PageBlockHeading6(b) => text_block("heading", &b.text, 6),
        PageBlock::PageBlockParagraph(b) => text_block("paragraph", &b.text, 0),
        PageBlock::PageBlockFooter(b) => text_block("footer", &b.text, 0),
        PageBlock::PageBlockThinking(b) => text_block("thinking", &b.text, 0),
        PageBlock::PageBlockPreformatted(b) => {
            let mut value = text_block("pre", &b.text, 0);
            if !b.language.is_empty() {
                value["lang"] = json!(b.language);
            }
            value
        }
        PageBlock::PageBlockDivider(_) => json!({"k": "divider"}),
        PageBlock::PageBlockAnchor(b) => json!({"k": "anchor", "n": b.name}),
        PageBlock::PageBlockMath(b) => json!({"k": "math", "src": b.source}),
        PageBlock::PageBlockBlockquote(b) => quote_block(&b.text, &b.caption, false),
        PageBlock::PageBlockPullquote(b) => quote_block(&b.text, &b.caption, true),
        PageBlock::PageBlockBlockquoteBlocks(b) => {
            json!({
                "k": "quote",
                "pull": false,
                "blocks": map_blocks(vector_boxed_items(&b.blocks), ctx),
                "caption": rich_json(&b.caption),
            })
        }
        PageBlock::PageBlockList(b) => json!({
            "k": "list",
            "ordered": false,
            "items": vector_boxed_items(&b.items).map(|item| list_item(item, ctx)).collect::<Vec<_>>(),
        }),
        PageBlock::PageBlockOrderedList(b) => json!({
            "k": "list",
            "ordered": true,
            "items": vector_boxed_items(&b.items).map(|item| ordered_item(item, ctx)).collect::<Vec<_>>(),
        }),
        PageBlock::PageBlockTable(b) => json!({
            "k": "table",
            "bordered": b.bordered.is_some(),
            "striped": b.striped.is_some(),
            "title": rich_json(&b.title),
            "rows": vector_boxed_items(&b.rows).map(table_row).collect::<Vec<_>>(),
        }),
        PageBlock::PageBlockDetails(b) => json!({
            "k": "details",
            "open": b.open.is_some(),
            "title": rich_json(&b.title),
            "blocks": map_blocks(vector_boxed_items(&b.blocks), ctx),
        }),
        PageBlock::PageBlockPhoto(b) => {
            media_photo(b.photo_id, Some(b.caption.as_ref()), b.url.clone(), ctx)
        }
        PageBlock::PageBlockVideo(b) => media_document(
            "video",
            b.video_id,
            Some(b.caption.as_ref()),
            json!({
                "autoplay": b.autoplay.is_some(),
                "loop": b.loop_.is_some(),
            }),
            ctx,
        ),
        PageBlock::PageBlockAudio(b) => media_document(
            "audio",
            b.audio_id,
            Some(b.caption.as_ref()),
            json!({}),
            ctx,
        ),
        PageBlock::PageBlockDocument(b) => media_document(
            "document",
            b.document_id,
            Some(b.caption.as_ref()),
            json!({}),
            ctx,
        ),
        PageBlock::PageBlockCover(b) => json!({
            "k": "cover",
            "block": map_block(b.cover.as_ref(), ctx),
        }),
        PageBlock::PageBlockEmbed(b) => json!({
            "k": "embed",
            "url": b.url,
            "html": b.html,
            "w": b.w,
            "h": b.h,
            "full": b.full_width.is_some(),
            "scroll": b.allow_scrolling.is_some(),
            "poster": b.poster_photo_id.map(|id| format!("photo:{id}")),
            "caption": caption_json(b.caption.as_ref()),
        }),
        PageBlock::PageBlockEmbedPost(b) => json!({
            "k": "embedPost",
            "url": b.url,
            "author": b.author,
            "date": b.date,
            "photo": photo_cache(b.author_photo_id, ctx),
            "blocks": map_blocks(vector_boxed_items(&b.blocks), ctx),
            "caption": caption_json(b.caption.as_ref()),
        }),
        PageBlock::PageBlockCollage(b) => json!({
            "k": "collage",
            "items": map_blocks(vector_boxed_items(&b.items), ctx),
            "caption": caption_json(b.caption.as_ref()),
        }),
        PageBlock::PageBlockSlideshow(b) => json!({
            "k": "slideshow",
            "items": map_blocks(vector_boxed_items(&b.items), ctx),
            "caption": caption_json(b.caption.as_ref()),
        }),
        PageBlock::PageBlockChannel(b) => channel_json(b.channel.as_ref()),
        PageBlock::PageBlockRelatedArticles(b) => json!({
            "k": "related",
            "title": rich_json(&b.title),
            "articles": vector_boxed_items(&b.articles).map(|a| related_article(a, ctx)).collect::<Vec<_>>(),
        }),
        PageBlock::PageBlockMap(b) => map_geo(b.geo.as_ref(), b.zoom, b.w, b.h, b.caption.as_ref()),
        PageBlock::InputPageBlockMap(b) => json!({
            "k": "map",
            "zoom": b.zoom,
            "w": b.w,
            "h": b.h,
            "caption": caption_json(b.caption.as_ref()),
        }),
        PageBlock::PageBlockButtonRow(b) => json!({
            "k": "buttons",
            "items": vector_boxed_items(&b.buttons).filter_map(page_button).collect::<Vec<_>>(),
        }),
        _ => json!({"k": "unsupported"}),
    }
}

pub(crate) fn map_blocks<'a>(
    blocks: impl Iterator<Item = &'a PageBlock>,
    ctx: &PageContext<'_>,
) -> Vec<Value> {
    blocks.map(|block| map_block(block, ctx)).collect()
}

pub(crate) fn text_block(kind: &str, text: &RichText, level: i32) -> Value {
    let mut value = rich_json(text);
    value["k"] = json!(kind);
    if level > 0 {
        value["l"] = json!(level);
    }
    value
}

pub(crate) fn quote_block(text: &RichText, caption: &RichText, pull: bool) -> Value {
    json!({
        "k": "quote",
        "pull": pull,
        "t": rich_json(text)["t"],
        "e": rich_json(text)["e"],
        "caption": rich_json(caption),
    })
}

pub(crate) fn list_item(item: &PageListItem, ctx: &PageContext<'_>) -> Value {
    match item {
        PageListItem::PageListItemText(t) => json!({
            "checkbox": t.checkbox.is_some(),
            "checked": t.checked.is_some(),
            "t": rich_json(&t.text)["t"],
            "e": rich_json(&t.text)["e"],
        }),
        PageListItem::PageListItemBlocks(t) => json!({
            "checkbox": t.checkbox.is_some(),
            "checked": t.checked.is_some(),
            "blocks": map_blocks(vector_boxed_items(&t.blocks), ctx),
        }),
        _ => json!({"t": ""}),
    }
}

pub(crate) fn ordered_item(item: &PageListOrderedItem, ctx: &PageContext<'_>) -> Value {
    match item {
        PageListOrderedItem::PageListOrderedItemText(t) => json!({
            "checkbox": t.checkbox.is_some(),
            "checked": t.checked.is_some(),
            "num": t.num,
            "t": rich_json(&t.text)["t"],
            "e": rich_json(&t.text)["e"],
        }),
        PageListOrderedItem::PageListOrderedItemBlocks(t) => json!({
            "checkbox": t.checkbox.is_some(),
            "checked": t.checked.is_some(),
            "num": t.num,
            "blocks": map_blocks(vector_boxed_items(&t.blocks), ctx),
        }),
        _ => json!({"t": ""}),
    }
}

pub(crate) fn table_row(row: &PageTableRow) -> Value {
    let PageTableRow::PageTableRow(row) = row;
    Value::Array(
        vector_boxed_items(&row.cells)
            .map(|cell| {
                let tellers_mtproto::latest::api::PageTableCell::PageTableCell(cell) = cell;
                json!({
                    "h": cell.header.is_some(),
                    "t": cell.text.as_ref().map(|t| rich_json(t)["t"].clone()).unwrap_or(json!("")),
                    "e": cell.text.as_ref().map(|t| rich_json(t)["e"].clone()).unwrap_or(json!([])),
                    "cs": cell.colspan.unwrap_or(1),
                    "rs": cell.rowspan.unwrap_or(1),
                })
            })
            .collect(),
    )
}

pub(crate) fn media_photo(
    photo_id: i64,
    caption: Option<&PageCaption>,
    url: Option<String>,
    ctx: &PageContext<'_>,
) -> Value {
    let (w, h) = ctx
        .photos
        .get(&photo_id)
        .map(|photo| photo_wh(photo))
        .unwrap_or((0, 0));
    json!({
        "k": "photo",
        "id": photo_id,
        "cache": format!("photo:{photo_id}"),
        "w": w,
        "h": h,
        "url": url,
        "caption": caption.map(caption_json),
    })
}

pub(crate) fn media_document(
    kind: &str,
    document_id: i64,
    caption: Option<&PageCaption>,
    extra: Value,
    ctx: &PageContext<'_>,
) -> Value {
    let meta = ctx
        .documents
        .get(&document_id)
        .and_then(|doc| match doc {
            Document::Document(d) => {
                let indexed = media::media_ref_from_document(d);
                let mut name = None;
                let mut title = None;
                let mut performer = None;
                let mut duration = None;
                let mut width = None;
                let mut height = None;
                for attr in vector_boxed_items(&d.attributes) {
                    match attr {
                        tellers_mtproto::latest::api::DocumentAttribute::DocumentAttributeFilename(f) => {
                            name = Some(f.file_name.clone());
                        }
                        tellers_mtproto::latest::api::DocumentAttribute::DocumentAttributeAudio(a) => {
                            title = a.title.clone();
                            performer = a.performer.clone();
                            duration = Some(a.duration);
                        }
                        tellers_mtproto::latest::api::DocumentAttribute::DocumentAttributeVideo(v) => {
                            duration = Some(v.duration as i32);
                            width = Some(v.w);
                            height = Some(v.h);
                        }
                        tellers_mtproto::latest::api::DocumentAttribute::DocumentAttributeImageSize(s) => {
                            width = Some(s.w);
                            height = Some(s.h);
                        }
                        _ => {}
                    }
                }
                Some((
                    indexed.cache_key,
                    name,
                    Some(d.mime_type.clone()),
                    Some(d.size),
                    title,
                    performer,
                    duration,
                    width,
                    height,
                ))
            }
            _ => None,
        })
        .unwrap_or_else(|| {
            (
                format!("doc:{document_id}"),
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None,
            )
        });
    let (cache, name, mime, size, title, performer, duration, width, height) = meta;
    let mut value = extra;
    if let Value::Object(map) = &mut value {
        map.insert("k".into(), json!(kind));
        map.insert("id".into(), json!(document_id));
        map.insert("cache".into(), json!(cache));
        if let Some(name) = name {
            map.insert("name".into(), json!(name));
        }
        if let Some(mime) = mime {
            map.insert("mime".into(), json!(mime));
        }
        if let Some(size) = size {
            map.insert("size".into(), json!(size));
        }
        if let Some(title) = title {
            map.insert("title".into(), json!(title));
        }
        if let Some(performer) = performer {
            map.insert("performer".into(), json!(performer));
        }
        if let Some(duration) = duration {
            map.insert("duration".into(), json!(duration));
        }
        if let (Some(w), Some(h)) = (width, height) {
            if w > 0 && h > 0 {
                map.insert("w".into(), json!(w));
                map.insert("h".into(), json!(h));
            }
        }
        if let Some(caption) = caption {
            map.insert("caption".into(), caption_json(caption));
        }
    }
    value
}

pub(crate) fn photo_cache(photo_id: i64, ctx: &PageContext<'_>) -> Option<String> {
    if ctx.photos.contains_key(&photo_id) {
        Some(format!("photo:{photo_id}"))
    } else if photo_id != 0 {
        Some(format!("photo:{photo_id}"))
    } else {
        None
    }
}

pub(crate) fn photo_wh(photo: &Photo) -> (i32, i32) {
    let Photo::Photo(ph) = photo else {
        return (0, 0);
    };
    let mut best = (0, 0, 0);
    for size in vector_boxed_items(&ph.sizes) {
        let PhotoSize::PhotoSize(s) = size else {
            continue;
        };
        let area = s.w.saturating_mul(s.h);
        if area >= best.0 {
            best = (area, s.w, s.h);
        }
    }
    (best.1, best.2)
}

pub(crate) fn caption_json(caption: &PageCaption) -> Value {
    let PageCaption::PageCaption(cap) = caption;
    json!({
        "t": rich_json(&cap.text)["t"],
        "e": rich_json(&cap.text)["e"],
        "credit": rich_json(&cap.credit),
    })
}

pub(crate) fn related_article(article: &PageRelatedArticle, ctx: &PageContext<'_>) -> Value {
    let PageRelatedArticle::PageRelatedArticle(a) = article;
    json!({
        "url": a.url,
        "title": a.title,
        "description": a.description,
        "author": a.author,
        "date": a.published_date,
        "photo": a.photo_id.and_then(|id| photo_cache(id, ctx)),
    })
}

pub(crate) fn map_geo(geo: &GeoPoint, zoom: i32, w: i32, h: i32, caption: &PageCaption) -> Value {
    let (lat, lng, access_hash) = match geo {
        GeoPoint::GeoPoint(g) => (g.lat, g.long, g.access_hash),
        _ => (0.0, 0.0, 0),
    };
    json!({
        "k": "map",
        "lat": lat,
        "lng": lng,
        "zoom": zoom,
        "w": w,
        "h": h,
        "cache": format!("geo:{access_hash}:{zoom}"),
        "hash": access_hash,
        "caption": caption_json(caption),
    })
}

pub(crate) fn channel_json(chat: &Chat) -> Value {
    match chat {
        Chat::Channel(c) => {
            let id = chat_id_for_channel(c.id);
            json!({
                "k": "channel",
                "id": id,
                "title": c.title,
                "username": c.username,
                "photo": channel_photo_key(id, c.photo.as_ref()),
            })
        }
        Chat::Chat(c) => {
            let id = chat_id_for_chat(c.id);
            json!({
                "k": "channel",
                "id": id,
                "title": c.title,
                "photo": channel_photo_key(id, c.photo.as_ref()),
            })
        }
        Chat::ChannelForbidden(c) => json!({
            "k": "channel",
            "id": chat_id_for_channel(c.id),
            "title": c.title,
        }),
        Chat::ChatForbidden(c) => json!({
            "k": "channel",
            "id": chat_id_for_chat(c.id),
            "title": c.title,
        }),
        _ => json!({"k": "channel", "id": 0, "title": ""}),
    }
}

pub(crate) fn channel_photo_key(chat_id: i64, photo: &ChatPhoto) -> Option<String> {
    match photo {
        ChatPhoto::ChatPhoto(_) => Some(format!("avatar:{chat_id}")),
        _ => None,
    }
}

pub(crate) fn page_button(button: &PageButton) -> Option<Value> {
    let PageButton::PageButton(b) = button;
    let mut formatted = rich_unbounded(&b.text);
    if let Some(url) = inline_button_href(b.type_.as_ref()) {
        let has_link = formatted
            .entities
            .iter()
            .any(|entity| entity.kind == "text_url" || entity.kind == "url");
        if !has_link {
            let length = formatted.text.encode_utf16().count() as i32;
            if length > 0 {
                formatted.entities.push(media::FormatEntity {
                    kind: "text_url".into(),
                    offset: 0,
                    length,
                    url: Some(url),
                });
            }
        }
    }
    Some(formatted_json(&formatted))
}
