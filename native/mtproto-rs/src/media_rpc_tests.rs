use super::*;
use crate::client_mgr::pipeline_parts;
#[allow(unused_imports)]
use crate::client_mgr::set_pipeline_parts;
use tellers_mtproto::latest::api::{
    GeoPointConstructor, GeoPointEmptyConstructor, MessageEntity, MessageMediaContactConstructor,
    MessageMediaDiceConstructor, MessageMediaGeoConstructor, MessageMediaGeoLiveConstructor,
    MessageMediaPollConstructor, MessageMediaVenueConstructor, PollAnswerConstructor,
    PollAnswerVotersConstructor, PollConstructor, PollResultsConstructor,
    TextWithEntitiesConstructor,
};

#[test]
fn stale_staging_preserves_existing_destination_and_removes_partial_bytes() {
    let dest = std::env::temp_dir().join(format!("monogram-staging-{}", std::process::id()));
    fs::write(&dest, b"previous").unwrap();
    let staged = StagedDownload::new(&dest).unwrap();
    let path = staged.path().to_owned();
    fs::write(&path, b"uncommitted").unwrap();
    drop(staged);
    assert!(!path.exists());
    assert_eq!(fs::read(&dest).unwrap(), b"previous");
    fs::remove_file(dest).unwrap();
}

#[test]
fn cancel_before_publication_cleans_staging_without_publishing() {
    let dest = std::env::temp_dir().join(format!("monogram-publish-cancel-{}", std::process::id()));
    let staged = StagedDownload::new(&dest).unwrap();
    let path = staged.path().to_owned();
    fs::write(&path, b"complete").unwrap();
    fs::write(cancel_marker(&dest), []).unwrap();
    assert!(staged.publish(&dest).is_err());
    assert!(!path.exists());
    assert!(!dest.exists());
    fs::remove_file(cancel_marker(&dest)).unwrap();
}

#[test]
fn successful_staging_publishes_complete_file() {
    let dest = std::env::temp_dir().join(format!("monogram-publish-{}", std::process::id()));
    let staged = StagedDownload::new(&dest).unwrap();
    let path = staged.path().to_owned();
    fs::write(&path, b"complete").unwrap();
    staged.publish(&dest).unwrap();
    assert!(!path.exists());
    assert_eq!(fs::read(&dest).unwrap(), b"complete");
    fs::remove_file(dest).unwrap();
}

#[test]
fn chunk_is_telegram_max_part() {
    assert_eq!(CHUNK, 128 * 1024);
    assert_eq!(CHUNK % 4096, 0);
    assert_eq!(CHUNK % 1024, 0);
}

#[test]
fn download_dc_uses_location_or_home() {
    let photo = MediaLocation::Photo {
        id: 1,
        access_hash: 2,
        file_reference: vec![1],
        thumb_size: "x".into(),
        dc_id: 4,
    };
    let home_photo = MediaLocation::Photo {
        id: 1,
        access_hash: 2,
        file_reference: vec![1],
        thumb_size: "x".into(),
        dc_id: 2,
    };
    let unset = MediaLocation::Photo {
        id: 1,
        access_hash: 2,
        file_reference: vec![1],
        thumb_size: "x".into(),
        dc_id: 0,
    };
    assert_eq!(download_dc(2, &photo), 4);
    assert_eq!(download_dc(2, &home_photo), 2);
    assert_eq!(download_dc(2, &unset), 2);
    assert_eq!(
        download_dc(
            2,
            &MediaLocation::Inline {
                bytes: vec![1],
                extension: "jpg".into()
            }
        ),
        2
    );
}

