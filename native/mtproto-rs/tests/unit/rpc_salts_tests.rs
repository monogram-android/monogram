use super::{SaltWindow, choose_salt};

#[test]
fn prefetch_retries_when_the_answer_never_arrives() {
    use super::{SaltWindow, mark_requested, should_prefetch, store_windows};
    let session = 91;
    assert!(should_prefetch(session, 1_000));
    mark_requested(session, 1_000);
    assert!(!should_prefetch(session, 1_010));
    assert!(should_prefetch(session, 1_030));
    store_windows(
        session,
        &[SaltWindow {
            valid_since: 0,
            valid_until: 2_000,
            salt: 5,
        }],
    );
    assert!(!should_prefetch(session, 1_100));
}

#[test]
fn longest_remaining_valid_salt_wins() {
    let windows = [
        SaltWindow {
            valid_since: 0,
            valid_until: 100,
            salt: 1,
        },
        SaltWindow {
            valid_since: 50,
            valid_until: 200,
            salt: 2,
        },
        SaltWindow {
            valid_since: 300,
            valid_until: 400,
            salt: 3,
        },
    ];
    assert_eq!(choose_salt(&windows, 60), Some(2));
    assert_eq!(choose_salt(&windows, 10), Some(1));
    assert_eq!(choose_salt(&windows, 250), None);
}
