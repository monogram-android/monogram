use super::resolve_permissions;

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
