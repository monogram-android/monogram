use super::*;

fn stock_policy() -> crate::transfer_policy::PolicyGuard {
    let policy = std::sync::Arc::new(crate::transfer_policy::TransferPolicy::stock());
    crate::transfer_policy::bind(policy)
}

fn temp_file(name: &str, size: usize) -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!(
        "monogram-upload-{}-{}-{name}",
        std::process::id(),
        random_id(),
    ));
    std::fs::write(&path, vec![7_u8; size]).expect("fixture file");
    path
}

fn item(path: &std::path::Path, kind: &str) -> UploadItemDto {
    UploadItemDto {
        path: path.to_string_lossy().into_owned(),
        kind: kind.into(),
        mime_type: String::new(),
        file_name: "fixture.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    }
}

#[test]
fn staging_hands_out_every_part_once() {
    let _policy = stock_policy();
    let path = temp_file("parts", FILE_PART_FAST * 2 + 3);
    let fixture = item(&path, "document");
    let mut staging = open_staging(&fixture).expect("staging");

    let first = staging.next_batch(2).expect("first").expect("read");
    assert_eq!(first.offset, 0);
    assert_eq!(first.parts_total, 3);
    assert_ne!(first.file_id, 0);
    assert_eq!(first.bytes.len(), 2);
    assert!(first.bytes.iter().all(|part| part.len() == FILE_PART_FAST));
    assert_eq!(staging.in_flight(), MAX_PARTS_IN_FLIGHT);
    assert!(!staging.is_complete());

    let second = staging.next_batch(2).expect("second").expect("read");
    assert_eq!(second.offset, 2);
    assert_eq!(second.bytes.len(), 1);
    assert_eq!(second.bytes[0].len(), 3);
    assert!(staging.is_complete());
    assert!(staging.next_batch(2).is_none());
    let _ = std::fs::remove_file(&path);
}

#[test]
fn staging_rejects_oversized_photos_and_empty_files() {
    let photo = temp_file("big-photo", PHOTO_MAX as usize + 1);
    let Err(err) = open_staging(&item(&photo, "photo")) else {
        panic!("oversized photo must be rejected");
    };
    match err {
        MtprotoError::Message(m) => assert_eq!(m, "photo too large"),
        other => panic!("{other:?}"),
    }
    let empty = temp_file("empty", 0);
    let Err(err) = open_staging(&item(&empty, "document")) else {
        panic!("empty file must be rejected");
    };
    match err {
        MtprotoError::Message(m) => assert_eq!(m, "empty file"),
        other => panic!("{other:?}"),
    }
    let _ = std::fs::remove_file(&photo);
    let _ = std::fs::remove_file(&empty);
}

#[test]
fn a_missing_stored_part_is_retryable() {
    assert!(is_missing_file_part(&MtprotoError::Message(
        "RPC 400: FILE_PART_3_MISSING".into()
    )));
    assert!(is_missing_file_part(&MtprotoError::Message(
        "RPC 400: FILE_PARTS_INVALID".into()
    )));
    assert!(!is_missing_file_part(&MtprotoError::Message(
        "RPC 420: FLOOD_WAIT_3".into()
    )));
}

#[test]
fn part_size_matches_file_api() {
    let _policy = stock_policy();
    assert_eq!(part_count(1), 1);
    assert_eq!(part_count(FILE_PART_SMALL as u64), 1);
    assert_eq!(part_count(FILE_PART_FAST as u64), 1);
    assert_eq!(part_count(FILE_PART_FAST as u64 + 1), 2);
    assert!(!is_big_file(PHOTO_MAX));
    assert!(is_big_file(PHOTO_MAX + 1));
    assert!(!is_big_file(BIG_FILE_THRESHOLD));
    assert!(is_big_file(BIG_FILE_THRESHOLD + 1));
}

#[test]
fn normal_upload_uses_512kib_and_eight_in_flight() {
    let _policy = stock_policy();
    let limits = upload_limits(FILE_PART_FAST as u64 + 1, None);
    assert_eq!(limits.part_size, FILE_PART_FAST);
    assert_eq!(limits.in_flight, 8);

    let path = temp_file("normal", FILE_PART_FAST + 3);
    let mut staging = open_staging(&item(&path, "document")).expect("staging");
    assert_eq!(staging.in_flight(), 8);
    assert!(
        !staging
            .next_batch(staging.in_flight())
            .expect("batch")
            .expect("read")
            .big
    );
    let _ = std::fs::remove_file(&path);
}

#[test]
fn one_part_or_slow_link_uses_32kib_and_one_in_flight() {
    let _policy = stock_policy();
    let small = upload_limits(FILE_PART_SMALL as u64, None);
    assert_eq!(small.part_size, FILE_PART_SMALL);
    assert_eq!(small.in_flight, 1);

    let path = temp_file("small", FILE_PART_SMALL);
    let staging = open_staging(&item(&path, "document")).expect("staging");
    assert_eq!(staging.in_flight(), 1);
    let _ = std::fs::remove_file(&path);

    let slow = upload_limits(8 * 1024 * 1024, Some(SLOW_UPLOAD_RTT));
    assert_eq!(slow.part_size, FILE_PART_SMALL);
    assert_eq!(slow.in_flight, 1);
    let below = upload_limits(
        8 * 1024 * 1024,
        Some(SLOW_UPLOAD_RTT - std::time::Duration::from_millis(1)),
    );
    assert_eq!(below.part_size, FILE_PART_FAST);
    assert_eq!(below.in_flight, 8);
}

#[test]
fn album_rejects_mixed_and_too_long() {
    validate_album(&["photo", "video"]).expect("media album");
    validate_album(&["document", "document"]).expect("file album");
    let mixed = validate_album(&["photo", "document"]).expect_err("mixed");
    match mixed {
        MtprotoError::Message(m) => assert_eq!(m, "MEDIA_GROUPED_INVALID"),
        other => panic!("{other:?}"),
    }
    let kinds = vec!["photo"; 11];
    let too_long = validate_album(&kinds).expect_err("cap");
    match too_long {
        MtprotoError::Message(m) => assert_eq!(m, "MULTI_MEDIA_TOO_LONG"),
        other => panic!("{other:?}"),
    }
}

#[test]
fn slow_network_preserves_large_file_support_and_premium_part_limit() {
    let policy = std::sync::Arc::new(crate::transfer_policy::TransferPolicy::stock());
    let _guard = crate::transfer_policy::bind(policy.clone());
    let path = temp_file("premium-parts", 1);
    let file = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
    file.set_len(4_001 * FILE_PART_FAST as u64).unwrap();
    assert!(open_staging_with_rtt(&item(&path, "document"), Some(SLOW_UPLOAD_RTT)).is_err());
    policy
        .premium
        .store(true, std::sync::atomic::Ordering::Relaxed);
    let staging = open_staging_with_rtt(&item(&path, "document"), Some(SLOW_UPLOAD_RTT)).unwrap();
    assert_eq!(staging.parts_total, 4_001);
    assert_eq!(staging.part_size, FILE_PART_FAST);
    assert_eq!(staging.in_flight(), 1);
    drop(staging);
    drop(file);
    std::fs::remove_file(path).unwrap();
}

#[test]
fn album_entry_preserves_caption_entities_and_literal_fallback() {
    let mut fixture = item(std::path::Path::new("fixture.jpg"), "photo");
    fixture.caption = "🙂 title".into();
    fixture.random_id = 7;
    let input = InputFile::InputFile(InputFileConstructor {
        id: 42,
        parts: 1,
        name: "fixture.jpg".into(),
        md5_checksum: String::new(),
    });
    fixture.entities_json = Some(r#"[{"kind":"italic","offset":3,"length":5}]"#.into());
    let InputSingleMedia::InputSingleMedia(styled) =
        album_entry(&fixture, input_media(&fixture, input.clone()).unwrap()).unwrap();
    assert_eq!(styled.message, fixture.caption);
    assert_eq!(styled.random_id, 7);
    assert_eq!(styled.flags, InputSingleMediaConstructor::ENTITIES_FLAG);
    let Vector::Vector(entities) = *styled.entities.unwrap();
    assert_eq!(entities.field_0, 1);
    let tellers_mtproto::latest::api::MessageEntity::MessageEntityItalic(entity) =
        &*entities.field_1[0]
    else {
        panic!("italic");
    };
    assert_eq!((entity.offset, entity.length), (3, 5));
    fixture.caption = "mediatek,gpio_usage_mapping".into();
    fixture.entities_json = None;
    let InputSingleMedia::InputSingleMedia(literal) =
        album_entry(&fixture, input_media(&fixture, input.clone()).unwrap()).unwrap();
    assert_eq!(literal.message, fixture.caption);
    assert_eq!(literal.flags, 0);
    assert!(literal.entities.is_none());
    fixture.entities_json = Some("invalid".into());
    assert!(album_entry(&fixture, input_media(&fixture, input).unwrap()).is_err());
}
