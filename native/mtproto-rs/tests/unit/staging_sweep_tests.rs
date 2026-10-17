use super::*;

#[test]
fn sweep_drops_stale_parts_over_the_cap_and_keeps_the_protected_file() {
    let dir = std::env::temp_dir().join(format!(
        "monogram-staging-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or(0)
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let protect = dir.join("keep.part");
    let stale = dir.join("old.part");
    std::fs::write(&protect, vec![1u8; 64]).unwrap();
    std::fs::write(&stale, vec![2u8; (STAGING_DIR_BYTE_CAP as usize) + 8]).unwrap();
    let old = std::time::SystemTime::now()
        .checked_sub(std::time::Duration::from_secs(STAGING_STALE_SECS + 5))
        .unwrap();
    std::fs::File::options()
        .write(true)
        .open(&stale)
        .unwrap()
        .set_modified(old)
        .unwrap();
    std::fs::File::options()
        .write(true)
        .open(&protect)
        .unwrap()
        .set_modified(old)
        .unwrap();
    let orphan = dir.join("gone.identity.part");
    std::fs::write(&orphan, b"id").unwrap();
    sweep_stale_parts(&dir, &protect);
    assert!(protect.is_file());
    assert!(!stale.exists());
    assert!(!orphan.exists(), "orphan identity sidecar must be removed");
    let _ = std::fs::remove_dir_all(&dir);
}
