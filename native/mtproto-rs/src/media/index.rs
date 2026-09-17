use tellers_mtproto::latest::api::{
    Document, DocumentAttribute, InputStickerSet, Message, MessageMedia,
    MessagesGetCustomEmojiDocumentsRequest, PageBlock, Photo, PhotoSize, RichMessage, Vector,
    VectorConstructor, VideoSize, WebPage,
};
use tellers_mtproto_session::Snapshot;

use super::location::{MediaIndex, MediaLocation, MediaRef};
use super::page_plain::photo_id_of;
use super::structured::{
    contact_to_json, dice_to_json, geo_live_to_json, geo_to_json, poll_to_json, todo_title,
    todo_to_json, venue_to_json,
};
use super::thumbs::{
    collect_photo_sizes, distinct_thumb_key, pick_display_size, pick_document_thumb_size,
    pick_getfile_preview, pick_photo_size, pick_thumb_size,
};
use crate::api_invoke;
use crate::peers::{peer_chat_id, vector_boxed_items};
use crate::MtprotoError;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MediaMetrics {
    pub duration: Option<i32>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub file_name: Option<String>,
    pub file_size: Option<i64>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct IndexedMessageMedia {
    pub kind: Option<String>,
    pub cache_key: Option<String>,
    pub thumb_cache_key: Option<String>,
    pub duration: Option<i32>,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub file_name: Option<String>,
    pub file_size: Option<i64>,
}

pub(crate) fn classify_document(
    mime_type: &str,
    is_sticker: bool,
    is_animated: bool,
    is_video: bool,
) -> &'static str {
    if is_sticker && is_animated {
        "sticker_animated"
    } else if is_sticker && (is_video || mime_type.contains("webm")) {
        "sticker_video"
    } else if is_sticker {
        "sticker"
    } else if !is_sticker && is_animated && mime_type == "video/mp4" {
        // GIF documents are stored as silent mp4 with DocumentAttributeAnimated
        "gif"
    } else if is_video || mime_type.starts_with("video/") {
        "video"
    } else if mime_type == "image/gif" {
        "gif"
    } else if mime_type.starts_with("image/") {
        "photo"
    } else {
        "document"
    }
}

pub(crate) fn duration_secs(duration: f64) -> i32 {
    duration.max(0.0).round() as i32
}

pub(crate) fn document_kind(doc: &tellers_mtproto::latest::api::DocumentConstructor) -> String {
    let attrs: Vec<&DocumentAttribute> = vector_boxed_items(&doc.attributes).collect();
    let is_voice = attrs.iter().any(
        |a| matches!(a, DocumentAttribute::DocumentAttributeAudio(audio) if audio.voice.is_some()),
    );
    if is_voice {
        return "voice".into();
    }
    let is_audio = attrs
        .iter()
        .any(|a| matches!(a, DocumentAttribute::DocumentAttributeAudio(_)));
    if is_audio {
        return "audio".into();
    }
    let is_sticker = attrs
        .iter()
        .any(|a| matches!(a, DocumentAttribute::DocumentAttributeSticker(_)));
    let is_animated = attrs
        .iter()
        .any(|a| matches!(a, DocumentAttribute::DocumentAttributeAnimated(_)))
        || doc.mime_type.contains("tgsticker")
        || doc.mime_type.contains("tgs");
    let is_video = attrs
        .iter()
        .any(|a| matches!(a, DocumentAttribute::DocumentAttributeVideo(_)));
    classify_document(&doc.mime_type, is_sticker, is_animated, is_video).into()
}

