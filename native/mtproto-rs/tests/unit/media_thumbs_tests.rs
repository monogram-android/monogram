use super::*;
use crate::client::pipeline_parts;

use crate::client::set_pipeline_parts;
use tellers_mtproto::latest::api::{
    GeoPointConstructor, GeoPointEmptyConstructor, MessageEntity, MessageMediaContactConstructor,
    MessageMediaDiceConstructor, MessageMediaGeoConstructor, MessageMediaGeoLiveConstructor,
    MessageMediaPollConstructor, MessageMediaVenueConstructor, PollAnswerConstructor,
    PollAnswerVotersConstructor, PollConstructor, PollResultsConstructor,
    TextWithEntitiesConstructor,
};

#[test]
fn duration_rounds_seconds() {
    assert_eq!(duration_secs(12.4), 12);
    assert_eq!(duration_secs(12.5), 13);
    assert_eq!(duration_secs(-1.0), 0);
}

#[test]
fn pick_thumb_prefers_m_then_smallest() {
    let sizes = vec![
        ("y".into(), 1280 * 720, None),
        ("m".into(), 320 * 180, None),
        ("s".into(), 100 * 56, None),
    ];
    let picked = pick_thumb_candidate(&sizes).expect("thumb");
    assert_eq!(picked.0, "m");
    let no_named = vec![("y".into(), 800 * 800, None), ("a".into(), 40 * 40, None)];
    assert_eq!(pick_thumb_candidate(&no_named).unwrap().0, "a");
}

#[test]
fn pick_thumb_skips_streaming_letters_without_inline() {
    let streaming = vec![("u".into(), 200 * 200, None), ("v".into(), 40 * 40, None)];
    assert!(pick_thumb_candidate(&streaming).is_none());
    let inline_u = vec![("u".into(), 200 * 200, Some(vec![1, 2, 3, 4]))];
    assert_eq!(pick_thumb_candidate(&inline_u).unwrap().0, "u");
    let stripped_only = vec![("i".into(), 40 * 40, Some(vec![0xFF, 0xD8, 0xFF, 0xD9]))];
    assert_eq!(pick_thumb_candidate(&stripped_only).unwrap().0, "i");
    let stripped_and_m = vec![
        ("i".into(), 40 * 40, Some(vec![0xFF, 0xD8])),
        ("m".into(), 320 * 180, None),
    ];
    assert_eq!(pick_thumb_candidate(&stripped_and_m).unwrap().0, "i");
    assert_eq!(
        pick_inline_or_thumb_candidate(&stripped_and_m).unwrap().0,
        "i"
    );
    assert_eq!(pick_getfile_preview(&stripped_and_m).unwrap().0, "m");
    let s_and_m = vec![("m".into(), 320 * 180, None), ("s".into(), 100 * 56, None)];
    assert_eq!(pick_thumb_candidate(&s_and_m).unwrap().0, "m");
    assert_eq!(pick_inline_or_thumb_candidate(&s_and_m).unwrap().0, "s");
    assert!(is_getfile_thumb_size("m"));
    assert!(!is_getfile_thumb_size("u"));
    assert!(!is_getfile_thumb_size(""));
    assert_eq!(next_getfile_thumb_sizes("m"), &["s", "x", "a"]);
    let loc = MediaLocation::Photo {
        id: 1,
        access_hash: 2,
        file_reference: vec![1],
        thumb_size: "m".into(),
        dc_id: 2,
    };
    let alt = with_thumb_size(&loc, "s").unwrap();
    assert!(matches!(
        alt,
        MediaLocation::Photo { ref thumb_size, .. } if thumb_size == "s"
    ));
    let mut media = MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: "photo:1".into(),
        location: loc.clone(),
        thumb_cache_key: Some("photo:1:thumb".into()),
        thumb_location: Some(loc),
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    remember_thumb_location(&mut media, alt);
    assert!(matches!(
        media.thumb_location,
        Some(MediaLocation::Photo { ref thumb_size, .. }) if thumb_size == "s"
    ));
}

#[test]
fn inline_thumb_jpeg_returns_stripped_bytes_without_getfile() {
    let jpeg = vec![0xFF, 0xD8, 0xFF, 0xD9];
    let media = MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: "photo:stripped".into(),
        location: MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "x".into(),
            dc_id: 2,
        },
        thumb_cache_key: Some("photo:stripped:thumb".into()),
        thumb_location: Some(MediaLocation::Inline {
            bytes: jpeg.clone(),
            extension: "jpg".into(),
        }),
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    assert_eq!(inline_thumb_jpeg(&media).as_deref(), Some(jpeg.as_slice()));
    let getfile_only = MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: "photo:m".into(),
        location: MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "m".into(),
            dc_id: 2,
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    assert!(inline_thumb_jpeg(&getfile_only).is_none());
}

