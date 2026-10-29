use super::*;
use crate::UpdatesStateDto;
use mono_proto_session::OsRandom;
use std::path::{Path, PathBuf};

struct TestDir(PathBuf);

impl TestDir {
    fn new() -> Self {
        let mut nonce = [0_u8; 16];
        mono_proto_crypto::fill_random(&mut nonce).unwrap();
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

fn seed(store: &FileSessionStore, path: &Path) -> ClientSession {
    let snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    fs::write(path, serde_json::to_vec(&snapshot).unwrap()).unwrap();
    store.load().unwrap().unwrap()
}

#[test]
fn destroyed_client_cannot_commit_an_old_snapshot() {
    let dir = TestDir::new();
    let store = dir.store();
    let session = seed(&store, &dir.0.join("session.json"));
    let original = fs::read(&dir.0.join("session.json")).unwrap();
    let control = std::sync::Arc::new(crate::tcp::ConnectionControl::default());
    control.close();
    crate::tcp::with_connection_control(&control, || {
        assert!(store.save(&session).is_err());
    });
    assert_eq!(fs::read(&dir.0.join("session.json")).unwrap(), original);
    assert_eq!(fs::read_dir(&dir.0).unwrap().count(), 1);
}

#[test]
fn failed_encryption_migration_preserves_original() {
    let dir = TestDir::new();
    let store = dir.store();
    seed(&store, &dir.0.join("session.json"));
    let original = fs::read(&dir.0.join("session.json")).unwrap();
    let _key = crate::session_crypto::register(&dir.0.join("session.json"), &[9; 32]).unwrap();
    let control = std::sync::Arc::new(crate::tcp::ConnectionControl::default());
    control.close();
    crate::tcp::with_connection_control(&control, || assert!(store.load().is_err()));
    assert_eq!(fs::read(&dir.0.join("session.json")).unwrap(), original);
    assert_eq!(fs::read_dir(&dir.0).unwrap().count(), 1);
}

#[test]
fn encrypted_client_owns_key_until_destroy_and_restores_authorization() {
    let dir = TestDir::new();
    let store = dir.store();
    let mut session = seed(&store, &dir.0.join("session.json"));
    session.user_id = Some(42);
    session.snapshot.auth_key = Some(vec![7; 256]);
    store.save(&session).unwrap();
    let path = dir.0.join("session.json").to_string_lossy().to_string();
    assert!(
        crate::create_encrypted_client(1, "fixture".into(), path.clone(), vec![9; 31]).is_err()
    );
    let handle =
        crate::create_encrypted_client(1, "fixture".into(), path.clone(), vec![9; 32]).unwrap();
    assert!(crate::is_authorized(handle).unwrap());
    assert!(crate::session_crypto::key_for(&dir.0.join("session.json")).is_some());
    crate::destroy_client(handle);
    assert!(crate::session_crypto::key_for(&dir.0.join("session.json")).is_none());
    assert!(crate::create_encrypted_client(1, "fixture".into(), path, vec![8; 32]).is_err());
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
        MESSAGE_MEDIA_CAP, PERSISTED_INLINE_BYTE_CAP, ZERO_ID_DOCUMENT_CAP, insert_media,
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
    let mut session = seed(&store, &dir.0.join("session.json"));
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