#[test]
fn failed_chunked_download_removes_partial_file() {
    let path = std::env::temp_dir().join(format!(
        "monogram-download-partial-{}.bin",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    let err = write_file_or_cleanup(&path, |file| {
        file.write_all(b"hello")
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        Err(MtprotoError::Message("boom".into()))
    });
    assert!(err.is_err());
    assert!(!path.exists());
}

#[test]
fn cancel_marker_aborts_partial_write() {
    let path = std::env::temp_dir().join(format!(
        "monogram-download-cancel-{}.bin",
        std::process::id()
    ));
    let marker = cancel_marker(&path);
    let _ = fs::remove_file(&path);
    let _ = fs::remove_file(&marker);
    let err = write_file_or_cleanup(&path, |file| {
        file.write_all(b"hello")
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        fs::write(&marker, b"").unwrap();
        if download_cancelled(&path) {
            return Err(MtprotoError::Message("cancelled".into()));
        }
        Ok(())
    });
    assert!(err.is_err());
    assert!(!path.exists());
    let _ = fs::remove_file(&marker);
}

#[test]
fn successful_chunked_download_keeps_file() {
    let path =
        std::env::temp_dir().join(format!("monogram-download-ok-{}.bin", std::process::id()));
    let _ = fs::remove_file(&path);
    write_file_or_cleanup(&path, |file| {
        file.write_all(b"hello")
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        Ok(())
    })
    .unwrap();
    assert_eq!(fs::read(&path).unwrap(), b"hello");
    let _ = fs::remove_file(&path);
}

#[test]
fn file_reference_error_matches_expired_and_invalid() {
    assert!(is_file_reference_error(&MtprotoError::Message(
        "RPC 400: FILE_REFERENCE_EXPIRED".into(),
    )));
    assert!(is_file_reference_error(&MtprotoError::Message(
        "FILE_REFERENCE_INVALID".into(),
    )));
    assert!(is_file_reference_error(&MtprotoError::Message(
        "FILE_REFERENCE_3_EXPIRED".into(),
    )));
    assert!(!is_file_reference_error(&MtprotoError::Message(
        "RPC timeout recv=2880".into(),
    )));
    assert!(!is_file_reference_error(&MtprotoError::UnknownClient));
}

#[test]
fn peer_photo_location_token_tracks_access_hash() {
    let peer = MediaLocation::PeerPhoto {
        peer_kind: crate::peers::PeerKind::User,
        peer_id: 1,
        access_hash: 0,
        photo_id: 1,
        big: true,
        dc_id: 2,
    };
    let mut updated = peer.clone();
    if let MediaLocation::PeerPhoto { access_hash, .. } = &mut updated {
        *access_hash = 9;
    }
    assert_ne!(location_token(&peer), location_token(&updated));
}

#[test]
fn fill_zero_peer_photo_hashes_from_full_peer() {
    let mut media = MediaIndex::new();
    media.insert(
        (-1_000_000_000_042, 0),
        media_ref_peer_photo(
            crate::peers::PeerKind::Channel,
            42,
            0,
            9,
            2,
            "avatar:-1000000000042".into(),
            false,
        ),
    );
    let mut peers = HashMap::new();
    peers.insert(
        -1_000_000_000_042,
        crate::peers::CachedPeer {
            kind: crate::peers::PeerKind::Channel,
            id: 42,
            access_hash: 77,
            min_hash: false,
        },
    );
    fill_zero_peer_photo_hashes(&mut media, &peers);
    match media.get(&(-1_000_000_000_042, 0)).unwrap().location {
        MediaLocation::PeerPhoto { access_hash, .. } => assert_eq!(access_hash, 77),
        _ => panic!("peer photo"),
    }
}

#[test]
fn classify_document_kinds() {
    assert_eq!(
        classify_document("application/x-tgsticker", true, true, false),
        "sticker_animated"
    );
    assert_eq!(
        classify_document("image/webp", true, false, false),
        "sticker"
    );
    assert_eq!(classify_document("video/mp4", false, true, false), "gif");
    assert_eq!(classify_document("video/mp4", false, false, true), "video");
    assert_eq!(classify_document("image/gif", false, false, false), "gif");
    assert_eq!(
        classify_document("image/jpeg", false, false, false),
        "document"
    );
    assert_eq!(
        classify_document("image/png", false, false, false),
        "document"
    );
    assert_eq!(
        classify_document("image/webp", false, false, false),
        "document"
    );
    assert_eq!(
        classify_document("application/pdf", false, false, false),
        "document"
    );
}

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
}

#[test]
fn shared_transport_fetches_bounded_parts_and_removes_failed_download() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "photo".into(),
        cache_key: "test".into(),
        location: MediaLocation::Photo {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: "x".into(),
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
    let path = std::env::temp_dir().join(format!("monogram-shared-parts-{}", std::process::id()));
    for fail_second in [false, true] {
        let mut offsets = Vec::new();
        let result = download_media_with_fetch(2, &media, &path, &path, |request| {
            assert_eq!(request.limit, CHUNK);
            offsets.push(request.offset);
            if fail_second && request.offset > 0 {
                return Err(MtprotoError::Message("cancelled".into()));
            }
            Ok(UploadFile::UploadFile(UploadFileConstructor {
                type_: Box::new(StorageFileType::StorageFileUnknown(
                    StorageFileUnknownConstructor {},
                )),
                mtime: 0,
                bytes: vec![
                    7;
                    if request.offset == 0 {
                        CHUNK as usize
                    } else {
                        3
                    }
                ],
            }))
        });
        assert_eq!(offsets, vec![0, CHUNK as i64]);
        if fail_second {
            assert!(result.is_err());
            assert!(!path.exists());
        } else {
            result.unwrap();
            assert_eq!(std::fs::metadata(&path).unwrap().len(), CHUNK as u64 + 3);
            std::fs::remove_file(&path).unwrap();
        }
    }
    assert!(
        download_media_with_fetch(4, &media, &path, &path, |_| {
            panic!("A foreign-DC file must not send requests on the home transport")
        })
        .is_err()
    );
}

#[test]
fn batched_range_requests_parts_together_and_writes_each_at_its_offset() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "batched-range".into(),
        location: MediaLocation::Document {
            id: 3,
            access_hash: 4,
            file_reference: vec![2],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-batched-{}", std::process::id()));
    let file_size = (CHUNK as i64) * 2 + 5;
    let mut batches: Vec<Vec<i64>> = Vec::new();
    let result = download_media_range_batched(
        2,
        &media,
        &path,
        &path,
        None,
        pipeline_parts(),
        |_init, requests| {
            // Every request in the batch is handed over before any response is
            // produced: that is the in-session pipelining contract.
            let offsets: Vec<i64> = requests.iter().map(|r| r.offset).collect();
            assert!(requests.iter().all(|r| r.limit == CHUNK));
            batches.push(offsets.clone());
            Ok(offsets
                .into_iter()
                .map(|offset| {
                    let remaining = (file_size - offset).max(0) as usize;
                    let len = remaining.min(CHUNK as usize);
                    Ok(UploadFile::UploadFile(UploadFileConstructor {
                        type_: Box::new(StorageFileType::StorageFileUnknown(
                            StorageFileUnknownConstructor {},
                        )),
                        mtime: 0,
                        bytes: vec![(offset % 251) as u8; len],
                    }))
                })
                .collect())
        },
    );
    assert!(result.is_ok(), "batched download failed: {result:?}");
    assert_eq!(
        batches[0].len(),
        pipeline_parts(),
        "first batch must pipeline parts"
    );
    assert_eq!(batches[0][0], 0);
    let written = fs::read(&path).expect("file");
    assert_eq!(written.len() as i64, file_size);
    // Each part landed at its own offset.
    assert_eq!(written[0], 0);
    assert_eq!(written[CHUNK as usize], (CHUNK as i64 % 251) as u8);
    assert_eq!(written[2 * CHUNK as usize], (2 * CHUNK as i64 % 251) as u8);
    fs::remove_file(&path).unwrap();
}

#[test]
fn batched_range_reports_a_failed_part_and_removes_the_partial_file() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "batched-fail".into(),
        location: MediaLocation::Document {
            id: 5,
            access_hash: 6,
            file_reference: vec![3],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-batched-fail-{}", std::process::id()));
    let result = download_media_range_batched(
        2,
        &media,
        &path,
        &path,
        None,
        pipeline_parts(),
        |_init, requests| {
            Ok(requests
                .into_iter()
                .map(|request| {
                    if request.offset == 0 {
                        Ok(UploadFile::UploadFile(UploadFileConstructor {
                            type_: Box::new(StorageFileType::StorageFileUnknown(
                                StorageFileUnknownConstructor {},
                            )),
                            mtime: 0,
                            bytes: vec![1; CHUNK as usize],
                        }))
                    } else {
                        Err(MtprotoError::Message(
                            "RPC 400: FILE_REFERENCE_EXPIRED".into(),
                        ))
                    }
                })
                .collect())
        },
    );
    assert!(result.is_err(), "a failed part must fail the download");
    assert!(is_file_reference_error(&result.unwrap_err()));
    assert!(
        !path.exists(),
        "a failed batch must not leave a partial file"
    );
}

#[test]
fn media_range_fetches_one_aligned_part_and_rejects_invalid_bounds() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "range-test".into(),
        location: MediaLocation::Document {
            id: 1,
            access_hash: 2,
            file_reference: vec![1],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-range-{}", std::process::id()));
    for size in [0, 3, CHUNK as usize, CHUNK as usize + 1] {
        let mut calls = 0;
        let result = download_media_range_with_fetch(
            2,
            &media,
            &path,
            &path,
            Some(2 * i64::from(CHUNK)),
            |request| {
                calls += 1;
                assert_eq!(request.offset, 2 * i64::from(CHUNK));
                assert_eq!(request.limit, CHUNK);
                assert!(request.cdn_supported.is_some());
                Ok(UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![7; size],
                }))
            },
        );
        assert_eq!(calls, 1);
        if size > CHUNK as usize {
            assert!(result.is_err());
            assert!(!path.exists());
        } else {
            result.unwrap();
            assert_eq!(fs::metadata(&path).unwrap().len(), size as u64);
            fs::remove_file(&path).unwrap();
        }
    }
    for offset in [-1, 1, i64::from(CHUNK) + 1] {
        assert!(
            download_media_range_with_fetch(2, &media, &path, &path, Some(offset), |_| {
                panic!("invalid range must not reach transport")
            })
            .is_err()
        );
    }
    assert!(
        download_media_range_with_fetch(4, &media, &path, &path, Some(0), |_| {
            panic!("foreign DC must migrate before fetching")
        })
        .is_err()
    );
}

