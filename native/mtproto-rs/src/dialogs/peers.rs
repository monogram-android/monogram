use crate::{HashMap, HashMapExt};

use tellers_mtproto::latest::api::{Chat as TlChat, ChatPhoto, Peer, User, UserProfilePhoto};

use super::permissions::{
    admin_can_delete, admin_can_post, banned_media, banned_photos, banned_plain, banned_send,
    banned_view, resolve_permissions,
};
use crate::media::{self, MediaIndex};
use crate::peers::{
    self, CachedPeer, PeerKind, chat_id_for_channel, chat_id_for_chat, chat_id_for_user,
};

pub(crate) fn display_name(user: &User) -> crate::CompactString {
    match user {
        User::User(u) => {
            let first = u.first_name.as_deref().unwrap_or("");
            let last = u.last_name.as_deref().unwrap_or("");
            let full = format!("{first} {last}");
            let full = full.trim();
            if !full.is_empty() {
                crate::CompactString::from(full)
            } else {
                u.username
                    .as_deref()
                    .map(crate::CompactString::from)
                    .unwrap_or_else(|| crate::CompactString::from(format!("User {}", u.id)))
            }
        }
        User::UserEmpty(u) => crate::CompactString::from(format!("User {}", u.id)),
        _ => crate::CompactString::from("User"),
    }
}

pub(crate) fn cache_from_users_chats(
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    users: impl Iterator<Item = User>,
    chats: impl Iterator<Item = TlChat>,
) {
    for user in users {
        if let User::User(u) = user {
            let chat_id = chat_id_for_user(u.id);
            peers::upsert_cached_peer(
                peers,
                chat_id,
                PeerKind::User,
                u.id,
                u.access_hash.unwrap_or(0),
                u.min.is_some(),
            );
            if let Some(photo) = u.photo.as_ref() {
                if let UserProfilePhoto::UserProfilePhoto(p) = photo.as_ref() {
                    if let Some(hash) = u.access_hash.filter(|&h| h != 0) {
                        if u.min.is_none() || !media_index.contains_key(&(chat_id, 0)) {
                            index_dialog_avatar(
                                media_index,
                                chat_id,
                                PeerKind::User,
                                u.id,
                                hash,
                                p.photo_id,
                                p.dc_id,
                                p.has_video.is_some(),
                            );
                        }
                    }
                }
            }
        }
    }
    for chat in chats {
        match chat {
            TlChat::Chat(c) => {
                let chat_id = chat_id_for_chat(c.id);
                peers::upsert_cached_peer(peers, chat_id, PeerKind::Chat, c.id, 0, false);
                if let ChatPhoto::ChatPhoto(p) = &*c.photo {
                    index_dialog_avatar(
                        media_index,
                        chat_id,
                        PeerKind::Chat,
                        c.id,
                        0,
                        p.photo_id,
                        p.dc_id,
                        p.has_video.is_some(),
                    );
                }
            }
            TlChat::Channel(c) => {
                let chat_id = chat_id_for_channel(c.id);
                peers::upsert_cached_peer(
                    peers,
                    chat_id,
                    PeerKind::Channel,
                    c.id,
                    c.access_hash.unwrap_or(0),
                    c.min.is_some(),
                );
                if let ChatPhoto::ChatPhoto(p) = &*c.photo {
                    if let Some(hash) = c.access_hash.filter(|&h| h != 0) {
                        if c.min.is_none() || !media_index.contains_key(&(chat_id, 0)) {
                            index_dialog_avatar(
                                media_index,
                                chat_id,
                                PeerKind::Channel,
                                c.id,
                                hash,
                                p.photo_id,
                                p.dc_id,
                                p.has_video.is_some(),
                            );
                        }
                    }
                }
            }
            _ => {}
        }
    }
    media::fill_zero_peer_photo_hashes(media_index, peers);
}

pub(crate) fn index_dialog_avatar(
    media_index: &mut MediaIndex,
    chat_id: i64,
    kind: PeerKind,
    raw_id: i64,
    access_hash: i64,
    photo_id: i64,
    dc_id: i32,
    has_video: bool,
) -> String {
    let cache_key = media::avatar_cache_key(chat_id, has_video);
    let media = media::media_ref_peer_photo(
        kind,
        raw_id,
        access_hash,
        photo_id,
        dc_id,
        cache_key,
        has_video,
    );
    media::index_avatar(media_index, chat_id, media)
}

#[derive(Clone)]
pub(crate) struct ChatMeta {
    pub(crate) title: crate::CompactString,
    pub(crate) is_channel: bool,
    pub(crate) is_group: bool,
    pub(crate) is_forum: bool,
    pub(crate) left: bool,
    pub(crate) can_view: bool,
    pub(crate) can_send_plain: bool,
    pub(crate) can_send_photos: bool,
    pub(crate) can_forward: bool,
    pub(crate) can_delete_others: bool,
}

pub(crate) fn user_chat_meta(title: impl Into<crate::CompactString>) -> ChatMeta {
    ChatMeta {
        title: title.into(),
        is_channel: false,
        is_group: false,
        is_forum: false,
        left: false,
        can_view: true,
        can_send_plain: true,
        can_send_photos: true,
        can_forward: true,
        can_delete_others: false,
    }
}

