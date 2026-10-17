use super::*;
use tellers_mtproto_session::OsRandom;

struct TestDir(PathBuf);

impl TestDir {
    fn new() -> Self {
        let mut nonce = [0_u8; 16];
        tellers_mtproto_crypto::fill_random(&mut nonce).unwrap();
        let path = std::env::temp_dir().join(format!(
            "mtproto-session-{:032x}",
            u128::from_le_bytes(nonce)
        ));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn store(&self) -> FileSessionStore {
        FileSessionStore::new(self.0.join("session.json"))
    }
}

impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn seed(store: &FileSessionStore) -> ClientSession {
    let snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    fs::write(&store.path, serde_json::to_vec(&snapshot).unwrap()).unwrap();
    store.load().unwrap().unwrap()
}

#[test]
fn missing_session_is_absent_but_empty_and_corrupt_files_fail() {
    let dir = TestDir::new();
    let store = dir.store();
    assert!(store.load().unwrap().is_none());
    for bytes in [b"".as_slice(), b"{\"snapshot\":", b"{}"] {
        fs::write(&store.path, bytes).unwrap();
        assert!(store.load().is_err());
    }
}

#[test]
fn legacy_snapshot_and_full_session_round_trip() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store);
    assert!(session.dc_auth_keys.is_empty());
    assert!(session.channel_recovery.is_empty());
    session.dc_auth_keys.insert(
        4,
        DcAuthKey {
            key: vec![9; 256],
            salt: 11,
        },
    );
    session.user_id = Some(42);
    session.channel_pts.insert(-1000000000001, 17);
    session.channel_recovery.push_back(ChannelRecovery {
        chat_id: -1000000000001,
        due_at: 123,
        watching: false,
    });
    session.updates = Some(UpdatesStateDto {
        pts: 4,
        qts: 5,
        seq: 6,
        date: 7,
    });
    store.save(&session).unwrap();
    let loaded = store.load().unwrap().unwrap();
    assert_eq!(loaded.snapshot.session_id, session.snapshot.session_id);
    assert_eq!(loaded.user_id, Some(42));
    assert_eq!(loaded.channel_pts, session.channel_pts);
    assert_eq!(loaded.channel_recovery, session.channel_recovery);
    assert_eq!(loaded.updates.unwrap().qts, 5);
    assert_eq!(loaded.dc_auth_keys.len(), 1);
    assert_eq!(loaded.dc_auth_keys[&4].salt, 11);
    assert_eq!(loaded.dc_auth_keys[&4].key, vec![9; 256]);
    let legacy = serde_json::json!({"snapshot": loaded.snapshot});
    let without_keys: ClientSession = serde_json::from_value(legacy).unwrap();
    assert!(without_keys.dc_auth_keys.is_empty());
    store.clear().unwrap();
    store.clear().unwrap();
}

#[test]
fn concurrent_writers_never_share_or_leave_temporary_files() {
    let dir = TestDir::new();
    let store = dir.store();
    let session = seed(&store);
    std::thread::scope(|scope| {
        for user_id in 1..=8 {
            let mut session = session.clone();
            let path = store.path.clone();
            scope.spawn(move || {
                session.user_id = Some(user_id);
                let store = FileSessionStore::new(path);
                for _ in 0..8 {
                    store.save(&session).unwrap();
                    assert!(store.load().unwrap().unwrap().user_id.is_some());
                }
            });
        }
    });
    assert_eq!(fs::read_dir(&dir.0).unwrap().count(), 1);
}

#[test]
fn invalid_snapshot_does_not_replace_valid_session() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store);
    let original = fs::read(&store.path).unwrap();
    session.snapshot.auth_key = Some(vec![1; 3]);
    assert!(store.save(&session).is_err());
    assert_eq!(fs::read(&store.path).unwrap(), original);
}