#[test]
fn media_for_download_thumb_skips_non_getfile_size() {
    let mut media = MediaRef {
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

fn plain(text: &str) -> Box<RichText> {
    Box::new(RichText::TextPlain(
        tellers_mtproto::latest::api::TextPlainConstructor {
            text: text.to_string(),
        },
    ))
}

fn boxed_vector<T>(items: Vec<T>) -> Vector<Box<T>> {
    Vector::Vector(tellers_mtproto::latest::api::VectorConstructor {
        field_0: tellers_mtproto::latest::api::VectorConstructor::<Box<T>>::ID,
        field_1: items.into_iter().map(Box::new).collect(),
    })
}

fn paragraph(text: &str) -> PageBlock {
    PageBlock::PageBlockParagraph(
        tellers_mtproto::latest::api::PageBlockParagraphConstructor { text: plain(text) },
    )
}

#[test]
fn nested_blockquote_blocks_overlap() {
    let inner = PageBlock::PageBlockBlockquoteBlocks(
        tellers_mtproto::latest::api::PageBlockBlockquoteBlocksConstructor {
            blocks: Box::new(boxed_vector(vec![paragraph("inner")])),
            caption: Box::new(RichText::TextEmpty(
                tellers_mtproto::latest::api::TextEmptyConstructor {},
            )),
        },
    );
    let outer = PageBlock::PageBlockBlockquoteBlocks(
        tellers_mtproto::latest::api::PageBlockBlockquoteBlocksConstructor {
            blocks: Box::new(boxed_vector(vec![paragraph("outer"), inner])),
            caption: Box::new(RichText::TextEmpty(
                tellers_mtproto::latest::api::TextEmptyConstructor {},
            )),
        },
    );
    let formatted = page_blocks_formatted(std::iter::once(&outer));
    assert_eq!(formatted.text, "outer\ninner");
    let quotes: Vec<_> = formatted
        .entities
        .iter()
        .filter(|e| e.kind == "blockquote")
        .collect();
    assert_eq!(quotes.len(), 2);
    assert_eq!(quotes[0].offset, utf16_len("outer\n"));
    assert_eq!(quotes[0].length, utf16_len("inner"));
    assert_eq!(quotes[1].offset, 0);
    assert_eq!(quotes[1].length, utf16_len("outer\ninner"));
}

#[test]
fn photo_caption_is_kept() {
    let block =
        PageBlock::PageBlockPhoto(tellers_mtproto::latest::api::PageBlockPhotoConstructor {
            flags: 0,
            spoiler: None,
            photo_id: 1,
            caption: Box::new(PageCaption::PageCaption(
                tellers_mtproto::latest::api::PageCaptionConstructor {
                    text: plain("Image title"),
                    credit: Box::new(RichText::TextEmpty(
                        tellers_mtproto::latest::api::TextEmptyConstructor {},
                    )),
                },
            )),
            url: None,
            webpage_id: None,
        });
    let formatted = page_blocks_formatted(std::iter::once(&block));
    assert_eq!(formatted.text, "Image title");
    let photo = Photo::Photo(tellers_mtproto::latest::api::PhotoConstructor {
        flags: 0,
        has_stickers: None,
        id: 1,
        access_hash: 1,
        file_reference: vec![1],
        date: 0,
        sizes: Box::new(boxed_vector(vec![PhotoSize::PhotoSize(
            tellers_mtproto::latest::api::PhotoSizeConstructor {
                type_: "y".into(),
                w: 800,
                h: 450,
                size: 100,
            },
        )])),
        video_sizes: None,
        dc_id: 2,
    });
    let with_photo = page_blocks_formatted_media(std::iter::once(&block), &[photo]);
    assert!(with_photo.text.contains('\u{FFFC}'));
    assert!(with_photo.text.contains("Image title"));
    let photo_entity = with_photo
        .entities
        .iter()
        .find(|entity| entity.kind == "photo")
        .expect("photo entity");
    assert_eq!(photo_entity.url.as_deref(), Some("photo:1:800x450"));
}

fn text(value: &str) -> Box<TextWithEntities> {
    Box::new(TextWithEntities::TextWithEntities(
        TextWithEntitiesConstructor {
            text: value.to_owned(),
            entities: vector::<MessageEntity>(&[]),
        },
    ))
}

fn vector<T>(items: &[T]) -> Box<Vector<Box<T>>>
where
    T: Clone,
{
    Box::new(Vector::Vector(VectorConstructor {
        field_0: items.len() as u32,
        field_1: items.iter().cloned().map(Box::new).collect(),
    }))
}

fn geo(lat: f64, long: f64) -> Box<GeoPoint> {
    Box::new(GeoPoint::GeoPoint(GeoPointConstructor {
        flags: 0,
        long,
        lat,
        access_hash: 0,
        accuracy_radius: None,
    }))
}

#[test]
fn dice_payload_carries_value_and_emoji() {
    let json = dice_to_json(&MessageMediaDiceConstructor {
        flags: 0,
        value: 4,
        emoticon: "🎲".to_owned(),
        game_outcome: None,
    });
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["v"], 4);
    assert_eq!(parsed["e"], "🎲");
}

#[test]
fn contact_payload_carries_names_phone_and_user() {
    let json = contact_to_json(&MessageMediaContactConstructor {
        phone_number: "+79000000000".to_owned(),
        first_name: "Ada".to_owned(),
        last_name: "Lovelace".to_owned(),
        vcard: "BEGIN:VCARD".to_owned(),
        user_id: 42,
    });
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["p"], "+79000000000");
    assert_eq!(parsed["f"], "Ada");
    assert_eq!(parsed["l"], "Lovelace");
    assert_eq!(parsed["u"], 42);
}

