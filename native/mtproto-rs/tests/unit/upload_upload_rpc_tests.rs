use super::*;

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
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    }
}

#[test]
fn staging_hands_out_every_part_once() {
    let path = temp_file("parts", FILE_PART * 2 + 3);
    let fixture = item(&path, "document");
    let mut staging = open_staging(&fixture).expect("staging");

    let first = staging.next_batch(2).expect("first").expect("read");
    assert_eq!(first.offset, 0);
    assert_eq!(first.parts_total, 3);
    assert_ne!(first.file_id, 0);
    assert_eq!(first.bytes.len(), 2);
    assert!(first.bytes.iter().all(|part| part.len() == FILE_PART));
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
    assert_eq!(part_count(1), 1);
    assert_eq!(part_count(FILE_PART as u64), 1);
    assert_eq!(part_count(FILE_PART as u64 + 1), 2);
    assert!(!is_big_file(PHOTO_MAX));
    assert!(is_big_file(PHOTO_MAX + 1));
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
