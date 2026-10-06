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
        !path.exists(),
        "a failed batch must not leave a partial file"
    );
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
fn one_mib_chunks_are_rejected() {
    let _globals = hold_media_globals();
    let _profile = bind_download_profile(DownloadProfile::Ordinary);
    let previous = chunk_size();
    set_chunk_size(1024 * 1024);
    assert_eq!(chunk_size(), DEFAULT_CHUNK);
    set_chunk_size(512 * 1024);
    assert_eq!(chunk_size(), 512 * 1024);
    set_chunk_size(previous);
}

#[test]
fn flood_wait_returns_without_sleeping_or_retrying_on_the_lane() {
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
    assert!(
        matches!(result, Err(MtprotoError::Message(ref message)) if message == "RPC 420: FLOOD_WAIT_2")
    );
    assert_eq!(calls, 1);
    assert!(!path.exists());
    fs::remove_file(&path).ok();
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