#[test]
fn empty_cached_size_is_skipped_for_getfile() {
    use tellers_mtproto::latest::api::{PhotoCachedSizeConstructor, PhotoSizeConstructor};
    let sizes = vec![
        PhotoSize::PhotoCachedSize(PhotoCachedSizeConstructor {
            type_: "m".into(),
            w: 320,
            h: 180,
            bytes: Vec::new(),
        }),
        PhotoSize::PhotoSize(PhotoSizeConstructor {
            type_: "s".into(),
            w: 100,
            h: 56,
            size: 100 * 56,
        }),
    ];
    assert_eq!(pick_document_thumb_size(&sizes).unwrap().0, "s");
    assert_eq!(pick_thumb_size(&sizes).unwrap().0, "s");
}

#[test]
fn pick_display_prefers_m_for_chat_cells() {
    let sizes = vec![
        ("w".into(), 2560 * 1440, None),
        ("x".into(), 800 * 450, None),
        ("m".into(), 320 * 180, None),
    ];
    let picked = pick_display_candidate(&sizes, Some("i"), "w").expect("display");
    assert_eq!(picked.0, "m");
    let when_thumb_is_m = pick_display_candidate(&sizes, Some("m"), "w").expect("x fallback");
    assert_eq!(when_thumb_is_m.0, "x");
    assert!(pick_display_candidate(&sizes, Some("m"), "x").is_none());
}

#[test]
fn media_for_download_display_uses_m_not_original() {
    let media = MediaRef {
        file_size: Some(25_000_000),
        kind: "photo".into(),
        cache_key: "photo:1".into(),
        location: MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "w".into(),
            dc_id: 2,
        },
        thumb_cache_key: Some("photo:1:thumb".into()),
        thumb_location: Some(MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "m".into(),
            dc_id: 2,
        }),
        display_cache_key: Some("photo:1:display".into()),
        display_location: Some(MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "m".into(),
            dc_id: 2,
        }),
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let display = media_for_download(&media, MediaDownloadKind::Display).expect("display");
    assert!(matches!(
        display.location,
        MediaLocation::Photo { thumb_size, .. } if thumb_size == "m"
    ));
    assert_eq!(display.cache_key, "photo:1:display");
    let mut no_display = media.clone();
    no_display.display_location = None;
    assert!(media_for_download(&no_display, MediaDownloadKind::Display).is_err());
    assert_eq!(
        media_for_download(&media, MediaDownloadKind::Full)
            .unwrap()
            .file_size,
        Some(25_000_000)
    );
    assert_eq!(
        media_for_download(&media, MediaDownloadKind::Thumb)
            .unwrap()
            .file_size,
        None
    );
    assert_eq!(
        media_for_download(&media, MediaDownloadKind::Display)
            .unwrap()
            .file_size,
        None
    );
    let mut old = serde_json::to_value(&media).unwrap();
    old.as_object_mut().unwrap().remove("file_size");
    let restored: MediaRef = serde_json::from_value(old).unwrap();
    assert_eq!(restored.file_size, None);
}