#[test]
fn venue_payload_carries_coordinates_and_place_details() {
    let json = venue_to_json(&MessageMediaVenueConstructor {
        geo: geo(55.75, 37.61),
        title: "Red Square".to_owned(),
        address: "Moscow".to_owned(),
        provider: "foursquare".to_owned(),
        venue_id: "abc".to_owned(),
        venue_type: "square".to_owned(),
    })
    .expect("venue payload");
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["lat"], 55.75);
    assert_eq!(parsed["long"], 37.61);
    assert_eq!(parsed["t"], "Red Square");
    assert_eq!(parsed["y"], "square");
}

#[test]
fn live_geo_payload_marks_live_and_keeps_heading() {
    let json = geo_live_to_json(&MessageMediaGeoLiveConstructor {
        flags: 0,
        geo: geo(10.0, 20.0),
        heading: Some(90),
        period: 900,
        proximity_notification_radius: None,
    })
    .expect("live payload");
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["live"], 1);
    assert_eq!(parsed["p"], 900);
    assert_eq!(parsed["h"], 90);
}

#[test]
fn empty_geo_point_has_no_payload() {
    assert!(
        geo_to_json(&MessageMediaGeoConstructor {
            geo: Box::new(GeoPoint::GeoPointEmpty(GeoPointEmptyConstructor {})),
        })
        .is_none()
    );
}

