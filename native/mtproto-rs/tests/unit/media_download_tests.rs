use super::*;
use crate::client::pipeline_parts;

fn hold_media_globals() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}
#[allow(unused_imports)]
use crate::client::set_pipeline_parts;
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
    let ranges = ranges_path(&path);
    fs::write(&ranges, b"ranges").unwrap();
    assert!(staged.publish(&dest).is_err());
    assert!(path.exists());
    assert!(ranges.exists());
    assert!(!dest.exists());
    let _ = fs::remove_file(&path);
    let _ = fs::remove_file(&ranges);
    fs::remove_file(cancel_marker(&dest)).unwrap();
}

#[test]
fn successful_staging_publishes_complete_file() {
    let dest = std::env::temp_dir().join(format!("monogram-publish-{}", std::process::id()));
    let staged = StagedDownload::new(&dest).unwrap();
    let path = staged.path().to_owned();
    fs::write(&path, b"complete").unwrap();
    let ranges = ranges_path(&path);
    fs::write(&ranges, b"stale").unwrap();
    staged.publish(&dest).unwrap();
    assert!(!path.exists());
    assert!(!ranges.exists());
    assert_eq!(fs::read(&dest).unwrap(), b"complete");
    fs::remove_file(dest).unwrap();
}

#[test]
fn resume_reopens_the_longest_part_and_cancel_deletes_it() {
    let dest = std::env::temp_dir().join(format!("monogram-resume-{}", std::process::id()));
    let short = StagedDownload::new(&dest).unwrap();
    fs::write(short.path(), vec![1; 32]).unwrap();
    let short_path = short.path().to_owned();
    short.keep();
    let long = StagedDownload::new(&dest).unwrap();
    fs::write(long.path(), vec![2; 128 * 1024 + 10]).unwrap();
    let long_path = long.path().to_owned();
    long.keep();
    let resumed = StagedDownload::resume_or_create(&dest).unwrap();
    assert_eq!(resumed.path(), long_path);
    assert!(!short_path.exists());
    assert_eq!(aligned_prefix(128 * 1024 + 10, CHUNK), 128 * 1024);
    assert_eq!(aligned_prefix(10, CHUNK), 0);
    resumed.keep();
    fs::write(cancel_marker(&dest), []).unwrap();
    assert!(StagedDownload::resume_or_create(&dest).is_err());
    assert!(long_path.exists(), "cancel keeps the partial like Android");
    let _ = fs::remove_file(&long_path);
    let _ = fs::remove_file(cancel_marker(&dest));
    let _ = fs::remove_file(&dest);
}

#[test]
fn android_range_file_round_trips_and_rejects_a_negative_count() {
    let ranges = MissingRanges::span(128 * 1024, 512 * 1024);
    let bytes = ranges.to_bytes().unwrap();
    assert_eq!(i32::from_be_bytes(bytes[0..4].try_into().unwrap()), 1);
    assert_eq!(i64::from_be_bytes(bytes[4..12].try_into().unwrap()), 128 * 1024);
    assert_eq!(i64::from_be_bytes(bytes[12..20].try_into().unwrap()), 512 * 1024);
    assert_eq!(MissingRanges::load(&bytes), Some(ranges));
    assert!(MissingRanges::load(&(-1i32).to_be_bytes()).is_none());
    let mut huge = vec![0x7f, 0xff, 0xff, 0xff];
    huge.extend_from_slice(&[0; 4]);
    assert!(MissingRanges::load(&huge).is_none());
    assert_eq!(resume_start(MissingRanges::load(&bytes).as_ref(), 0, CHUNK), 128 * 1024);
    assert_eq!(resume_start(None, 128 * 1024 + 10, CHUNK), 128 * 1024);
    let mut hole = MissingRanges::span(100, 200);
    assert!(preserved_end(1_000, 200, Some(1_000), Some(&hole)).is_err());
    hole.cover(100, 200);
    assert_eq!(preserved_end(1_000, 200, Some(1_000), Some(&hole)), Ok(1_000));
    let leading = MissingRanges::span(0, CHUNK as i64);
    assert!(preserved_end(1_048_576, 0, Some(1_048_576), Some(&leading)).is_err());
    let mut unknown = MissingRanges::span(100, 200);
    unknown.cover(400, i64::MAX);
    assert!(preserved_end(1_000, 180, None, Some(&unknown)).is_err());
    unknown.cover(100, 200);
    unknown.clip_to_end(180);
    assert_eq!(preserved_end(1_000, 180, None, Some(&unknown)), Ok(1_000));
    assert!(keep_partial(&crate::MtprotoError::Message(
        "download incomplete: received 200 of 1000 bytes".into()
    )));
    assert!(keep_partial(&crate::MtprotoError::Message(
        "401: AUTH_KEY_UNREGISTERED".into()
    )));
    assert!(keep_partial(&crate::MtprotoError::Message(
        "401: AUTH_KEY_INVALID".into()
    )));
    assert!(keep_partial(&crate::MtprotoError::Message(
        "406: AUTH_KEY_DUPLICATED".into()
    )));
    let staging = std::path::PathBuf::from("video.mp4.100.2.part");
    let sidecar = ranges_path(&staging);
    assert!(sidecar.ends_with("video.mp4.100.2.ranges.part"));
    assert!(!is_native_staging_name(
        "video.mp4.100.2.ranges.part",
        "video.mp4"
    ));
}