pub(crate) fn document_metrics(
    doc: &tellers_mtproto::latest::api::DocumentConstructor,
) -> MediaMetrics {
    let mut metrics = MediaMetrics::default();
    for attr in vector_boxed_items(&doc.attributes) {
        match attr {
            DocumentAttribute::DocumentAttributeVideo(v) => {
                metrics.duration = Some(duration_secs(v.duration));
                metrics.width = Some(v.w);
                metrics.height = Some(v.h);
            }
            DocumentAttribute::DocumentAttributeImageSize(s) if metrics.width.is_none() => {
                metrics.width = Some(s.w);
                metrics.height = Some(s.h);
            }
            DocumentAttribute::DocumentAttributeAudio(a) if metrics.duration.is_none() => {
                metrics.duration = Some(a.duration);
            }
            _ => {}
        }
    }
    metrics
}

pub(crate) fn photo_metrics(sizes: &[PhotoSize]) -> MediaMetrics {
    let mut best: Option<(i32, i32, i32)> = None;
    for size in sizes {
        let Some((_, area, _, w, h)) = super::thumbs::photo_size_type(size) else {
            continue;
        };
        match best {
            None => best = Some((area, w, h)),
            Some((best_area, _, _)) if area >= best_area => best = Some((area, w, h)),
            _ => {}
        }
    }
    MediaMetrics {
        duration: None,
        width: best.map(|(_, w, _)| w),
        height: best.map(|(_, _, h)| h),
        file_name: None,
        file_size: None,
    }
}

pub fn media_ref_from_photo(photo: &Photo, cache_key: String) -> Option<MediaRef> {
    let Photo::Photo(photo) = photo else {
        return None;
    };
    if let Some(videos) = photo.video_sizes.as_ref() {
        let picked = vector_boxed_items(videos).find_map(|size| match size {
            VideoSize::VideoSize(v) if v.type_ == "u" || v.type_ == "v" => Some(v.type_.clone()),
            VideoSize::VideoSize(v) => Some(v.type_.clone()),
            _ => None,
        });
        if let Some(thumb_size) = picked {
            let cache_key = if cache_key.ends_with(":video") {
                cache_key
            } else {
                format!("{cache_key}:video")
            };
            return Some(MediaRef {
                kind: "video_avatar".into(),
                cache_key,
                location: MediaLocation::Photo {
                    id: photo.id,
                    access_hash: photo.access_hash,
                    file_reference: photo.file_reference.clone(),
                    thumb_size,
                    dc_id: photo.dc_id,
                },
                thumb_cache_key: None,
                thumb_location: None,
                display_cache_key: None,
                display_location: None,
                sticker_set_id: None,
                sticker_set_access_hash: None,
                source_url: None,
            });
        }
    }
    let sizes: Vec<PhotoSize> = vector_boxed_items(&photo.sizes).cloned().collect();
    let (thumb_size, inline) = pick_photo_size(&sizes)?;
    let location = if let Some(bytes) = inline {
        MediaLocation::Inline {
            bytes,
            extension: "jpg".into(),
        }
    } else {
        MediaLocation::Photo {
            id: photo.id,
            access_hash: photo.access_hash,
            file_reference: photo.file_reference.clone(),
            thumb_size,
            dc_id: photo.dc_id,
        }
    };
    Some(MediaRef {
        kind: "photo".into(),
        cache_key,
        location,
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    })
}

/// Still-image photo for inline galleries: skip `video_sizes` (those are `u`/`v`
/// streaming letters that fail `upload.getFile`) and attach a small getFile thumb.
pub(crate) fn media_ref_from_photo_with_thumbs(
    photo: &Photo,
    cache_key: String,
) -> Option<MediaRef> {
    let Photo::Photo(ph) = photo else {
        return None;
    };
    let sizes: Vec<PhotoSize> = vector_boxed_items(&ph.sizes).cloned().collect();
    let (full_ty, full_inline) = pick_photo_size(&sizes)?;
    let thumb = pick_document_thumb_size(&sizes);
    let location = photo_file_location(ph, full_ty.clone(), full_inline);
    let (thumb_cache_key, thumb_location) = match thumb {
        Some((ty, inline)) if ty != full_ty => (
            Some(distinct_thumb_key(&cache_key, &full_ty, &ty)),
            Some(photo_file_location(ph, ty, inline)),
        ),
        Some(_) => (Some(cache_key.clone()), None),
        None => (None, None),
    };
    Some(MediaRef {
        kind: "photo".into(),
        cache_key,
        location,
        thumb_cache_key,
        thumb_location,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    })
}