#[test]
fn poll_payload_marks_answers_voters_and_quiz() {
    let answers = vector(&[
        PollAnswer::PollAnswer(PollAnswerConstructor {
            flags: 0,
            text: text("yes"),
            option: vec![0x01, 0xa0],
            media: None,
            added_by: None,
            date: None,
        }),
        PollAnswer::PollAnswer(PollAnswerConstructor {
            flags: 0,
            text: text("no"),
            option: b"b".to_vec(),
            media: None,
            added_by: None,
            date: None,
        }),
    ]);
    let voters = vector(&[PollAnswerVoters::PollAnswerVoters(
        PollAnswerVotersConstructor {
            flags: 0,
            chosen: Some(Box::new(True::True(TrueConstructor {}))),
            correct: Some(Box::new(True::True(TrueConstructor {}))),
            option: vec![0x01, 0xa0],
            voters: Some(7),
            recent_voters: None,
        },
    )]);
    let json = poll_to_json(&MessageMediaPollConstructor {
        flags: 0,
        poll: Box::new(Poll::Poll(PollConstructor {
            id: 5,
            flags: 0,
            closed: None,
            public_voters: None,
            multiple_choice: None,
            quiz: Some(Box::new(True::True(TrueConstructor {}))),
            open_answers: None,
            revoting_disabled: None,
            shuffle_answers: None,
            hide_results_until_close: None,
            creator: None,
            subscribers_only: None,
            question: text("ready?"),
            answers,
            close_period: None,
            close_date: None,
            countries_iso2: None,
            hash: 0,
        })),
        results: Box::new(PollResults::PollResults(PollResultsConstructor {
            flags: 0,
            min: None,
            has_unread_votes: None,
            can_view_stats: None,
            results: Some(voters),
            total_voters: Some(7),
            recent_voters: None,
            solution: Some("because".to_owned()),
            solution_entities: None,
            solution_media: None,
        })),
        attached_media: None,
    });
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["q"], "ready?");
    assert_eq!(parsed["z"], 1);
    assert_eq!(parsed["n"], 7);
    assert_eq!(parsed["s"], "because");
    let answers = parsed["a"].as_array().expect("answers");
    assert_eq!(answers.len(), 2);
    assert_eq!(answers[0]["t"], "yes");
    assert_eq!(answers[0]["c"], 1);
    assert_eq!(answers[0]["k"], 1);
    assert_eq!(answers[0]["v"], 7);
    assert_eq!(answers[0]["o"], "01a0");
    assert_eq!(answers[1]["c"], 0);
    assert_eq!(answers[1]["v"], 0);
}