#[test]
fn destroyed_client_cannot_commit_an_old_snapshot() {
    let dir = TestDir::new();
    let store = dir.store();
    let session = seed(&store);
    let original = fs::read(&store.path).unwrap();
    let control = std::sync::Arc::new(crate::tcp::ConnectionControl::default());
    control.close();
    crate::tcp::with_connection_control(&control, || {
        assert!(store.save(&session).is_err());
    });
    assert_eq!(fs::read(&store.path).unwrap(), original);
    assert_eq!(fs::read_dir(&dir.0).unwrap().count(), 1);
}

#[test]
fn encryption_migrates_legacy_session_without_losing_identity() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store);
    session.user_id = Some(42);
    session.snapshot.auth_key = Some(vec![7; 256]);
    store.save(&session).unwrap();
    let key = crate::session_crypto::register(&store.path, &[9; 32]).unwrap();
    let restored = store.load().unwrap().unwrap();
    assert_eq!(restored.user_id, Some(42));
    assert_eq!(restored.snapshot.auth_key, session.snapshot.auth_key);
    let first = fs::read(&store.path).unwrap();
    assert!(first.starts_with(crate::session_crypto::MAGIC));
    assert!(serde_json::from_slice::<serde_json::Value>(&first).is_err());
    store.save(&restored).unwrap();
    assert_ne!(first, fs::read(&store.path).unwrap());
    assert_eq!(store.load().unwrap().unwrap().user_id, Some(42));
    drop(key);
    assert!(store.load().is_err());
    let _wrong_key = crate::session_crypto::register(&store.path, &[8; 32]).unwrap();
    assert!(store.load().is_err());
}

#[test]
fn encrypted_tampering_and_truncation_fail_without_replacing_session() {
    let dir = TestDir::new();
    let store = dir.store();
    let session = seed(&store);
    let _key = crate::session_crypto::register(&store.path, &[9; 32]).unwrap();
    store.save(&session).unwrap();
    let valid = fs::read(&store.path).unwrap();
    for index in [0, crate::session_crypto::MAGIC.len(), valid.len() - 1] {
        let mut damaged = valid.clone();
        damaged[index] ^= 1;
        fs::write(&store.path, &damaged).unwrap();
        assert!(store.load().is_err());
        assert_eq!(fs::read(&store.path).unwrap(), damaged);
    }
    fs::write(&store.path, &valid[..valid.len() - 1]).unwrap();
    assert!(store.load().is_err());
}

#[test]
fn failed_encryption_migration_preserves_original() {
    let dir = TestDir::new();
    let store = dir.store();
    seed(&store);
    let original = fs::read(&store.path).unwrap();
    let _key = crate::session_crypto::register(&store.path, &[9; 32]).unwrap();
    let control = std::sync::Arc::new(crate::tcp::ConnectionControl::default());
    control.close();
    crate::tcp::with_connection_control(&control, || assert!(store.load().is_err()));
    assert_eq!(fs::read(&store.path).unwrap(), original);
    assert_eq!(fs::read_dir(&dir.0).unwrap().count(), 1);
}

#[test]
fn encrypted_client_owns_key_until_destroy_and_restores_authorization() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store);
    session.user_id = Some(42);
    session.snapshot.auth_key = Some(vec![7; 256]);
    store.save(&session).unwrap();
    let path = store.path.to_string_lossy().to_string();
    assert!(
        crate::create_encrypted_client(1, "fixture".into(), path.clone(), vec![9; 31]).is_err()
    );
    let handle =
        crate::create_encrypted_client(1, "fixture".into(), path.clone(), vec![9; 32]).unwrap();
    assert!(crate::is_authorized(handle).unwrap());
    assert!(crate::session_crypto::key_for(&store.path).is_some());
    crate::destroy_client(handle);
    assert!(crate::session_crypto::key_for(&store.path).is_none());
    assert!(crate::create_encrypted_client(1, "fixture".into(), path, vec![8; 32]).is_err());
}