pub(crate) fn attach_document_thumbs(
    doc: &tellers_mtproto::latest::api::DocumentConstructor,
    media: &mut MediaRef,
) {
    if media.thumb_location.is_some() {
        return;
    }
    let thumbs: Vec<PhotoSize> = doc
        .thumbs
        .as_ref()
        .map(|t| vector_boxed_items(t).cloned().collect())
        .unwrap_or_default();
    let Some((ty, inline)) = pick_document_thumb_size(&thumbs) else {
        return;
    };
    media.thumb_cache_key = Some(format!("{}:thumb", media.cache_key));
    media.thumb_location = Some(document_file_location(doc, ty, inline));
}

pub fn avatar_cache_key(chat_id: i64, has_video: bool) -> String {
    if has_video {
        format!("avatar:{chat_id}:video")
    } else {
        format!("avatar:{chat_id}")
    }
}

pub fn is_video_avatar(media: &MediaRef) -> bool {
    media.kind == "video_avatar"
}

pub fn is_resolved_video_avatar(media: &MediaRef) -> bool {
    is_video_avatar(media) && matches!(media.location, MediaLocation::Photo { .. })
}

pub fn needs_video_avatar_upgrade(media: &MediaRef) -> bool {
    is_video_avatar(media) && matches!(media.location, MediaLocation::PeerPhoto { .. })
}

fn avatar_photo_id(media: &MediaRef) -> Option<i64> {
    match &media.location {
        MediaLocation::PeerPhoto { photo_id, .. } => Some(*photo_id),
        MediaLocation::Photo { id, .. } => Some(*id),
        _ => None,
    }
}

/// Keep a resolved profile video (`inputPhotoFileLocation` + `u`/`v`) over a still
/// `InputPeerPhotoFileLocation` for the same photo id.
pub fn index_avatar(media: &mut MediaIndex, chat_id: i64, incoming: MediaRef) -> String {
    let key = (chat_id, 0);
    if let Some(existing) = media.get(&key) {
        if is_resolved_video_avatar(existing)
            && !is_resolved_video_avatar(&incoming)
            && avatar_photo_id(existing) == avatar_photo_id(&incoming)
        {
            return existing.cache_key.clone();
        }
    }
    let cache_key = incoming.cache_key.clone();
    media.insert(key, incoming);
    cache_key
}

pub fn media_ref_peer_photo(
    peer_kind: crate::peers::PeerKind,
    peer_id: i64,
    access_hash: i64,
    photo_id: i64,
    dc_id: i32,
    cache_key: String,
    has_video: bool,
) -> MediaRef {
    MediaRef {
        kind: if has_video {
            "video_avatar".into()
        } else {
            "photo".into()
        },
        cache_key,
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
        location: MediaLocation::PeerPhoto {
            peer_kind,
            peer_id,
            access_hash,
            photo_id,
            big: true,
            dc_id,
        },
    }
}

pub(crate) fn photo_file_location(
    photo: &tellers_mtproto::latest::api::PhotoConstructor,
    thumb_size: String,
    inline: Option<Vec<u8>>,
) -> MediaLocation {
    if let Some(bytes) = inline {
        MediaLocation::Inline {
            bytes,
            extension: "jpg".into(),
        }
    } else {
        MediaLocation::Photo {
            id: photo.id,
            access_hash: photo.access_hash,
            file_reference: photo.file_reference.clone(),
            thumb_size,
            dc_id: photo.dc_id,
        }
    }
}

