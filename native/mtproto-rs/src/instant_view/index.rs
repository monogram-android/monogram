use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    Document, GeoPoint, InputGeoPoint, InputGeoPointConstructor,
    InputWebFileGeoPointLocationConstructor, InputWebFileLocation, PageBlock, Photo,
    UploadGetWebFileRequest, UploadWebFile,
};
use tellers_mtproto_session::Snapshot;

use crate::media::{self, MediaIndex, MediaLocation, MediaRef};
use crate::peers::vector_boxed_items;
use crate::MtprotoError;

/// Indexed as `(media_id, INSTANT_VIEW_MEDIA_MSG)` so avatars `(peer, 0)` stay distinct.
pub const INSTANT_VIEW_MEDIA_MSG: i32 = -1;

pub(crate) struct PageContext<'a> {
    pub(crate) photos: HashMap<i64, &'a Photo>,
    pub(crate) documents: HashMap<i64, &'a Document>,
}

pub(crate) fn index_page_media<'a>(
    media: &mut MediaIndex,
    page_url: &str,
    photos: impl Iterator<Item = &'a Photo>,
    documents: impl Iterator<Item = &'a Document>,
) {
    for photo in photos {
        let Some(id) = photo_id(photo) else { continue };
        let cache_key = format!("photo:{id}");
        if let Some(mut indexed) = media::media_ref_from_photo_with_thumbs(photo, cache_key) {
            indexed.source_url = Some(page_url.to_string());
            media.insert((id, INSTANT_VIEW_MEDIA_MSG), indexed);
        }
    }
    for document in documents {
        let Document::Document(d) = document else {
            continue;
        };
        let mut indexed = media::media_ref_from_document(d);
        indexed.source_url = Some(page_url.to_string());
        media.insert((d.id, INSTANT_VIEW_MEDIA_MSG), indexed);
    }
}

pub(crate) fn index_map_previews<'a>(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &mut MediaIndex,
    page_url: &str,
    blocks: impl Iterator<Item = &'a PageBlock>,
) {
    for block in blocks {
        match block {
            PageBlock::PageBlockMap(m) => {
                let _ = index_one_map(snapshot, api_id, media, page_url, m);
            }
            PageBlock::PageBlockCover(c) => {
                index_map_previews(
                    snapshot,
                    api_id,
                    media,
                    page_url,
                    std::iter::once(c.cover.as_ref()),
                );
            }
            PageBlock::PageBlockDetails(d) => {
                index_map_previews(
                    snapshot,
                    api_id,
                    media,
                    page_url,
                    vector_boxed_items(&d.blocks),
                );
            }
            PageBlock::PageBlockCollage(c) => {
                index_map_previews(
                    snapshot,
                    api_id,
                    media,
                    page_url,
                    vector_boxed_items(&c.items),
                );
            }
            PageBlock::PageBlockSlideshow(c) => {
                index_map_previews(
                    snapshot,
                    api_id,
                    media,
                    page_url,
                    vector_boxed_items(&c.items),
                );
            }
            PageBlock::PageBlockEmbedPost(c) => {
                index_map_previews(
                    snapshot,
                    api_id,
                    media,
                    page_url,
                    vector_boxed_items(&c.blocks),
                );
            }
            PageBlock::PageBlockBlockquoteBlocks(c) => {
                index_map_previews(
                    snapshot,
                    api_id,
                    media,
                    page_url,
                    vector_boxed_items(&c.blocks),
                );
            }
            _ => {}
        }
    }
}

pub(crate) fn index_one_map(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &mut MediaIndex,
    page_url: &str,
    block: &tellers_mtproto::latest::api::PageBlockMapConstructor,
) -> Result<(), MtprotoError> {
    let GeoPoint::GeoPoint(geo) = block.geo.as_ref() else {
        return Ok(());
    };
    let cache_key = format!("geo:{}:{}", geo.access_hash, block.zoom);
    let location = InputWebFileLocation::InputWebFileGeoPointLocation(
        InputWebFileGeoPointLocationConstructor {
            geo_point: Box::new(InputGeoPoint::InputGeoPoint(InputGeoPointConstructor {
                flags: 0,
                lat: geo.lat,
                long: geo.long,
                accuracy_radius: None,
            })),
            access_hash: geo.access_hash,
            w: block.w.clamp(64, 1024),
            h: block.h.clamp(64, 1024),
            zoom: block.zoom,
            scale: 1,
        },
    );
    let response: UploadWebFile = crate::api_invoke::invoke_api_without_updates(
        snapshot,
        api_id,
        UploadGetWebFileRequest {
            location: Box::new(location),
            offset: 0,
            limit: 128 * 1024,
        },
    )?;
    let UploadWebFile::UploadWebFile(file) = response;
    media.insert(
        (geo.access_hash, INSTANT_VIEW_MEDIA_MSG),
        MediaRef {
            kind: "photo".into(),
            cache_key,
            location: MediaLocation::Inline {
                bytes: file.bytes,
                extension: "jpg".into(),
            },
            thumb_cache_key: None,
            thumb_location: None,
            display_cache_key: None,
            display_location: None,
            sticker_set_id: None,
            sticker_set_access_hash: None,
            source_url: if page_url.is_empty() {
                None
            } else {
                Some(page_url.to_string())
            },
        },
    );
    Ok(())
}

/// IV media is keyed `(id, INSTANT_VIEW_MEDIA_MSG)`. FILE_REFERENCE refresh
/// re-runs `messages.getWebPage` for this URL instead of a chat message.
pub(crate) fn media_refresh_url(
    media: &MediaIndex,
    chat_id: i64,
    message_id: i32,
) -> Option<String> {
    if message_id != INSTANT_VIEW_MEDIA_MSG {
        return None;
    }
    media.get(&(chat_id, message_id))?.source_url.clone()
}

pub(crate) fn photo_id(photo: &Photo) -> Option<i64> {
    match photo {
        Photo::Photo(p) => Some(p.id),
        _ => None,
    }
}

pub(crate) fn document_id(document: &Document) -> Option<i64> {
    match document {
        Document::Document(d) => Some(d.id),
        _ => None,
    }
}
