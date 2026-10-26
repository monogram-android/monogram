use super::*;
use sha2::{Digest, Sha256};
use std::cell::Cell;
use std::path::PathBuf;
use tellers_mtproto::latest::api::FileHashConstructor;

struct Fixture {
    root: PathBuf,
    dest: PathBuf,
    job: media::CdnRedirect,
    encrypted: Vec<u8>,
}

impl Fixture {
    fn new(label: &str) -> Self {
        let id = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root =
            std::env::temp_dir().join(format!("monogram-cdn-{label}-{}-{id}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let dest = root.join("file");
        std::fs::write(&dest, b"original").unwrap();
        let bytes = b"verified CDN bytes";
        let job = media::CdnRedirect {
            dc_id: 203,
            file_token: vec![1],
            encryption_key: vec![7; 32],
            encryption_iv: vec![9; 16],
            offset: 0,
            file_hashes: vec![FileHash::FileHash(FileHashConstructor {
                offset: 0,
                limit: bytes.len() as i32,
                hash: Sha256::digest(bytes).to_vec(),
            })],
        };
        let mut encrypted = bytes.to_vec();
        media::decrypt_cdn_part(&job.encryption_key, &job.encryption_iv, 0, &mut encrypted)
            .unwrap();
        Self {
            root,
            dest,
            job,
            encrypted,
        }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

#[test]
fn verified_cdn_transfer_stages_then_publishes_through_session_guard() {
    let fixture = Fixture::new("verified");
    let handle = create_client(
        1,
        "fixture".into(),
        fixture.root.join("session").display().to_string(),
    );
    let client = get_client(handle).unwrap();
    let session = client.data.lock().home_session_id;
    let staged = media::StagedDownload::new(&fixture.dest).unwrap();
    receive_cdn_parts(
        &fixture.job,
        &fixture.dest,
        &staged,
        |_| Ok(Ok(fixture.encrypted.clone())),
        |_| panic!("unexpected reupload"),
    )
    .unwrap();
    assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"original");
    assert_eq!(std::fs::read(staged.path()).unwrap(), b"verified CDN bytes");
    publish_media(&client, session, staged, &fixture.dest).unwrap();
    assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"verified CDN bytes");
    destroy_client(handle);
}

#[test]
fn corrupt_or_missing_cdn_hashes_never_write_and_fall_back_to_origin() {
    for missing in [false, true] {
        let mut fixture = Fixture::new(if missing { "missing" } else { "corrupt" });
        if missing {
            fixture.job.file_hashes.clear();
        } else {
            fixture.encrypted[0] ^= 1;
        }
        let fallback = Cell::new(0);
        let staged = media::StagedDownload::new(&fixture.dest).unwrap();
        let partial = staged.path().to_path_buf();
        cdn_or_origin(
            || {
                let result = receive_cdn_parts(
                    &fixture.job,
                    &fixture.dest,
                    &staged,
                    |_| Ok(Ok(fixture.encrypted.clone())),
                    |_| panic!("unexpected reupload"),
                );
                assert!(result.is_err());
                assert!(std::fs::read(staged.path()).unwrap().is_empty());
                assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"original");
                drop(staged);
                result
            },
            || {
                assert!(!media::cdn_supported());
                assert!(!partial.exists());
                fallback.set(fallback.get() + 1);
                std::fs::write(&fixture.dest, b"origin verified").unwrap();
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(fallback.get(), 1);
        assert!(media::cdn_supported());
        assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"origin verified");
    }
}

#[test]
fn repeated_reuploads_are_bounded_and_cleanup_staging() {
    let fixture = Fixture::new("reupload");
    let staged = media::StagedDownload::new(&fixture.dest).unwrap();
    let partial = staged.path().to_path_buf();
    let fetches = Cell::new(0);
    let reuploads = Cell::new(0);
    let result = receive_cdn_parts(
        &fixture.job,
        &fixture.dest,
        &staged,
        |_| {
            fetches.set(fetches.get() + 1);
            Ok(Err(vec![2]))
        },
        |_| {
            reuploads.set(reuploads.get() + 1);
            Ok(())
        },
    );
    assert!(result.is_err());
    assert_eq!(fetches.get(), 4);
    assert_eq!(reuploads.get(), 3);
    assert!(std::fs::read(staged.path()).unwrap().is_empty());
    drop(staged);
    assert!(!partial.exists());
    assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"original");
}

#[test]
fn cancellation_after_fetch_prevents_writing_or_publication() {
    let fixture = Fixture::new("cancel");
    let staged = media::StagedDownload::new(&fixture.dest).unwrap();
    let partial = staged.path().to_path_buf();
    let result = receive_cdn_parts(
        &fixture.job,
        &fixture.dest,
        &staged,
        |_| {
            std::fs::write(media::cancel_marker(&fixture.dest), b"").unwrap();
            Ok(Ok(fixture.encrypted.clone()))
        },
        |_| panic!("unexpected reupload"),
    );
    assert!(result.is_err());
    assert!(std::fs::read(staged.path()).unwrap().is_empty());
    drop(staged);
    assert!(!partial.exists());
    assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"original");
}

#[test]
fn session_replacement_during_fetch_prevents_publication() {
    let fixture = Fixture::new("session");
    let handle = create_client(
        1,
        "fixture".into(),
        fixture.root.join("session").display().to_string(),
    );
    let client = get_client(handle).unwrap();
    let session = client.data.lock().home_session_id;
    let staged = media::StagedDownload::new(&fixture.dest).unwrap();
    let partial = staged.path().to_path_buf();
    receive_cdn_parts(
        &fixture.job,
        &fixture.dest,
        &staged,
        |_| {
            client.data.lock().home_session_id = session.wrapping_add(1);
            Ok(Ok(fixture.encrypted.clone()))
        },
        |_| panic!("unexpected reupload"),
    )
    .unwrap();
    assert!(publish_media(&client, session, staged, &fixture.dest).is_err());
    assert!(!partial.exists());
    assert_eq!(std::fs::read(&fixture.dest).unwrap(), b"original");
    destroy_client(handle);
}