pub(crate) fn document_file_location(
    doc: &tellers_mtproto::latest::api::DocumentConstructor,
    thumb_size: String,
    inline: Option<Vec<u8>>,
) -> MediaLocation {
    if let Some(bytes) = inline {
        MediaLocation::Inline {
            bytes,
            extension: "jpg".into(),
        }
    } else {
        MediaLocation::Document {
            id: doc.id,
            access_hash: doc.access_hash,
            file_reference: doc.file_reference.clone(),
            thumb_size,
            dc_id: doc.dc_id,
            mime_type: doc.mime_type.clone(),
        }
    }
}

pub(crate) fn webpage_media(
    media: &tellers_mtproto::latest::api::MessageMediaWebPageConstructor,
) -> Option<(MediaRef, MediaMetrics)> {
    let WebPage::WebPage(page) = media.webpage.as_ref() else {
        return None;
    };
    let meta = serde_json::json!({
        "u": page.url,
        "t": page.title,
        "s": page.site_name,
        "d": page.description,
        "e": page.embed_url,
        "y": page.type_,
        "iv": page.cached_page.is_some(),
        "h": page.hash,
    })
    .to_string();
    let metrics = MediaMetrics {
        duration: page.duration,
        width: page.embed_width,
        height: page.embed_height,
        file_name: Some(meta),
        file_size: None,
    };
    if let Some(photo) = page.photo.as_ref() {
        if let Photo::Photo(ph) = photo.as_ref() {
            let cache_key = format!("photo:{}", ph.id);
            if let Some(mut indexed) = media_ref_from_photo_with_thumbs(photo.as_ref(), cache_key) {
                indexed.kind = "webpage".into();
                let sizes: Vec<PhotoSize> = vector_boxed_items(&ph.sizes).cloned().collect();
                let full_ty = match &indexed.location {
                    MediaLocation::Photo { thumb_size, .. } => thumb_size.clone(),
                    _ => String::new(),
                };
                let thumb_ty = match &indexed.thumb_location {
                    Some(MediaLocation::Photo { thumb_size, .. }) => Some(thumb_size.clone()),
                    _ => None,
                };
                if let Some((ty, inline)) = pick_display_size(&sizes, thumb_ty.as_deref(), &full_ty)
                {
                    indexed.display_cache_key = Some(format!("{}:display", indexed.cache_key));
                    indexed.display_location = Some(photo_file_location(ph, ty, inline));
                }
                return Some((indexed, metrics));
            }
        }
    }
    Some((
        MediaRef {
            kind: "webpage".into(),
            cache_key: format!("web:{}", page.id),
            location: MediaLocation::Inline {
                bytes: Vec::new(),
                extension: "jpg".into(),
            },
            thumb_cache_key: None,
            thumb_location: None,
            display_cache_key: None,
            display_location: None,
            sticker_set_id: None,
            sticker_set_access_hash: None,
            source_url: None,
        },
        metrics,
    ))
}

