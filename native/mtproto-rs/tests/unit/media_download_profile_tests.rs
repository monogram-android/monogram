use super::*;

#[test]
fn download_profiles_bound_part_size_and_concurrency() {
    assert_eq!(
        download_window(DownloadProfile::Visible),
        DownloadWindow {
            chunk: FAST_CHUNK,
            in_flight: 8,
        }
    );
    assert_eq!(
        download_window(DownloadProfile::Playing),
        download_window(DownloadProfile::User)
    );
    assert_eq!(download_window(DownloadProfile::User).chunk, 512 * 1024);
    assert_eq!(download_window(DownloadProfile::User).in_flight, 8);
    for profile in [DownloadProfile::Ordinary, DownloadProfile::Background] {
        let window = download_window(profile);
        assert_eq!(window.chunk, DEFAULT_CHUNK);
        assert_eq!(window.in_flight, 4);
    }
    assert_eq!(
        download_window(DownloadProfile::Thumb),
        DownloadWindow {
            chunk: DEFAULT_CHUNK,
            in_flight: 1,
        }
    );
    let lowered =
        limit_invalid_window(download_window(DownloadProfile::User)).expect("smaller window");
    assert_eq!(lowered.chunk, DEFAULT_CHUNK);
    assert!(lowered.in_flight < 8);
    assert!(lowered.chunk < FAST_CHUNK);
}

#[test]
fn parts_outside_the_seek_window_are_cancelled_and_cdn_stays_on_origin() {
    let chunk = FAST_CHUNK;
    let step = i64::from(chunk);
    let inflight = vec![0, step, step * 2, step * 3, step * 5];
    let cancelled = cancel_outside_seek_window(step * 2 + 10, chunk, 2, &inflight);
    assert_eq!(cancelled, vec![0, step, step * 5]);

    let media = crate::media::MediaRef {
        file_size: None,
        kind: "video".into(),
        cache_key: "seek-cdn".into(),
        location: crate::media::MediaLocation::Document {
            id: 3,
            access_hash: 4,
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
    let path = std::env::temp_dir().join(format!("monogram-seek-cdn-{}", std::process::id()));
    let mut cdn_flags = Vec::new();
    let result = download_media_range_with_fetch(2, &media, &path, &path, Some(step), |request| {
        cdn_flags.push(request.cdn_supported.is_some());
        assert_eq!(request.offset, step);
        Ok(tellers_mtproto::latest::api::UploadFile::UploadFile(
            tellers_mtproto::latest::api::UploadFileConstructor {
                type_: Box::new(
                    tellers_mtproto::latest::api::StorageFileType::StorageFileUnknown(
                        tellers_mtproto::latest::api::StorageFileUnknownConstructor {},
                    ),
                ),
                mtime: 0,
                bytes: vec![9u8; 16],
            },
        ))
    });
    let _ = std::fs::remove_file(&path);
    assert!(result.is_ok(), "{result:?}");
    assert_eq!(cdn_flags, vec![false]);
}

#[test]
fn flood_parks_one_dc_and_class_for_the_server_interval() {
    let fast = download_window(DownloadProfile::User);
    let ordinary = download_window(DownloadProfile::Ordinary);
    let mut scope = FloodScope::new();
    let now = std::time::Instant::now();
    scope.park(2, TransferClass::Download, 120, true, now, fast);
    scope.park(2, TransferClass::Download, 5, false, now, fast);
    assert!(
        scope
            .pending_error(2, TransferClass::Download, now + flood_wait_duration(6))
            .is_some()
    );
    assert!(
        scope
            .pending_error(2, TransferClass::Download, now)
            .is_some()
    );
    assert!(
        scope
            .pending_error(4, TransferClass::Download, now)
            .is_none()
    );
    assert!(scope.pending_error(2, TransferClass::Upload, now).is_none());
    assert!(
        scope
            .pending_error(2, TransferClass::Download, now + flood_wait_duration(120))
            .is_none()
    );
    assert_eq!(flood_wait_duration(120).as_secs(), 120);
    assert_eq!(
        scope
            .window(2, TransferClass::Download, now, fast)
            .in_flight,
        1
    );
    assert_eq!(scope.window(4, TransferClass::Download, now, fast), fast);
    assert_eq!(
        scope.window(2, TransferClass::Upload, now, ordinary),
        ordinary
    );
    let later = now + flood_wait_duration(120);
    assert_eq!(scope.window(2, TransferClass::Download, later, fast).in_flight, 1);
}