#[test]
fn default_pipeline_parts_is_twelve() {
    use crate::client_mgr::{DEFAULT_PIPELINE_PARTS, pipeline_parts, set_pipeline_parts};
    let previous = pipeline_parts();
    set_pipeline_parts(DEFAULT_PIPELINE_PARTS);
    assert_eq!(pipeline_parts(), 12);
    set_pipeline_parts(previous);
}

#[test]
fn limit_invalid_falls_back_to_128kib() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let previous_chunk = chunk_size();
    set_chunk_size(512 * 1024);
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "limit-invalid".into(),
        location: MediaLocation::Document {
            id: 9,
            access_hash: 10,
            file_reference: vec![4],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-limit-invalid-{}", std::process::id()));
    let mut limits: Vec<i32> = Vec::new();
    let file_size = 200i64;
    let result =
        download_media_range_batched(2, &media, &path, &path, None, 2, |_init, requests| {
            limits.push(requests[0].limit);
            if requests[0].limit > DEFAULT_CHUNK {
                return Err(MtprotoError::Message("RPC 400: LIMIT_INVALID".into()));
            }
            Ok(requests
                .into_iter()
                .map(|request| {
                    let remaining = (file_size - request.offset).max(0) as usize;
                    let len = remaining.min(request.limit as usize);
                    Ok(UploadFile::UploadFile(UploadFileConstructor {
                        type_: Box::new(StorageFileType::StorageFileUnknown(
                            StorageFileUnknownConstructor {},
                        )),
                        mtime: 0,
                        bytes: vec![7u8; len],
                    }))
                })
                .collect())
        });
    set_chunk_size(previous_chunk);
    assert!(result.is_ok(), "fallback download failed: {result:?}");
    assert_eq!(limits, vec![512 * 1024, DEFAULT_CHUNK]);
    assert_eq!(fs::read(&path).unwrap(), vec![7u8; file_size as usize]);
    fs::remove_file(&path).unwrap();
}

#[test]
fn batched_streaming_writes_a_part_before_the_batch_returns() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let previous = pipeline_parts();
    set_pipeline_parts(4);
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "stream-write".into(),
        location: MediaLocation::Document {
            id: 11,
            access_hash: 12,
            file_reference: vec![5],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-stream-write-{}", std::process::id()));
    let mut lengths_during_batch: Vec<u64> = Vec::new();
    let mut batches = 0usize;
    let file_size = CHUNK as i64 * 3;
    let result = download_media_range_batched_streaming(
        2,
        &media,
        &path,
        &path,
        None,
        3,
        |_init, requests, on_chunk| {
            batches += 1;
            let mut results = Vec::with_capacity(requests.len());
            for (index, request) in requests.iter().enumerate() {
                let remaining = (file_size - request.offset).max(0) as usize;
                let len = remaining.min(request.limit as usize);
                let file = UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![(request.offset % 251) as u8; len],
                });
                let _ = on_chunk(index, Ok(file.clone()));
                if index == 0 {
                    lengths_during_batch.push(fs::metadata(&path).map(|m| m.len()).unwrap_or(0));
                }
                results.push(Ok(file));
            }
            Ok(results)
        },
    );
    set_pipeline_parts(previous);
    assert!(result.is_ok(), "streaming download failed: {result:?}");
    assert!(
        lengths_during_batch.iter().any(|len| *len > 0),
        "part must be written before fetch_batch returns: {lengths_during_batch:?}"
    );
    assert!(batches >= 1);
    fs::remove_file(&path).ok();
}

