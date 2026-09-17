//! https://core.telegram.org/method/messages.getDialogs
//! https://core.telegram.org/api/folders

use crate::{HashMap, HashMapExt, HashSet, HashSetExt};

use tellers_mtproto::latest::api::{
    Chat as TlChat, ChatPhoto, Dialog, InputPeer, InputPeerEmptyConstructor, Message,
    MessagesDialogs, MessagesGetDialogsRequest, PeerNotifySettings, True, TrueConstructor, User,
    UserProfilePhoto,
};
use tellers_mtproto_session::Snapshot;

use super::peers::{
    cache_from_users_chats, index_dialog_avatar, index_users_chats, title_for_peer,
};
use super::preview::message_preview;
use crate::api_invoke;
use crate::emoji_status::emoji_status_document_id;
use crate::media::{self, MediaIndex};
use crate::peers::{
    self, CachedPeer, PeerKind, chat_id_for_channel, chat_id_for_chat, chat_id_for_user,
    input_peer_from_cached, peer_chat_id, vector_boxed_items,
};
use crate::presence::user_status_parts;
use crate::{ChatDto, MtprotoError};

pub(crate) fn is_muted(settings: &PeerNotifySettings) -> bool {
    let PeerNotifySettings::PeerNotifySettings(s) = settings else {
        return false;
    };
    let until = s.mute_until.unwrap_or(0);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i32)
        .unwrap_or(0);
    until > now
}

/// True when the dialog carries its own mute setting instead of inheriting the peer type default
/// (`account.getNotifySettings` for users/chats/broadcasts). Telegram clients treat a missing
/// `mute_until` flag as "inherited", and an explicitly past `mute_until` as "not muted".
pub(crate) fn is_mute_override(settings: &PeerNotifySettings) -> bool {
    let PeerNotifySettings::PeerNotifySettings(s) = settings else {
        return false;
    };
    s.mute_until.is_some()
}

