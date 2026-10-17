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