#[test]
fn retryable_failure_keeps_the_part_and_cancel_does_not() {
    let dest = std::env::temp_dir().join(format!("monogram-keep-{}", std::process::id()));
    let staged = StagedDownload::new(&dest).unwrap();
    let path = staged.path().to_owned();
    fs::write(&path, b"partial").unwrap();
    assert!(keep_partial(&crate::MtprotoError::Message("FLOOD_WAIT_3".into())));
    assert!(keep_partial(&crate::MtprotoError::Message(
        "FILE_REFERENCE_EXPIRED".into()
    )));
    assert!(!keep_partial(&crate::MtprotoError::Message("cancelled".into())));
    staged.keep();
    assert!(path.exists());
    let _ = fs::remove_file(&path);
    let _ = fs::remove_file(&dest);
}

#[test]
fn one_destination_has_one_writer() {
    let dest = std::env::temp_dir().join(format!("monogram-lock-{}", std::process::id()));
    let first = DestinationLock::acquire(&dest);
    let dest_for_thread = dest.clone();
    let (started, started_rx) = std::sync::mpsc::channel();
    let thread = std::thread::spawn(move || {
        started.send(()).unwrap();
        let _second = DestinationLock::acquire(&dest_for_thread);
        started.send(()).unwrap();
    });
    started_rx.recv_timeout(std::time::Duration::from_secs(1)).unwrap();
    assert!(started_rx.recv_timeout(std::time::Duration::from_millis(50)).is_err());
    drop(first);
    started_rx.recv_timeout(std::time::Duration::from_secs(1)).unwrap();
    thread.join().unwrap();
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
fn shared_transport_fetches_bounded_parts_and_removes_failed_download() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
        path.exists(),
        "a recoverable file-reference error must retain the partial file"
    );
    assert_eq!(fs::read(&path).unwrap(), vec![1; CHUNK as usize]);
    fs::remove_file(&path).ok();
    fs::remove_file(ranges_path(&path)).ok();
}

#[test]
fn media_range_fetches_one_aligned_part_and_rejects_invalid_bounds() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
                assert!(request.cdn_supported.is_none());
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
    for callback in [false, true] {
        let origin = 2 * i64::from(CHUNK);
        download_media_range_batched_streaming(
            2, &media, &path, &path, Some(origin), 8,
            |_, requests, on_chunk| {
                assert_eq!(requests.len(), 4);
                let mut results = Vec::new();
                for (index, request) in requests.into_iter().enumerate() {
                    let result = Ok(UploadFile::UploadFile(UploadFileConstructor {
                        type_: Box::new(StorageFileType::StorageFileUnknown(StorageFileUnknownConstructor {})),
                        mtime: 0,
                        bytes: vec![(request.offset / i64::from(CHUNK)) as u8; CHUNK as usize],
                    }));
                    if callback { assert!(on_chunk(index, result.clone()).is_none()); }
                    results.push(result);
                }
                Ok(results)
            },
        ).unwrap();
        let bytes = fs::read(&path).unwrap();
        assert_eq!(bytes.len(), 512 * 1024);
        for index in 0..4 {
            assert!(bytes[index * CHUNK as usize..(index + 1) * CHUNK as usize]
                .iter().all(|byte| *byte == (index + 2) as u8));
        }
        fs::remove_file(&path).unwrap();
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
fn default_pipeline_parts_is_four() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use crate::client::{DEFAULT_PIPELINE_PARTS, pipeline_parts, set_pipeline_parts};
    let previous = pipeline_parts();
    set_pipeline_parts(DEFAULT_PIPELINE_PARTS);
    assert_eq!(pipeline_parts(), 4);
    set_pipeline_parts(previous);
}