#[test]
fn progress_counts_each_offset_once_when_on_chunk_and_batch_both_deliver() {
    use std::sync::{Arc, Mutex};
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let seen = Arc::new(Mutex::new(Vec::<i64>::new()));
    let seen_cb = seen.clone();
    set_progress_callback(Some(Arc::new(move |_, downloaded, _| {
        seen_cb.lock().unwrap().push(downloaded);
    })));
    let media = MediaRef {
        kind: "document".into(),
        cache_key: "progress-once".into(),
        location: MediaLocation::Document {
            id: 41,
            access_hash: 42,
            file_reference: vec![8],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "application/pdf".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-progress-once-{}", std::process::id()));
    let file_size = CHUNK as i64 * 4;
    let result = download_media_range_batched_streaming(
        2,
        &media,
        &path,
        &path,
        None,
        2,
        |_init, requests, on_chunk| {
            let mut pending = requests;
            let mut results = Vec::new();
            let mut index = 0usize;
            while index < pending.len() {
                let request = pending[index].clone();
                let remaining = (file_size - request.offset).max(0) as usize;
                let len = remaining.min(request.limit as usize);
                let file = UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![4u8; len],
                });
                if let Some(next) = on_chunk(index, Ok(file.clone())) {
                    pending.push(next);
                }
                results.push(Ok(file));
                index += 1;
            }
            Ok(results)
        },
    );
    set_progress_callback(None);
    assert!(result.is_ok(), "{result:?}");
    let values = seen.lock().unwrap().clone();
    assert!(!values.is_empty(), "progress must be pushed");
    let last = *values.last().unwrap();
    assert_eq!(last, file_size, "progress doubled or short: {values:?}");
    assert!(
        values.windows(2).all(|w| w[1] >= w[0]),
        "progress went backwards: {values:?}"
    );
    fs::remove_file(&path).ok();
}

#[test]
fn completed_part_refills_inflight_window_without_waiting_for_the_batch() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let previous = pipeline_parts();
    set_pipeline_parts(2);
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "slide-refill".into(),
        location: MediaLocation::Document {
            id: 31,
            access_hash: 32,
            file_reference: vec![7],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-slide-refill-{}", std::process::id()));
    let file_size = CHUNK as i64 * 5;
    let mut batches = 0usize;
    let mut max_inflight = 0usize;
    let mut seen_offsets: Vec<i64> = Vec::new();
    let result = download_media_range_batched_streaming(
        2,
        &media,
        &path,
        &path,
        None,
        2,
        |_init, requests, on_chunk| {
            batches += 1;
            let mut pending = requests;
            let mut results = Vec::new();
            let mut index = 0usize;
            while index < pending.len() {
                max_inflight = max_inflight.max(pending.len() - index);
                let request = pending[index].clone();
                seen_offsets.push(request.offset);
                let remaining = (file_size - request.offset).max(0) as usize;
                let len = remaining.min(request.limit as usize);
                let file = UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![3u8; len],
                });
                if let Some(next) = on_chunk(index, Ok(file.clone())) {
                    pending.push(next);
                }
                results.push(Ok(file));
                index += 1;
            }
            Ok(results)
        },
    );
    set_pipeline_parts(previous);
    assert!(result.is_ok(), "sliding download failed: {result:?}");
    assert_eq!(batches, 1, "refill must stay inside the first window call");
    assert!(
        seen_offsets.len() >= 5,
        "expected whole file via refill: {seen_offsets:?}"
    );
    assert!(
        max_inflight <= 2,
        "window must stay at parts_in_flight: {max_inflight}"
    );
    assert_eq!(fs::read(&path).unwrap().len(), file_size as usize);
    fs::remove_file(&path).ok();
}

#[test]
fn one_mib_chunks_are_rejected() {
    let previous = chunk_size();
    set_chunk_size(1024 * 1024);
    assert_eq!(chunk_size(), DEFAULT_CHUNK);
    set_chunk_size(512 * 1024);
    assert_eq!(chunk_size(), 512 * 1024);
    set_chunk_size(previous);
}