pub(crate) fn title_for_peer(
    peer: &Peer,
    users: &HashMap<i64, crate::CompactString>,
    chats: &HashMap<i64, ChatMeta>,
) -> ChatMeta {
    match peer {
        Peer::PeerUser(u) => user_chat_meta(
            users
                .get(&u.user_id)
                .cloned()
                .unwrap_or_else(|| crate::CompactString::from(format!("User {}", u.user_id))),
        ),
        Peer::PeerChat(c) => chats.get(&c.chat_id).cloned().unwrap_or_else(|| ChatMeta {
            title: crate::CompactString::from(format!("Chat {}", c.chat_id)),
            is_channel: false,
            is_group: true,
            is_forum: false,
            // Unknown membership: keep the dialog rather than hiding it by guess.
            left: false,
            can_view: true,
            can_send_plain: true,
            can_send_photos: true,
            can_forward: true,
            can_delete_others: false,
        }),
        Peer::PeerChannel(c) => chats
            .get(&c.channel_id)
            .cloned()
            .unwrap_or_else(|| ChatMeta {
                title: crate::CompactString::from(format!("Channel {}", c.channel_id)),
                is_channel: true,
                is_group: false,
                is_forum: false,
                left: false,
                can_view: true,
                can_send_plain: false,
                can_send_photos: false,
                can_forward: true,
                can_delete_others: false,
            }),
        _ => user_chat_meta(crate::CompactString::from("Chat")),
    }
}

pub(crate) fn index_users_chats(
    users: impl Iterator<Item = User>,
    chats: impl Iterator<Item = TlChat>,
) -> (HashMap<i64, crate::CompactString>, HashMap<i64, ChatMeta>) {
    let mut user_names = HashMap::new();
    for user in users {
        match &user {
            User::User(u) => {
                user_names.insert(u.id, display_name(&user));
            }
            User::UserEmpty(u) => {
                user_names.insert(u.id, crate::CompactString::from(format!("User {}", u.id)));
            }
            _ => {}
        }
    }
    let mut chat_meta = HashMap::new();
    for chat in chats {
        if let Some((id, meta)) = chat_meta_from_tl(&chat) {
            chat_meta.insert(id, meta);
        }
    }
    (user_names, chat_meta)
}

pub(crate) fn chat_meta_from_tl(chat: &TlChat) -> Option<(i64, ChatMeta)> {
    match chat {
        TlChat::Chat(c) => {
            let (can_view, can_send_plain, can_send_photos, can_forward, can_delete_others) =
                resolve_permissions(
                    false,
                    c.left.is_some(),
                    false,
                    c.creator.is_some(),
                    false,
                    admin_can_delete(c.admin_rights.as_deref()),
                    c.admin_rights.is_some() || c.creator.is_some(),
                    c.noforwards.is_some(),
                    false,
                    false,
                    false,
                    false,
                    false,
                    banned_send(c.default_banned_rights.as_deref()),
                    banned_plain(c.default_banned_rights.as_deref()),
                    banned_media(c.default_banned_rights.as_deref()),
                    banned_photos(c.default_banned_rights.as_deref()),
                );
            Some((
                c.id,
                ChatMeta {
                    title: crate::CompactString::from(c.title.clone()),
                    is_channel: false,
                    is_group: true,
                    is_forum: false,
                    left: c.left.is_some() || c.deactivated.is_some(),
                    can_view,
                    can_send_plain,
                    can_send_photos,
                    can_forward,
                    can_delete_others,
                },
            ))
        }
        TlChat::Channel(c) => {
            let is_channel = c.broadcast.is_some() && c.megagroup.is_none();
            let is_group = !is_channel;
            let (can_view, can_send_plain, can_send_photos, can_forward, can_delete_others) =
                resolve_permissions(
                    false,
                    c.left.is_some() || c.join_to_send.is_some() && c.left.is_some(),
                    is_channel,
                    c.creator.is_some(),
                    admin_can_post(c.admin_rights.as_deref()),
                    admin_can_delete(c.admin_rights.as_deref()),
                    c.admin_rights.is_some() || c.creator.is_some(),
                    c.noforwards.is_some(),
                    banned_view(c.banned_rights.as_deref()),
                    banned_send(c.banned_rights.as_deref()),
                    banned_plain(c.banned_rights.as_deref()),
                    banned_media(c.banned_rights.as_deref()),
                    banned_photos(c.banned_rights.as_deref()),
                    banned_send(c.default_banned_rights.as_deref()),
                    banned_plain(c.default_banned_rights.as_deref()),
                    banned_media(c.default_banned_rights.as_deref()),
                    banned_photos(c.default_banned_rights.as_deref()),
                );
            let is_forum = (c.forum.is_some() || c.forum_tabs.is_some())
                && c.monoforum.is_none()
                && !is_channel;
            Some((
                c.id,
                ChatMeta {
                    title: crate::CompactString::from(c.title.clone()),
                    is_channel,
                    is_group,
                    is_forum,
                    left: c.left.is_some(),
                    can_view,
                    can_send_plain,
                    can_send_photos,
                    can_forward,
                    can_delete_others,
                },
            ))
        }
        TlChat::ChatForbidden(c) => Some((
            c.id,
            ChatMeta {
                title: crate::CompactString::from(c.title.clone()),
                is_channel: false,
                is_group: true,
                is_forum: false,
                left: true,
                can_view: false,
                can_send_plain: false,
                can_send_photos: false,
                can_forward: false,
                can_delete_others: false,
            },
        )),
        TlChat::ChannelForbidden(c) => Some((
            c.id,
            ChatMeta {
                title: crate::CompactString::from(c.title.clone()),
                is_channel: true,
                is_group: false,
                is_forum: false,
                left: true,
                can_view: false,
                can_send_plain: false,
                can_send_photos: false,
                can_forward: false,
                can_delete_others: false,
            },
        )),
        _ => None,
    }
}