pub(crate) fn extract_from_media(media: &MessageMedia) -> Option<(MediaRef, MediaMetrics)> {
    match media {
        MessageMedia::MessageMediaPhoto(p) => {
            let Photo::Photo(ph) = p.photo.as_ref()?.as_ref() else {
                return None;
            };
            let sizes: Vec<PhotoSize> = vector_boxed_items(&ph.sizes).cloned().collect();
            let cache_key = format!("photo:{}", ph.id);
            let metrics = photo_metrics(&sizes);
            let (full_ty, full_inline) = pick_photo_size(&sizes)?;
            let thumb = pick_thumb_size(&sizes);
            let location = photo_file_location(ph, full_ty.clone(), full_inline);
            let thumb_ty = thumb.as_ref().map(|(ty, _)| ty.as_str());
            let (display_cache_key, display_location) =
                match pick_display_size(&sizes, thumb_ty, &full_ty) {
                    Some((ty, inline)) => (
                        Some(format!("{cache_key}:display")),
                        Some(photo_file_location(ph, ty, inline)),
                    ),
                    None => (None, None),
                };
            let (thumb_cache_key, thumb_location) = match thumb {
                Some((ty, inline)) if ty != full_ty => (
                    Some(distinct_thumb_key(&cache_key, &full_ty, &ty)),
                    Some(photo_file_location(ph, ty, inline)),
                ),
                Some(_) => (Some(cache_key.clone()), None),
                None => (None, None),
            };
            Some((
                MediaRef {
                    kind: "photo".into(),
                    cache_key,
                    location,
                    thumb_cache_key,
                    thumb_location,
                    display_cache_key,
                    display_location,
                    sticker_set_id: None,
                    sticker_set_access_hash: None,
                    source_url: None,
                },
                metrics,
            ))
        }
        MessageMedia::MessageMediaDocument(d) => {
            let Document::Document(doc) = d.document.as_ref()?.as_ref() else {
                return None;
            };
            let kind = document_kind(doc);
            let cache_key = format!("doc:{}", doc.id);
            let mut metrics = document_metrics(doc);
            metrics.file_size = Some(doc.size);
            let mut filename = None;
            let mut audio_label = None;
            for attr in vector_boxed_items(&doc.attributes) {
                match attr {
                    DocumentAttribute::DocumentAttributeFilename(f) => {
                        filename = Some(f.file_name.clone());
                    }
                    DocumentAttribute::DocumentAttributeAudio(a) => {
                        audio_label = match (a.title.as_ref(), a.performer.as_ref()) {
                            (Some(title), Some(performer)) => {
                                Some(format!("{title} — {performer}"))
                            }
                            (Some(title), None) => Some(title.clone()),
                            (None, Some(performer)) => Some(performer.clone()),
                            _ => None,
                        };
                    }
                    _ => {}
                }
            }
            metrics.file_name = audio_label.or(filename);
            let thumbs: Vec<PhotoSize> = doc
                .thumbs
                .as_ref()
                .map(|t| vector_boxed_items(t).cloned().collect())
                .unwrap_or_default();
            let collected = collect_photo_sizes(&thumbs);
            let thumb = pick_thumb_size(&thumbs);
            let (thumb_cache_key, thumb_location) = match thumb {
                Some((ty, inline)) => (
                    Some(format!("{cache_key}:thumb")),
                    Some(document_file_location(doc, ty, inline)),
                ),
                None => (None, None),
            };
            let thumb_inline = matches!(
                &thumb_location,
                Some(MediaLocation::Inline { bytes, .. }) if !bytes.is_empty()
            );
            let (display_cache_key, display_location) = if thumb_inline {
                match pick_getfile_preview(&collected) {
                    Some((ty, inline)) => (
                        Some(format!("{cache_key}:display")),
                        Some(document_file_location(doc, ty, inline)),
                    ),
                    None => (None, None),
                }
            } else {
                (None, None)
            };
            let (sticker_set_id, sticker_set_access_hash) = sticker_set_ids(doc);
            Some((
                MediaRef {
                    kind,
                    cache_key,
                    location: document_file_location(doc, String::new(), None),
                    thumb_cache_key,
                    thumb_location,
                    display_cache_key,
                    display_location,
                    sticker_set_id,
                    sticker_set_access_hash,
                    source_url: None,
                },
                metrics,
            ))
        }
        MessageMedia::MessageMediaWebPage(w) => webpage_media(w),
        _ => None,
    }
}

/// Caption/body when `message` is empty but media still carries readable text.
pub(crate) fn media_fallback_text(media: &MessageMedia) -> Option<String> {
    match media {
        MessageMedia::MessageMediaToDo(todo) => todo_title(todo),
        MessageMedia::MessageMediaWebPage(_) => None,
        MessageMedia::MessageMediaDocument(d) => {
            let Document::Document(doc) = d.document.as_ref()?.as_ref() else {
                return None;
            };
            let kind = document_kind(doc);
            if matches!(
                kind.as_str(),
                "sticker"
                    | "sticker_animated"
                    | "sticker_video"
                    | "voice"
                    | "audio"
                    | "gif"
                    | "video"
                    | "photo"
            ) {
                return None;
            }
            vector_boxed_items(&doc.attributes).find_map(|attr| match attr {
                DocumentAttribute::DocumentAttributeFilename(f) => Some(f.file_name.clone()),
                _ => None,
            })
        }
        _ => None,
    }
}

