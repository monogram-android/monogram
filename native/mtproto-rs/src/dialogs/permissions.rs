use tellers_mtproto::latest::api::{ChatAdminRights, ChatBannedRights};

pub(crate) fn banned_rights(
    rights: Option<&ChatBannedRights>,
) -> Option<&tellers_mtproto::latest::api::ChatBannedRightsConstructor> {
    match rights? {
        ChatBannedRights::ChatBannedRights(c) => Some(c),
        _ => None,
    }
}

pub(crate) fn admin_rights(
    rights: Option<&ChatAdminRights>,
) -> Option<&tellers_mtproto::latest::api::ChatAdminRightsConstructor> {
    match rights? {
        ChatAdminRights::ChatAdminRights(c) => Some(c),
        _ => None,
    }
}

pub(crate) fn banned_view(rights: Option<&ChatBannedRights>) -> bool {
    banned_rights(rights).is_some_and(|r| r.view_messages.is_some())
}
pub(crate) fn banned_send(rights: Option<&ChatBannedRights>) -> bool {
    banned_rights(rights).is_some_and(|r| r.send_messages.is_some())
}
pub(crate) fn banned_plain(rights: Option<&ChatBannedRights>) -> bool {
    banned_rights(rights).is_some_and(|r| r.send_plain.is_some())
}
pub(crate) fn banned_media(rights: Option<&ChatBannedRights>) -> bool {
    banned_rights(rights).is_some_and(|r| r.send_media.is_some())
}
pub(crate) fn banned_photos(rights: Option<&ChatBannedRights>) -> bool {
    banned_rights(rights).is_some_and(|r| r.send_photos.is_some())
}
pub(crate) fn admin_can_post(rights: Option<&ChatAdminRights>) -> bool {
    admin_rights(rights).is_some_and(|r| r.post_messages.is_some())
}
pub(crate) fn admin_can_delete(rights: Option<&ChatAdminRights>) -> bool {
    admin_rights(rights).is_some_and(|r| r.delete_messages.is_some())
}

/// Resolves effective permissions inverted from
/// chatBannedRights flags (https://core.telegram.org/constructor/chatBannedRights).
pub(crate) fn resolve_permissions(
    forbidden: bool,
    left: bool,
    broadcast: bool,
    creator: bool,
    admin_post: bool,
    admin_delete: bool,
    admin_present: bool,
    noforwards: bool,
    ban_view: bool,
    ban_send: bool,
    ban_plain: bool,
    ban_media: bool,
    ban_photos: bool,
    def_send: bool,
    def_plain: bool,
    def_media: bool,
    def_photos: bool,
) -> (bool, bool, bool, bool, bool) {
    let can_view = !forbidden && !ban_view;
    if !can_view {
        return (false, false, false, false, false);
    }
    let can_forward = !noforwards;
    let can_delete_others = creator || admin_delete;
    if broadcast {
        let can_send = creator || admin_post;
        return (true, can_send, can_send, can_forward, can_delete_others);
    }
    if left {
        return (true, false, false, can_forward, can_delete_others);
    }
    let override_default = creator || admin_present;
    let send_blocked = ban_send || (!override_default && def_send);
    let plain_blocked = ban_plain || (!override_default && def_plain);
    let media_blocked = ban_media || (!override_default && def_media);
    let photos_blocked = ban_photos || (!override_default && def_photos);
    (
        true,
        !send_blocked && !plain_blocked,
        !send_blocked && !media_blocked && !photos_blocked,
        can_forward,
        can_delete_others,
    )
}