#[test]
fn media_for_download_thumb_skips_non_getfile_size() {
    let mut media = MediaRef {
        file_size: None,
        kind: "video".into(),
        cache_key: "doc:1".into(),
        location: MediaLocation::Document {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: Some("doc:1:thumb".into()),
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
        thumb_location: Some(MediaLocation::Document {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "u".into(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        }),
    };
    assert!(media_for_download(&media, MediaDownloadKind::Thumb).is_err());
    assert!(media_for_download(&media, MediaDownloadKind::Full).is_ok());
    media.thumb_location = None;
    assert!(media_for_download(&media, MediaDownloadKind::Thumb).is_err());
    let photo_only = MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: "photo:1".into(),
        location: MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "m".into(),
            dc_id: 2,
        },
        thumb_cache_key: Some("photo:1".into()),
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let as_thumb =
        media_for_download(&photo_only, MediaDownloadKind::Thumb).expect("photo m is a thumb");
    assert!(matches!(
        as_thumb.location,
        MediaLocation::Photo { thumb_size, .. } if thumb_size == "m"
    ));
    media.thumb_location = Some(MediaLocation::Document {
        id: 1,
        access_hash: 2,
        file_reference: vec![1],
        thumb_size: "m".into(),
        dc_id: 2,
        mime_type: "video/mp4".into(),
    });
    let thumb = media_for_download(&media, MediaDownloadKind::Thumb).expect("m thumb");
    assert!(matches!(
        thumb.location,
        MediaLocation::Document {
            thumb_size,
            ..
        } if thumb_size == "m"
    ));
    assert!(is_file_id_invalid(&MtprotoError::Message(
        "RPC 400: FILE_ID_INVALID".into(),
    )));
    assert!(!is_file_id_invalid(&MtprotoError::Message(
        "FILE_REFERENCE_EXPIRED".into(),
    )));
}

#[test]
fn distinct_thumb_key_only_when_types_differ() {
    assert_eq!(distinct_thumb_key("photo:1", "y", "m"), "photo:1:thumb");
    assert_eq!(distinct_thumb_key("photo:1", "y", "y"), "photo:1");
}

#[test]
fn still_photo_indexes_small_thumb_not_video_size() {
    use tellers_mtproto::latest::api::{
        PhotoConstructor, PhotoSizeConstructor, Vector, VectorConstructor,
    };
    let size = |ty: &str, w: i32| {
        PhotoSize::PhotoSize(PhotoSizeConstructor {
            type_: ty.into(),
            w,
            h: w,
            size: w * w,
        })
    };
    let photo = Photo::Photo(PhotoConstructor {
        flags: 0,
        has_stickers: None,
        id: 88,
        access_hash: 1,
        file_reference: vec![1],
        date: 0,
        sizes: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![Box::new(size("m", 320)), Box::new(size("y", 1280))],
        })),
        video_sizes: None,
        dc_id: 2,
    });
    let indexed = media_ref_from_photo_with_thumbs(&photo, "photo:88".into()).expect("photo");
    assert_eq!(indexed.thumb_cache_key.as_deref(), Some("photo:88:thumb"));
    assert!(matches!(
        indexed.thumb_location,
        Some(MediaLocation::Photo { ref thumb_size, .. }) if thumb_size == "m"
    ));
    assert!(matches!(
        indexed.location,
        MediaLocation::Photo { ref thumb_size, .. } if thumb_size == "y"
    ));
}

#[test]
fn profile_video_sizes_index_as_video_avatar() {
    use tellers_mtproto::latest::api::{
        PhotoConstructor, PhotoSizeConstructor, Vector, VectorConstructor, VideoSize,
        VideoSizeConstructor,
    };
    let size = PhotoSize::PhotoSize(PhotoSizeConstructor {
        type_: "m".into(),
        w: 320,
        h: 320,
        size: 100,
    });
    let video = VideoSize::VideoSize(VideoSizeConstructor {
        flags: 0,
        type_: "u".into(),
        w: 800,
        h: 800,
        size: 4000,
        video_start_ts: None,
    });
    let photo = Photo::Photo(PhotoConstructor {
        flags: PhotoConstructor::VIDEO_SIZES_FLAG,
        has_stickers: None,
        id: 88,
        access_hash: 1,
        file_reference: vec![1],
        date: 0,
        sizes: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![Box::new(size)],
        })),
        video_sizes: Some(Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![Box::new(video)],
        }))),
        dc_id: 2,
    });
    let indexed = media_ref_from_photo(&photo, "avatar:42".into()).expect("video avatar");
    assert_eq!(indexed.kind, "video_avatar");
    assert_eq!(indexed.cache_key, "avatar:42:video");
    assert!(matches!(
        indexed.location,
        MediaLocation::Photo { ref thumb_size, .. } if thumb_size == "u"
    ));
}

#[test]
fn resolved_video_avatar_survives_still_peer_photo() {
    let mut media = MediaIndex::new();
    let video = MediaRef {
        file_size: None,
        kind: "video_avatar".into(),
        cache_key: "avatar:7:video".into(),
        location: MediaLocation::Photo {
            id: 99,
            access_hash: 1,
            file_reference: vec![1],
            thumb_size: "u".into(),
            dc_id: 2,
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    assert_eq!(index_avatar(&mut media, 7, video.clone()), "avatar:7:video");
    let still = media_ref_peer_photo(
        crate::peers::PeerKind::User,
        7,
        1,
        99,
        2,
        "avatar:7".into(),
        false,
    );
    assert_eq!(index_avatar(&mut media, 7, still), "avatar:7:video");
    assert!(is_resolved_video_avatar(media.get(&(7, 0)).unwrap()));
    let placeholder = media_ref_peer_photo(
        crate::peers::PeerKind::User,
        7,
        1,
        99,
        2,
        "avatar:7:video".into(),
        true,
    );
    assert!(needs_video_avatar_upgrade(&placeholder));
    assert_eq!(index_avatar(&mut media, 7, placeholder), "avatar:7:video");
    assert!(is_resolved_video_avatar(media.get(&(7, 0)).unwrap()));
}