pub fn index_message_media(msg: &Message, index: &mut MediaIndex) -> IndexedMessageMedia {
    let Message::Message(m) = msg else {
        return IndexedMessageMedia::default();
    };
    let chat_id = peer_chat_id(&m.peer_id);
    let Some(media) = m.media.as_ref() else {
        return match m.rich_message.as_ref().map(|rich| rich.as_ref()) {
            Some(RichMessage::RichMessage(body)) => {
                index_first_rich_photo(chat_id, m.id, body, index)
            }
            _ => IndexedMessageMedia::default(),
        };
    };
    if matches!(media.as_ref(), MessageMedia::MessageMediaUnsupported(_)) {
        return IndexedMessageMedia {
            kind: Some("unsupported".into()),
            ..IndexedMessageMedia::default()
        };
    }
    if let MessageMedia::MessageMediaToDo(todo) = media.as_ref() {
        return IndexedMessageMedia {
            kind: Some("todo".into()),
            file_name: Some(todo_to_json(todo)),
            ..IndexedMessageMedia::default()
        };
    }
    match media.as_ref() {
        MessageMedia::MessageMediaPoll(poll) => {
            return IndexedMessageMedia {
                kind: Some("poll".into()),
                file_name: Some(poll_to_json(poll)),
                ..IndexedMessageMedia::default()
            };
        }
        MessageMedia::MessageMediaGeo(geo) => {
            return IndexedMessageMedia {
                kind: Some("geo".into()),
                file_name: geo_to_json(geo),
                ..IndexedMessageMedia::default()
            };
        }
        MessageMedia::MessageMediaGeoLive(live) => {
            return IndexedMessageMedia {
                kind: Some("geo".into()),
                file_name: geo_live_to_json(live),
                ..IndexedMessageMedia::default()
            };
        }
        MessageMedia::MessageMediaVenue(venue) => {
            return IndexedMessageMedia {
                kind: Some("venue".into()),
                file_name: venue_to_json(venue),
                ..IndexedMessageMedia::default()
            };
        }
        MessageMedia::MessageMediaContact(contact) => {
            return IndexedMessageMedia {
                kind: Some("contact".into()),
                file_name: Some(contact_to_json(contact)),
                ..IndexedMessageMedia::default()
            };
        }
        MessageMedia::MessageMediaDice(dice) => {
            return IndexedMessageMedia {
                kind: Some("dice".into()),
                file_name: Some(dice_to_json(dice)),
                ..IndexedMessageMedia::default()
            };
        }
        _ => {}
    }
    let Some((media_ref, metrics)) = extract_from_media(media) else {
        return match m.rich_message.as_ref().map(|rich| rich.as_ref()) {
            Some(RichMessage::RichMessage(body)) => {
                index_first_rich_photo(chat_id, m.id, body, index)
            }
            _ => IndexedMessageMedia::default(),
        };
    };
    let indexed = IndexedMessageMedia {
        kind: Some(media_ref.kind.clone()),
        cache_key: Some(media_ref.cache_key.clone()),
        thumb_cache_key: media_ref.thumb_cache_key.clone(),
        duration: metrics.duration,
        width: metrics.width,
        height: metrics.height,
        file_name: metrics.file_name,
        file_size: metrics.file_size,
    };
    index.insert((chat_id, m.id), media_ref);
    indexed
}

