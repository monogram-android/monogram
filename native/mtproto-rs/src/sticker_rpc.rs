//! Sticker / custom-emoji pack lookup.
//! https://core.telegram.org/method/messages.getStickerSet
//! https://core.telegram.org/method/messages.getAllStickers
//! https://core.telegram.org/method/messages.getEmojiStickers
//! https://core.telegram.org/method/messages.getStickers
//! https://core.telegram.org/api/stickers
//! https://core.telegram.org/api/custom-emoji

use tellers_mtproto::latest::api::{
    Document, InputStickerSet, InputStickerSetIdConstructor, MessagesAllStickers,
    MessagesGetAllStickersRequest, MessagesGetEmojiStickersRequest, MessagesGetStickerSetRequest,
    MessagesGetStickersRequest, MessagesStickerSet, MessagesStickers, StickerPack as TlStickerPack,
    StickerSet, StickerSetConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::media::{self, MediaIndex, MediaLocation};
use crate::peers::{vector_boxed_items, vector_items};
use crate::{MtprotoError, StickerCatalogDto, StickerListDto, StickerPackDto};

pub fn get_sticker_pack(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &mut MediaIndex,
    document_id: i64,
) -> Result<StickerPackDto, MtprotoError> {
    let (set_id, access_hash) = sticker_set_for_document(snapshot, api_id, media, document_id)?;
    get_sticker_set(snapshot, api_id, media, set_id, access_hash)
}

pub fn get_sticker_set(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &mut MediaIndex,
    set_id: i64,
    access_hash: i64,
) -> Result<StickerPackDto, MtprotoError> {
    let response: MessagesStickerSet = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetStickerSetRequest {
            stickerset: Box::new(InputStickerSet::InputStickerSetId(
                InputStickerSetIdConstructor {
                    id: set_id,
                    access_hash,
                },
            )),
            hash: 0,
        },
    )?;
    let MessagesStickerSet::MessagesStickerSet(pack) = response else {
        return Err(MtprotoError::Message("sticker set not modified".into()));
    };
    let StickerSet::StickerSet(set) = *pack.set;
    let mut previews = Vec::new();
    for boxed in vector_items(pack.documents.as_ref()) {
        let Document::Document(d) = boxed.as_ref() else {
            continue;
        };
        if !previews.contains(&d.id) {
            previews.push(d.id);
        }
        media.insert((d.id, 0), media::media_ref_from_document(d));
    }
    if previews.is_empty() {
        if let Some(id) = set.thumb_document_id {
            previews.push(id);
        }
        for pack_row in vector_boxed_items(pack.packs.as_ref()) {
            let TlStickerPack::StickerPack(row) = pack_row else {
                continue;
            };
            for id in vector_items(row.documents.as_ref()).iter().copied() {
                if !previews.contains(&id) {
                    previews.push(id);
                }
            }
        }
    }
    Ok(pack_from_set(&set, previews))
}

pub fn get_all_stickers(
    snapshot: &mut Snapshot,
    api_id: i32,
    hash: i64,
) -> Result<StickerCatalogDto, MtprotoError> {
    catalog_from_all(
        api_invoke::invoke_api(snapshot, api_id, MessagesGetAllStickersRequest { hash })?,
        hash,
    )
}

pub fn get_emoji_stickers(
    snapshot: &mut Snapshot,
    api_id: i32,
    hash: i64,
) -> Result<StickerCatalogDto, MtprotoError> {
    catalog_from_all(
        api_invoke::invoke_api(snapshot, api_id, MessagesGetEmojiStickersRequest { hash })?,
        hash,
    )
}

pub fn get_stickers(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &mut MediaIndex,
    emoticon: &str,
    hash: i64,
) -> Result<StickerListDto, MtprotoError> {
    let response: MessagesStickers = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetStickersRequest {
            emoticon: emoticon.to_string(),
            hash,
        },
    )?;
    match response {
        MessagesStickers::MessagesStickersNotModified(_) => Ok(StickerListDto {
            hash,
            not_modified: true,
            document_ids: Vec::new(),
        }),
        MessagesStickers::MessagesStickers(body) => {
            let mut document_ids = Vec::new();
            for boxed in vector_items(body.stickers.as_ref()) {
                let Document::Document(d) = boxed.as_ref() else {
                    continue;
                };
                if !document_ids.contains(&d.id) {
                    document_ids.push(d.id);
                }
                media.insert((d.id, 0), media::media_ref_from_document(d));
            }
            Ok(StickerListDto {
                hash: body.hash,
                not_modified: false,
                document_ids,
            })
        }
    }
}

