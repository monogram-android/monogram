use super::*;

#[test]
fn forget_session_drops_windows_and_prefetch_stamp() {
    let id = i64::MAX - 7;
    store_windows(
        id,
        &[SaltWindow {
            valid_since: 0,
            valid_until: 100,
            salt: 7,
        }],
    );
    mark_requested(id, 1);
    assert_eq!(preferred_salt(id, 10), Some(7));
    forget_session(id);
    assert_eq!(preferred_salt(id, 10), None);
    assert!(should_prefetch(id, 1));
}