pub(crate) fn index_first_rich_photo(
    chat_id: i64,
    message_id: i32,
    rich: &tellers_mtproto::latest::api::RichMessageConstructor,
    index: &mut MediaIndex,
) -> IndexedMessageMedia {
    let photos: Vec<Photo> = vector_boxed_items(&rich.photos).cloned().collect();
    let Some(want) = first_block_photo_id(vector_boxed_items(&rich.blocks)) else {
        return IndexedMessageMedia::default();
    };
    for photo in &photos {
        if photo_id_of(photo) != Some(want) {
            continue;
        }
        let cache_key = format!("photo:{want}");
        let Some(media_ref) = media_ref_from_photo_with_thumbs(photo, cache_key) else {
            continue;
        };
        let Photo::Photo(ph) = photo else {
            continue;
        };
        let sizes: Vec<PhotoSize> = vector_boxed_items(&ph.sizes).cloned().collect();
        let metrics = photo_metrics(&sizes);
        let indexed = IndexedMessageMedia {
            kind: None,
            cache_key: Some(media_ref.cache_key.clone()),
            thumb_cache_key: media_ref.thumb_cache_key.clone(),
            duration: None,
            width: metrics.width,
            height: metrics.height,
            file_name: None,
            file_size: metrics.file_size,
        };
        index.insert((chat_id, message_id), media_ref);
        return indexed;
    }
    IndexedMessageMedia::default()
}

fn first_block_photo_id<'a>(blocks: impl Iterator<Item = &'a PageBlock>) -> Option<i64> {
    for block in blocks {
        match block {
            PageBlock::PageBlockPhoto(p) => return Some(p.photo_id),
            PageBlock::PageBlockBlockquoteBlocks(p) => {
                if let Some(id) = first_block_photo_id(vector_boxed_items(&p.blocks)) {
                    return Some(id);
                }
            }
            PageBlock::PageBlockDetails(p) => {
                if let Some(id) = first_block_photo_id(vector_boxed_items(&p.blocks)) {
                    return Some(id);
                }
            }
            _ => {}
        }
    }
    None
}

pub fn fetch_custom_emoji(
    snapshot: &mut Snapshot,
    api_id: i32,
    document_id: i64,
) -> Result<MediaRef, MtprotoError> {
    let request = MessagesGetCustomEmojiDocumentsRequest {
        document_id: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![document_id],
        })),
    };
    let docs: Vector<Box<Document>> = api_invoke::invoke_api(snapshot, api_id, request)?;
    let first = vector_boxed_items(&docs)
        .next()
        .ok_or_else(|| MtprotoError::Message("empty custom emoji documents".into()))?;
    let Document::Document(doc) = first else {
        return Err(MtprotoError::Message("custom emoji document empty".into()));
    };
    Ok(media_ref_from_document(doc))
}

pub(crate) fn media_ref_from_document(
    doc: &tellers_mtproto::latest::api::DocumentConstructor,
) -> MediaRef {
    let kind = document_kind(doc);
    let (sticker_set_id, sticker_set_access_hash) = sticker_set_ids(doc);
    let cache_key = if kind.starts_with("sticker") || sticker_set_id.is_some() {
        format!("emoji:{}", doc.id)
    } else {
        format!("doc:{}", doc.id)
    };
    let mut media = MediaRef {
        kind: kind.into(),
        cache_key,
        location: document_file_location(doc, String::new(), None),
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id,
        sticker_set_access_hash,
        source_url: None,
    };
    attach_document_thumbs(doc, &mut media);
    media
}

pub(crate) fn sticker_set_ids(
    doc: &tellers_mtproto::latest::api::DocumentConstructor,
) -> (Option<i64>, Option<i64>) {
    for attr in vector_boxed_items(&doc.attributes) {
        let set = match attr {
            DocumentAttribute::DocumentAttributeSticker(s) => s.stickerset.as_ref(),
            DocumentAttribute::DocumentAttributeCustomEmoji(s) => s.stickerset.as_ref(),
            _ => continue,
        };
        if let InputStickerSet::InputStickerSetId(id) = set {
            return (Some(id.id), Some(id.access_hash));
        }
    }
    (None, None)
}