#[test]
fn limit_invalid_falls_back_to_128kib() {
    let _globals = hold_media_globals();
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let policy = std::sync::Arc::new(crate::transfer_policy::TransferPolicy::stock());
    policy.own_chunk_size(512 * 1024);
    policy.own_pipeline_parts(8);
    let _policy = crate::transfer_policy::bind(policy);
    let _profile = bind_download_profile(DownloadProfile::User);
    let previous_chunk = chunk_size();
    set_chunk_size(512 * 1024);
    let media = MediaRef {
        file_size: None,
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
    assert_eq!(
        chunk_size(),
        512 * 1024,
        "fallback must not change the configured chunk"
    );
    set_chunk_size(previous_chunk);
    assert!(result.is_ok(), "fallback download failed: {result:?}");
    assert_eq!(limits, vec![512 * 1024, DEFAULT_CHUNK]);
    assert_eq!(fs::read(&path).unwrap(), vec![7u8; file_size as usize]);
    fs::remove_file(&path).unwrap();
}

#[test]
fn batched_streaming_writes_a_part_before_the_batch_returns() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let previous = pipeline_parts();
    set_pipeline_parts(4);
    let media = MediaRef {
        file_size: None,
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
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
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
        file_size: None,
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
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let previous = pipeline_parts();
    set_pipeline_parts(2);
    let media = MediaRef {
        file_size: None,
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
fn parallel_media_download_preserves_bytes_with_out_of_order_refills() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor, UploadGetFileRequest,
    };
    let media = MediaRef {
        file_size: None,
        kind: "video".into(),
        cache_key: "parallel-refill".into(),
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
    let path =
        std::env::temp_dir().join(format!("monogram-parallel-refill-{}", std::process::id()));
    let mut batches = 0;
    let mut file_size = 0;
    let result = download_media_range_batched_streaming(
        2,
        &media,
        &path,
        &path,
        None,
        4,
        |_, requests, on_chunk| {
            batches += 1;
            file_size = requests[0].limit as usize * 7 + 123;
            let mut lanes = [Vec::new(), Vec::new()];
            for (index, request) in requests.into_iter().enumerate() {
                lanes[index % 2].push((index, request));
            }
            let invoke = |mut requests: Vec<UploadGetFileRequest>,
                          on_chunk: &mut dyn FnMut(
                usize,
                Result<UploadFile, MtprotoError>,
            ) -> Option<UploadGetFileRequest>| {
                let mut results = Vec::new();
                let mut index = 0;
                while index < requests.len() {
                    let request = &requests[index];
                    if request.offset == 0 {
                        std::thread::sleep(std::time::Duration::from_millis(20));
                    }
                    let start = request.offset as usize;
                    let len = file_size.saturating_sub(start).min(request.limit as usize);
                    let file = UploadFile::UploadFile(UploadFileConstructor {
                        type_: Box::new(StorageFileType::StorageFileUnknown(
                            StorageFileUnknownConstructor {},
                        )),
                        mtime: 0,
                        bytes: (start..start + len)
                            .map(|offset| (offset % 251) as u8)
                            .collect(),
                    });
                    if let Some(next) = on_chunk(index, Ok(file.clone())) {
                        requests.push(next);
                    }
                    results.push(Ok(file));
                    index += 1;
                }
                Ok(results)
            };
            crate::client::invoke_parallel_media_batches(lanes, invoke, invoke, on_chunk)
        },
    );
    assert!(result.is_ok(), "parallel download failed: {result:?}");
    assert_eq!(batches, 1);
    assert_eq!(
        fs::read(&path).unwrap(),
        (0..file_size)
            .map(|offset| (offset % 251) as u8)
            .collect::<Vec<_>>()
    );
    fs::remove_file(path).unwrap();
}

#[test]
fn one_mib_chunks_are_rejected() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    let previous = chunk_size();
    set_chunk_size(1024 * 1024);
    assert_eq!(chunk_size(), DEFAULT_CHUNK);
    set_chunk_size(512 * 1024);
    assert_eq!(chunk_size(), 512 * 1024);
    set_chunk_size(previous);
    assert!(window_candidate(128 * 1024, 4, false).is_some());
    assert!(window_candidate(512 * 1024, 8, false).is_some());
    assert!(window_candidate(1024 * 1024, 12, false).is_none());
    assert!(window_candidate(1024 * 1024, 12, true).is_some());
    assert!(window_candidate(1024 * 1024, 4, true).is_none());
}

#[test]
fn overlapping_ranges_collapse_and_a_second_claim_waits() {
    let offsets = dedup_offsets(&[0, 100, 128 * 1024, 50, 128 * 1024], 128 * 1024);
    assert_eq!(offsets, vec![0, 128 * 1024]);
    let first = claim_offsets(9, &[0, 128 * 1024]);
    assert_eq!(first, vec![0, 128 * 1024]);
    assert!(claim_offsets(9, &[0]).is_empty());
    assert_eq!(claim_offsets(9, &[256 * 1024]), vec![256 * 1024]);
    release_offsets(9, &[0, 128 * 1024, 256 * 1024]);
    assert_eq!(claim_offsets(9, &[0]), vec![0]);
    release_offsets(9, &[0]);
}

#[test]
fn resume_rejects_a_partial_whose_identity_does_not_match() {
    let current = RangeIdentity {
        file_id: 7,
        access_hash: 8,
        dc_id: 2,
        size: 100,
        version: 0,
    };
    assert!(!resume_allowed(None, &current));
    let mut other = current.clone();
    other.file_id = 9;
    assert!(!resume_allowed(Some(&other), &current));
    other.file_id = 7;
    other.size = 90;
    assert!(!resume_allowed(Some(&other), &current));
    assert!(resume_allowed(Some(&current), &current));
}

#[test]
fn optional_transfer_pauses_only_while_visible_work_is_waiting() {
    crate::client::lanes::set_visible_transfer_waiters_for_test(0);
    assert!(!should_pause_optional(Some(DownloadProfile::Background)));
    crate::client::lanes::set_visible_transfer_waiters_for_test(1);
    assert!(should_pause_optional(Some(DownloadProfile::Background)));
    assert!(!should_pause_optional(Some(DownloadProfile::Visible)));
    assert!(!should_pause_optional(Some(DownloadProfile::User)));
    crate::client::lanes::set_visible_transfer_waiters_for_test(0);
    assert!(!should_pause_optional(Some(DownloadProfile::Background)));
    assert!(keep_partial(&crate::MtprotoError::Message(
        "OPTIONAL_PAUSED".into()
    )));
}

#[test]
fn file_migrate_retries_that_dc_and_flood_resumes_the_same_transfer() {
    let migrate = MtprotoError::Message("RPC 303: FILE_MIGRATE_4".into());
    assert_eq!(transfer_step(&migrate, 2, 0, 0), TransferStep::Migrate(4));
    assert_eq!(transfer_step(&migrate, 4, 0, 0), TransferStep::Stop);
    assert_eq!(transfer_step(&migrate, 2, 3, 0), TransferStep::Stop);
    let user = MtprotoError::Message("RPC 303: USER_MIGRATE_4".into());
    assert_eq!(transfer_step(&user, 2, 0, 0), TransferStep::Stop);
    let flood = MtprotoError::Message("RPC 420: FLOOD_WAIT_2".into());
    assert_eq!(transfer_step(&flood, 2, 0, 0), TransferStep::ResumeFlood);
    assert_eq!(transfer_step(&flood, 2, 0, 3), TransferStep::Stop);

    let mut dc = 2i32;
    let mut migrations = 0u8;
    let mut flood_resumes = 0u8;
    let mut attempts = Vec::new();
    let err = loop {
        let step = match attempts.len() {
            0 => transfer_step(&flood, dc, migrations, flood_resumes),
            1 => transfer_step(&migrate, dc, migrations, flood_resumes),
            _ => break None,
        };
        match step {
            TransferStep::ResumeFlood => {
                attempts.push(dc);
                flood_resumes += 1;
            }
            TransferStep::Migrate(next) => {
                attempts.push(dc);
                dc = next;
                migrations += 1;
            }
            TransferStep::Stop => break Some(step),
        }
    };
    assert_eq!(err, None);
    assert_eq!(attempts, vec![2, 2]);
    assert_eq!(dc, 4);
}

#[test]
fn flood_wait_unwinds_without_retrying_on_the_lane() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    let _policy = crate::transfer_policy::bind(std::sync::Arc::new(
        crate::transfer_policy::TransferPolicy::stock(),
    ));
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
    assert!(result.is_err());
    assert_eq!(calls, 1);
    fs::remove_file(&path).ok();
}