pub fn get_dialogs(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    channel_pts: &mut HashMap<i64, i32>,
    offset_date: i32,
    offset_id: i32,
    offset_peer_id: i64,
    folder_id: Option<i32>,
) -> Result<Vec<ChatDto>, MtprotoError> {
    let offset_peer = if offset_peer_id == 0 {
        InputPeer::InputPeerEmpty(InputPeerEmptyConstructor {})
    } else {
        let cached = peers::require_usable_peer(peers, offset_peer_id)?;
        input_peer_from_cached(cached)
    };
    let folder_present = folder_id.is_some();
    let request = MessagesGetDialogsRequest {
        // flags.0 exclude_pinned, flags.1 folder_id. Both reference clients always send
        // exclude_pinned together with folder_id, so mirror that shape exactly.
        flags: if folder_present { 3 } else { 0 },
        exclude_pinned: folder_present.then(|| Box::new(True::True(TrueConstructor {}))),
        folder_id,
        offset_date,
        offset_id,
        offset_peer: Box::new(offset_peer),
        limit: 40,
        hash: 0,
    };
    let response: MessagesDialogs = api_invoke::invoke_api(snapshot, api_id, request)?;
    let (dialogs, messages, chats, users) = match response {
        MessagesDialogs::MessagesDialogs(d) => (d.dialogs, d.messages, d.chats, d.users),
        MessagesDialogs::MessagesDialogsSlice(d) => (d.dialogs, d.messages, d.chats, d.users),
        MessagesDialogs::MessagesDialogsNotModified(_) => {
            return Ok(Vec::new());
        }
        _ => return Err(MtprotoError::Message("unexpected messages.dialogs".into())),
    };

    let user_list: Vec<User> = vector_boxed_items(&users).cloned().collect();
    let chat_list: Vec<TlChat> = vector_boxed_items(&chats).cloned().collect();
    cache_from_users_chats(
        peers,
        media_index,
        user_list.iter().cloned(),
        chat_list.iter().cloned(),
    );
    let mut extras: HashMap<
        i64,
        (
            bool,
            bool,
            Option<String>,
            Option<String>,
            Option<i64>,
            Option<i64>,
        ),
    > = HashMap::new();
    let mut verified_ids: HashSet<i64> = HashSet::new();
    for user in &user_list {
        if let User::User(u) = user {
            let chat_id = chat_id_for_user(u.id);
            if u.verified.is_some() {
                verified_ids.insert(chat_id);
            }
            let photo_key = u.photo.as_ref().and_then(|photo| {
                let UserProfilePhoto::UserProfilePhoto(p) = photo.as_ref() else {
                    return None;
                };
                let hash = u.access_hash.filter(|&h| h != 0)?;
                Some(index_dialog_avatar(
                    media_index,
                    chat_id,
                    PeerKind::User,
                    u.id,
                    hash,
                    p.photo_id,
                    p.dc_id,
                    p.has_video.is_some(),
                ))
            });
            let (peer_status, peer_status_at) =
                user_status_parts(u.status.as_ref().map(|s| s.as_ref()));
            extras.insert(
                chat_id,
                (
                    u.contact.is_some(),
                    u.bot.is_some(),
                    photo_key,
                    peer_status,
                    peer_status_at,
                    emoji_status_document_id(u.emoji_status.as_ref().map(|s| s.as_ref())),
                ),
            );
        }
    }
    for chat in &chat_list {
        match chat {
            TlChat::Chat(c) => {
                let chat_id = chat_id_for_chat(c.id);
                let photo_key = match &*c.photo {
                    ChatPhoto::ChatPhoto(p) => Some(index_dialog_avatar(
                        media_index,
                        chat_id,
                        PeerKind::Chat,
                        c.id,
                        0,
                        p.photo_id,
                        p.dc_id,
                        p.has_video.is_some(),
                    )),
                    _ => None,
                };
                extras.insert(chat_id, (false, false, photo_key, None, None, None));
            }
            TlChat::Channel(c) => {
                let chat_id = chat_id_for_channel(c.id);
                if c.verified.is_some() {
                    verified_ids.insert(chat_id);
                }
                let photo_key = match &*c.photo {
                    ChatPhoto::ChatPhoto(p) => c.access_hash.filter(|&h| h != 0).map(|hash| {
                        index_dialog_avatar(
                            media_index,
                            chat_id,
                            PeerKind::Channel,
                            c.id,
                            hash,
                            p.photo_id,
                            p.dc_id,
                            p.has_video.is_some(),
                        )
                    }),
                    _ => None,
                };
                extras.insert(
                    chat_id,
                    (
                        false,
                        false,
                        photo_key,
                        None,
                        None,
                        emoji_status_document_id(c.emoji_status.as_ref().map(|s| s.as_ref())),
                    ),
                );
            }
            _ => {}
        }
    }
    let (user_names, chat_meta) = index_users_chats(user_list.into_iter(), chat_list.into_iter());

    let mut last_by_peer: HashMap<i64, (Option<String>, Option<i64>, Option<String>, i32, bool)> =
        HashMap::new();
    for msg in vector_boxed_items(&messages) {
        let indexed = media::index_message_media(msg, media_index);
        if let Some((id, date, text, outgoing)) =
            message_preview(msg, &user_names, &chat_meta, &indexed)
        {
            let chat_id = match msg {
                Message::Message(m) => peer_chat_id(&m.peer_id),
                Message::MessageService(m) => peer_chat_id(&m.peer_id),
                _ => continue,
            };
            let thumb = indexed.thumb_cache_key;
            last_by_peer
                .entry(chat_id)
                .and_modify(|e| {
                    if e.1.map(|d| date >= d).unwrap_or(true) {
                        *e = (text.clone(), Some(date), thumb.clone(), id, outgoing);
                    }
                })
                .or_insert((text, Some(date), thumb, id, outgoing));
        }
    }

    let mut out = Vec::new();
    for dialog in vector_boxed_items(&dialogs) {
        let Dialog::Dialog(d) = dialog else {
            continue;
        };
        let chat_id = peer_chat_id(&d.peer);
        let meta = title_for_peer(&d.peer, &user_names, &chat_meta);
        let title = meta.title.clone();
        let is_channel = meta.is_channel;
        let is_group = meta.is_group;
        if is_channel {
            merge_channel_pts(channel_pts, chat_id, d.pts);
        }
        let (preview, date, last_thumb, last_id, last_outgoing) = last_by_peer
            .remove(&chat_id)
            .unwrap_or((None, None, None, 0, false));
        let (
            is_contact,
            is_bot,
            photo_cache_key,
            peer_status,
            peer_status_at,
            emoji_status_document_id,
        ) = extras
            .get(&chat_id)
            .cloned()
            .unwrap_or((false, false, None, None, None, None));
        out.push(ChatDto {
            id: chat_id,
            title,
            is_channel,
            is_group,
            is_forum: meta.is_forum,
            left: meta.left,
            unread_count: d.unread_count,
            last_message_preview: preview,
            last_message_date: date,
            archived: d.folder_id == Some(1),
            muted: is_muted(&d.notify_settings),
            mute_override: is_mute_override(&d.notify_settings),
            unread_mark: d.unread_mark.is_some(),
            unread_mentions_count: d.unread_mentions_count,
            unread_reactions_count: d.unread_reactions_count,
            is_contact,
            is_bot,
            is_verified: verified_ids.contains(&chat_id),
            photo_cache_key,
            pinned: d.pinned.is_some(),
            read_inbox_max_id: d.read_inbox_max_id,
            read_outbox_max_id: d.read_outbox_max_id,
            peer_status,
            peer_status_at,
            last_media_thumb_cache_key: last_thumb,
            last_message_id: last_id,
            can_view: meta.can_view,
            can_send_plain: meta.can_send_plain,
            can_send_photos: meta.can_send_photos,
            can_forward: meta.can_forward,
            can_delete_others: meta.can_delete_others,
            emoji_status_document_id,
            last_message_outgoing: last_outgoing,
        });
    }
    Ok(out)
}

/// Dialog `pts` is the last channel cursor supplied with the dialog list.
/// Keep only positive, monotonic values so an older page cannot rewind a
/// recovered channel before `updates.getChannelDifference` runs.
pub(crate) fn merge_channel_pts(
    channel_pts: &mut HashMap<i64, i32>,
    chat_id: i64,
    dialog_pts: Option<i32>,
) {
    let Some(dialog_pts) = dialog_pts.filter(|pts| *pts > 0) else {
        return;
    };
    channel_pts
        .entry(chat_id)
        .and_modify(|current| *current = (*current).max(dialog_pts))
        .or_insert(dialog_pts);
}
