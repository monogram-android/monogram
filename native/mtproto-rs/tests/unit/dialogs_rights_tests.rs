use super::resolve_permissions;

#[test]
fn poll_permissions_are_independent_of_plain_text_and_respect_membership_and_admins() {
    use super::permissions::resolve_poll_permission as allowed;
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    use tellers_mtproto::latest::api::{ChatBannedRights, ChatBannedRightsConstructor as Rights};
    let decode = |flags: u32| {
        let bytes = [
            Rights::ID.to_le_bytes(),
            flags.to_le_bytes(),
            0_i32.to_le_bytes(),
        ]
        .concat();
        ChatBannedRights::decode(&mut Decoder::new(&bytes, Limits::default()).unwrap()).unwrap()
    };
    let plain = decode(Rights::SEND_PLAIN_FLAG);
    let polls = decode(Rights::SEND_POLLS_FLAG);
    let send = decode(Rights::SEND_MESSAGES_FLAG);
    let view = decode(Rights::VIEW_MESSAGES_FLAG);
    assert!(allowed(
        false,
        false,
        false,
        false,
        false,
        Some(&plain),
        Some(&plain)
    ));
    assert!(!allowed(
        false,
        false,
        false,
        false,
        false,
        None,
        Some(&polls)
    ));
    assert!(allowed(
        false,
        false,
        false,
        true,
        false,
        None,
        Some(&polls)
    ));
    assert!(allowed(false, false, true, false, false, None, Some(&send)));
    assert!(!allowed(
        false,
        false,
        false,
        true,
        false,
        Some(&polls),
        None
    ));
    assert!(!allowed(
        false,
        false,
        false,
        true,
        false,
        Some(&send),
        None
    ));
    assert!(!allowed(
        false,
        false,
        false,
        true,
        false,
        Some(&view),
        None
    ));
    assert!(!allowed(true, false, true, true, true, None, None));
    assert!(!allowed(false, true, false, true, false, None, None));
    assert!(allowed(false, true, false, true, true, None, Some(&polls)));
}

#[test]
fn broadcast_member_cannot_send() {
    let r = resolve_permissions(
        false, false, true, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false,
    );
    assert_eq!(r, (true, false, false, true, false));
}

#[test]
fn broadcast_admin_can_post() {
    let r = resolve_permissions(
        false, false, true, false, true, true, true, false, false, false, false, false, false,
        true, true, true, true,
    );
    assert_eq!(r, (true, true, true, true, true));
}

#[test]
fn default_ban_plain_blocks_text_not_photos() {
    let r = resolve_permissions(
        false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, true, false, false,
    );
    assert_eq!(r.1, false);
    assert_eq!(r.2, true);
    assert_eq!(r.3, true);
}

#[test]
fn admin_overrides_default_ban() {
    let r = resolve_permissions(
        false, false, false, false, false, false, true, false, false, false, false, false, false,
        true, true, true, true,
    );
    assert_eq!(r, (true, true, true, true, false));
}

#[test]
fn noforwards_and_kicked() {
    let protected = resolve_permissions(
        false, false, false, false, false, false, false, true, false, false, false, false, false,
        false, false, false, false,
    );
    assert!(!protected.3);
    let kicked = resolve_permissions(
        false, false, false, false, false, false, false, false, true, true, true, true, true,
        false, false, false, false,
    );
    assert_eq!(kicked, (false, false, false, false, false));
}