#[test]
fn ordinary_flood_wait_reduces_aggressive_download_window() {
    let now = std::time::Instant::now();
    let current = DownloadWindow {
        chunk: FAST_CHUNK,
        in_flight: 8,
    };
    let mut scope = FloodScope::new();

    scope.park(2, TransferClass::Download, 2, false, now, current);
    assert_eq!(
        scope.window(2, TransferClass::Download, now, current),
        DownloadWindow {
            chunk: FAST_CHUNK,
            in_flight: 2,
        }
    );

    let mut premium_scope = FloodScope::new();
    premium_scope.park(2, TransferClass::Download, 2, true, now, current);
    assert_eq!(
        premium_scope.window(2, TransferClass::Download, now, current),
        DownloadWindow {
            chunk: FAST_CHUNK,
            in_flight: 1,
        }
    );
}

#[test]
fn flood_retry_keeps_window_after_deadline_and_recovers_only_with_success() {
    let now = std::time::Instant::now();
    let fast = DownloadWindow { chunk: FAST_CHUNK, in_flight: 8 };
    let mut scope = FloodScope::new();
    scope.park(2, TransferClass::Download, 2, false, now, fast);
    let retry = now + std::time::Duration::from_secs(3);
    assert!(scope.pending_error(2, TransferClass::Download, retry).is_none());
    assert_eq!(scope.window(2, TransferClass::Download, retry, fast).in_flight, 2);
    assert!(scope.recovering_download(2));
    assert!(!scope.recovering_download(4));
    scope.park(2, TransferClass::Download, 2, false, retry, fast);
    let ready = retry + std::time::Duration::from_secs(2);
    assert_eq!(scope.window(2, TransferClass::Download, ready, fast).in_flight, 1);
    let later = ready + std::time::Duration::from_secs(31);
    assert_eq!(scope.window(2, TransferClass::Download, later, fast).in_flight, 1);
    for _ in 0..31 { scope.download_succeeded(2, later); }
    assert_eq!(scope.window(2, TransferClass::Download, later, fast).in_flight, 1);
    scope.download_succeeded(2, later);
    assert_eq!(scope.window(2, TransferClass::Download, later, fast).in_flight, 2);
    for _ in 0..32 { scope.download_succeeded(2, later); }
    assert_eq!(scope.window(2, TransferClass::Download, later, fast).in_flight, 2);
    let stable = later + std::time::Duration::from_secs(30);
    scope.download_succeeded(2, stable);
    assert_eq!(scope.window(2, TransferClass::Download, stable, fast).in_flight, 3);
    assert_eq!(scope.window(4, TransferClass::Download, stable, fast), fast);
    let small = DownloadWindow { chunk: DEFAULT_CHUNK, in_flight: 1 };
    assert_eq!(scope.window(2, TransferClass::Download, stable, small), small);
}

#[test]
fn rpc_timeout_retries_from_missing_offset_without_dropping_written_parts() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    use tellers_mtproto::latest::api::{
        StorageFileType, StorageFileUnknownConstructor, UploadFileConstructor,
    };
    let media = MediaRef {
        file_size: None,
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
