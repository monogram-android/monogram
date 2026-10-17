use super::*;

#[test]
fn idle_destination_lock_leaves_the_map() {
    let path = std::env::temp_dir().join(format!(
        "monogram-dest-lock-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or(0)
    ));
    let before = destination_lock_entries();
    {
        let outer = DestinationLock::acquire(&path);
        let inner = DestinationLock::acquire(&path);
        assert!(destination_lock_entries() >= before + 1);
        drop(inner);
        assert!(destination_lock_entries() >= before + 1);
        drop(outer);
    }
    assert_eq!(destination_lock_entries(), before);
}