#[test]
fn flood_wait_retries_then_succeeds() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "flood-retry".into(),
        location: MediaLocation::Document {
            id: 21,
            access_hash: 22,
            file_reference: vec![6],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-flood-{}", std::process::id()));
    let mut calls = 0u8;
    let result =
        download_media_range_batched(2, &media, &path, &path, None, 1, |_init, requests| {
            calls += 1;
            if calls == 1 {
                return Err(MtprotoError::Message("RPC 420: FLOOD_WAIT_2".into()));
            }
            Ok(requests
                .into_iter()
                .map(|request| {
                    let remaining = (20i64 - request.offset).max(0) as usize;
                    Ok(UploadFile::UploadFile(UploadFileConstructor {
                        type_: Box::new(StorageFileType::StorageFileUnknown(
                            StorageFileUnknownConstructor {},
                        )),
                        mtime: 0,
                        bytes: vec![9u8; remaining.min(request.limit as usize)],
                    }))
                })
                .collect())
        });
    assert!(result.is_ok(), "{result:?}");
    assert!(calls >= 2);
    fs::remove_file(&path).ok();
}

#[test]
fn rpc_timeout_retries_from_missing_offset_without_dropping_written_parts() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "document".into(),
        cache_key: "timeout-retry".into(),
        location: MediaLocation::Document {
            id: 51,
            access_hash: 52,
            file_reference: vec![9],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "application/pdf".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-timeout-{}", std::process::id()));
    let file_size = CHUNK as i64 * 3;
    let mut calls = 0u8;
    let result = download_media_range_batched_streaming(
        2,
        &media,
        &path,
        &path,
        None,
        2,
        |_init, requests, on_chunk| {
            calls += 1;
            if calls == 1 {
                let request = requests[0].clone();
                let file = UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![1u8; request.limit as usize],
                });
                let _ = on_chunk(0, Ok(file.clone()));
                return Err(MtprotoError::Message(
                    "RPC timeout recv=95973128 needed=524399 available=513559 prefix=524395 last_ctor=0xf35c6d01".into(),
                ));
            }
            let mut results = Vec::new();
            for (index, request) in requests.iter().enumerate() {
                let remaining = (file_size - request.offset).max(0) as usize;
                let len = remaining.min(request.limit as usize);
                let file = UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![2u8; len],
                });
                let _ = on_chunk(index, Ok(file.clone()));
                results.push(Ok(file));
            }
            Ok(results)
        },
    );
    assert!(result.is_ok(), "{result:?}");
    assert!(calls >= 2, "timeout must retry the window: calls={calls}");
    assert_eq!(fs::read(&path).unwrap().len(), file_size as usize);
    fs::remove_file(&path).ok();
}

#[test]
fn capped_refill_releases_fetch_batch_before_eof() {
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        kind: "video".into(),
        cache_key: "window-release".into(),
        location: MediaLocation::Document {
            id: 61,
            access_hash: 62,
            file_reference: vec![10],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "video/mp4".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    };
    let path = std::env::temp_dir().join(format!("monogram-window-{}", std::process::id()));
    let file_size = CHUNK as i64 * 6;
    let mut batches = 0usize;
    let result = download_media_range_batched_streaming_capped(
        2,
        &media,
        &path,
        &path,
        None,
        2,
        Some(2),
        |_init, requests, on_chunk| {
            batches += 1;
            let mut pending = requests;
            let mut results = Vec::new();
            let mut index = 0usize;
            while index < pending.len() {
                let request = pending[index].clone();
                let remaining = (file_size - request.offset).max(0) as usize;
                let len = remaining.min(request.limit as usize);
                let file = UploadFile::UploadFile(UploadFileConstructor {
                    type_: Box::new(StorageFileType::StorageFileUnknown(
                        StorageFileUnknownConstructor {},
                    )),
                    mtime: 0,
                    bytes: vec![5u8; len],
                });
                if let Some(next) = on_chunk(index, Ok(file.clone())) {
                    pending.push(next);
                }
                results.push(Ok(file));
                index += 1;
            }
            Ok(results)
        },
    );
    assert!(result.is_ok(), "{result:?}");
    assert!(
        batches >= 2,
        "home-DC window must return fetch_batch before EOF: batches={batches}"
    );
    assert_eq!(fs::read(&path).unwrap().len(), file_size as usize);
    fs::remove_file(&path).ok();
}