#[test]
fn encryption_reads_legacy_magic_and_rewrites_current() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store);
    session.user_id = Some(42);
    let key = crate::session_crypto::register(&store.path, &[9; 32]).unwrap();
    let plaintext = serde_json::to_vec(&session).unwrap();
    let encrypted = crate::session_crypto::encrypt_legacy(&plaintext, &key).unwrap();
    assert!(encrypted.starts_with(crate::session_crypto::LEGACY_MAGIC));
    fs::write(&store.path, &encrypted).unwrap();
    let restored = store.load().unwrap().unwrap();
    assert_eq!(restored.user_id, Some(42));
    store.save(&restored).unwrap();
    assert!(
        fs::read(&store.path)
            .unwrap()
            .starts_with(crate::session_crypto::MAGIC)
    );
}

#[test]
fn encryption_uses_opaque_tag_and_fresh_salt() {
    let dir = TestDir::new();
    let key = crate::session_crypto::register(&dir.0.join("key"), &[9; 32]).unwrap();
    let first = crate::session_crypto::encrypt(b"{}", &key).unwrap();
    let second = crate::session_crypto::encrypt(b"{}", &key).unwrap();
    assert!(first.starts_with(crate::session_crypto::MAGIC));
    assert_ne!(first, second);
    assert!(
        !first
            .windows(6)
            .any(|window| window == b"MONORE" || window == b"MONOGR")
    );
    assert_eq!(
        crate::session_crypto::decrypt(&first, &key)
            .unwrap()
            .as_slice(),
        b"{}"
    );
}

fn photo_ref(id: i64) -> MediaRef {
    MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: format!("photo:{id}"),
        location: crate::media::MediaLocation::Photo {
            id,
            access_hash: 1,
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
    }
}