fn catalog_from_all(
    response: MessagesAllStickers,
    hash: i64,
) -> Result<StickerCatalogDto, MtprotoError> {
    match response {
        MessagesAllStickers::MessagesAllStickersNotModified(_) => Ok(StickerCatalogDto {
            hash,
            not_modified: true,
            sets: Vec::new(),
        }),
        MessagesAllStickers::MessagesAllStickers(body) => {
            let mut sets = Vec::new();
            for set in vector_boxed_items(body.sets.as_ref()) {
                let StickerSet::StickerSet(set) = set else {
                    continue;
                };
                let preview = set.thumb_document_id.into_iter().collect();
                sets.push(pack_from_set(set, preview));
            }
            Ok(StickerCatalogDto {
                hash: body.hash,
                not_modified: false,
                sets,
            })
        }
    }
}

pub(crate) fn pack_from_set(set: &StickerSetConstructor, previews: Vec<i64>) -> StickerPackDto {
    StickerPackDto {
        id: set.id,
        access_hash: set.access_hash,
        title: set.title.clone(),
        short_name: set.short_name.clone(),
        count: set.count,
        is_emoji: set.emojis.is_some(),
        preview_document_ids: previews,
    }
}

fn sticker_set_for_document(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &MediaIndex,
    document_id: i64,
) -> Result<(i64, i64), MtprotoError> {
    if let Some(indexed) = media.values().find(|item| match item.location {
        MediaLocation::Document { id, .. } => id == document_id,
        _ => false,
    }) {
        if let (Some(id), Some(hash)) = (indexed.sticker_set_id, indexed.sticker_set_access_hash) {
            return Ok((id, hash));
        }
    }
    let fetched = media::fetch_custom_emoji(snapshot, api_id, document_id)?;
    match (fetched.sticker_set_id, fetched.sticker_set_access_hash) {
        (Some(id), Some(hash)) => Ok((id, hash)),
        _ => Err(MtprotoError::Message("document has no sticker set".into())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{
        MessagesAllStickersConstructor, MessagesAllStickersNotModifiedConstructor, Vector,
        VectorConstructor,
    };

    fn sample_set() -> StickerSetConstructor {
        StickerSetConstructor {
            flags: 0,
            archived: None,
            official: None,
            masks: None,
            emojis: None,
            text_color: None,
            channel_emoji_status: None,
            creator: None,
            installed_date: None,
            id: 9,
            access_hash: 11,
            title: "Cats".into(),
            short_name: "cats".into(),
            thumbs: None,
            thumb_dc_id: None,
            thumb_version: None,
            thumb_document_id: Some(42),
            count: 3,
            hash: 0,
        }
    }

    #[test]
    fn pack_from_set_keeps_access_hash_and_thumb() {
        let dto = pack_from_set(&sample_set(), vec![42]);
        assert_eq!(dto.id, 9);
        assert_eq!(dto.access_hash, 11);
        assert_eq!(dto.title, "Cats");
        assert_eq!(dto.preview_document_ids, vec![42]);
        assert!(!dto.is_emoji);
    }

    #[test]
    fn catalog_maps_not_modified() {
        let dto = catalog_from_all(
            MessagesAllStickers::MessagesAllStickersNotModified(
                MessagesAllStickersNotModifiedConstructor {},
            ),
            7,
        )
        .expect("catalog");
        assert!(dto.not_modified);
        assert_eq!(dto.hash, 7);
        assert!(dto.sets.is_empty());
    }

    #[test]
    fn catalog_maps_installed_sets() {
        let set = StickerSet::StickerSet(sample_set());
        let dto = catalog_from_all(
            MessagesAllStickers::MessagesAllStickers(MessagesAllStickersConstructor {
                hash: 99,
                sets: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 1,
                    field_1: vec![Box::new(set)],
                })),
            }),
            0,
        )
        .expect("catalog");
        assert!(!dto.not_modified);
        assert_eq!(dto.hash, 99);
        assert_eq!(dto.sets[0].id, 9);
        assert_eq!(dto.sets[0].access_hash, 11);
        assert_eq!(dto.sets[0].preview_document_ids, vec![42]);
    }
}