fn document_ref(id: i64) -> MediaRef {
    MediaRef {
        file_size: Some(4),
        kind: "sticker".into(),
        cache_key: format!("doc:{id}"),
        location: crate::media::MediaLocation::Document {
            id,
            access_hash: 1,
            file_reference: vec![2],
            thumb_size: String::new(),
            dc_id: 2,
            mime_type: "image/webp".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    }
}

fn inline_ref(bytes: Vec<u8>) -> MediaRef {
    MediaRef {
        file_size: None,
        kind: "photo".into(),
        cache_key: "iv".into(),
        location: crate::media::MediaLocation::Inline {
            bytes,
            extension: "jpg".into(),
        },
        thumb_cache_key: None,
        thumb_location: None,
        display_cache_key: None,
        display_location: None,
        sticker_set_id: None,
        sticker_set_access_hash: None,
        source_url: None,
    }
}

#[test]
fn media_retention_keeps_avatars_and_caps_other_slices() {
    use crate::media::{
        insert_media, MESSAGE_MEDIA_CAP, PERSISTED_INLINE_BYTE_CAP, ZERO_ID_DOCUMENT_CAP,
    };
    let mut index = MediaIndex::default();
    insert_media(
        &mut index,
        (9, 0),
        crate::media::media_ref_peer_photo(
            crate::peers::PeerKind::User,
            9,
            4,
            9,
            2,
            "avatar:9".into(),
            false,
            None,
        ),
    );
    let mut video = photo_ref(8);
    video.kind = "video_avatar".into();
    video.cache_key = "avatar:8:video".into();
    insert_media(&mut index, (8, 0), video);
    for id in 1..=MESSAGE_MEDIA_CAP as i32 + 1 {
        insert_media(&mut index, (3, id), photo_ref(i64::from(id)));
    }
    for id in 0..=ZERO_ID_DOCUMENT_CAP as i64 {
        insert_media(&mut index, (1_000 + id, 0), document_ref(1_000 + id));
    }
    insert_media(&mut index, (50, -1), inline_ref(vec![1; 32]));
    let mut kept = inline_ref(vec![9; PERSISTED_INLINE_BYTE_CAP + 1]);
    kept.source_url = Some("https://example.invalid/page".into());
    insert_media(&mut index, (51, -1), kept);

    assert!(index.contains_key(&(9, 0)));
    assert!(index.contains_key(&(8, 0)));
    assert!(!index.contains_key(&(3, 1)));
    assert!(index.contains_key(&(3, MESSAGE_MEDIA_CAP as i32 + 1)));
    assert!(!index.contains_key(&(1_000, 0)));
    assert!(index.contains_key(&(1_000 + ZERO_ID_DOCUMENT_CAP as i64, 0)));
    match &index.get(&(51, -1)).unwrap().location {
        crate::media::MediaLocation::Inline { bytes, .. } => {
            assert!(bytes.len() > PERSISTED_INLINE_BYTE_CAP);
        }
        _ => panic!("live instant view"),
    }

    let saved = media_from_index(&index);
    let order = media_order_from_index(&index);
    assert!(saved.contains_key(&media_key(9, 0)));
    assert!(saved.contains_key(&media_key(8, 0)));
    assert!(!saved.contains_key(&media_key(3, 1)));
    assert!(saved.contains_key(&media_key(3, MESSAGE_MEDIA_CAP as i32 + 1)));
    assert!(!saved.contains_key(&media_key(1_000, 0)));
    assert!(saved.contains_key(&media_key(1_000 + ZERO_ID_DOCUMENT_CAP as i64, 0)));
    assert!(!saved.contains_key(&media_key(50, -1)));
    let persisted = saved.get(&media_key(51, -1)).unwrap();
    assert_eq!(
        persisted.source_url.as_deref(),
        Some("https://example.invalid/page")
    );
    match &persisted.location {
        crate::media::MediaLocation::Inline { bytes, .. } => assert!(bytes.is_empty()),
        _ => panic!("persisted instant view"),
    }

    let restored = media_to_index(&saved, &order);
    assert!(restored.contains_key(&(9, 0)));
    assert!(restored.contains_key(&(8, 0)));
    assert!(restored.contains_key(&(3, MESSAGE_MEDIA_CAP as i32 + 1)));
    assert!(restored.contains_key(&(1_000 + ZERO_ID_DOCUMENT_CAP as i64, 0)));
    assert!(restored.contains_key(&(51, -1)));
    assert!(!restored.contains_key(&(50, -1)));
}

#[test]
fn oversized_session_media_loads_and_next_save_shrinks() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store);
    for id in 1..=2_000 {
        session
            .media
            .insert(media_key(4, id), photo_ref(i64::from(id)));
    }
    session.media.insert(
        media_key(9, 0),
        crate::media::media_ref_peer_photo(
            crate::peers::PeerKind::User,
            9,
            4,
            9,
            2,
            "avatar:9".into(),
            false,
            None,
        ),
    );
    session.media_order.clear();
    let mut value = serde_json::to_value(&session).unwrap();
    value.as_object_mut().unwrap().remove("media_order");
    let loaded: ClientSession = serde_json::from_value(value).unwrap();
    assert!(loaded.media_order.is_empty());
    assert!(loaded.media.len() > crate::media::MESSAGE_MEDIA_CAP);
    store.save(&loaded).unwrap();
    let from_disk = store.load().unwrap().unwrap();
    assert!(from_disk.media.len() > crate::media::MESSAGE_MEDIA_CAP);
    let index = media_to_index(&from_disk.media, &from_disk.media_order);
    assert!(index.contains_key(&(9, 0)));
    let messages = index.keys().filter(|key| key.1 > 0).count();
    assert!(messages <= crate::media::MESSAGE_MEDIA_CAP);
    let mut shrunk = from_disk;
    shrunk.media = media_from_index(&index);
    shrunk.media_order = media_order_from_index(&index);
    assert!(shrunk.media.len() < 2_000);
    store.save(&shrunk).unwrap();
    let again = store.load().unwrap().unwrap();
    assert!(again.media.contains_key(&media_key(9, 0)));
    assert!(again.media.len() <= crate::media::MESSAGE_MEDIA_CAP + 1);
}
