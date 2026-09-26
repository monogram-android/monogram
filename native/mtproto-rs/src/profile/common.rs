use crate::HashMap;

use tellers_mtproto::latest::api::{
    Chat as TlChat, InputUser, InputUserConstructor, MessagesChats, MessagesGetCommonChatsRequest,
};
use tellers_mtproto_session::Snapshot;

use super::full::{first_username, index_chat_photo};
use crate::MtprotoError;
use crate::api_invoke;
use crate::media::MediaIndex;
use crate::peers::{
    self, CachedPeer, PeerKind, chat_id_for_channel, chat_id_for_chat, vector_boxed_items,
};

/// `messages.getCommonChats` — groups/channels shared with a user, as compact JSON
/// `{"chats":[{id,title,kind,username,avatar}]}`.
/// https://core.telegram.org/method/messages.getCommonChats
pub fn get_common_chats(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    user_id: i64,
    max_id: i64,
    limit: i32,
) -> Result<String, MtprotoError> {
    let cached = peers::require_usable_peer(peers, user_id)?;
    let response: MessagesChats = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetCommonChatsRequest {
            user_id: Box::new(InputUser::InputUser(InputUserConstructor {
                user_id: cached.id,
                access_hash: cached.access_hash,
            })),
            max_id,
            limit: limit.clamp(1, 100),
        },
    )?;
    let chats = match response {
        MessagesChats::MessagesChats(value) => value.chats,
        MessagesChats::MessagesChatsSlice(value) => value.chats,
    };
    let mut out = Vec::new();
    for chat in vector_boxed_items(&chats) {
        let (raw_id, title, kind, username, photo, is_channel) = match chat {
            TlChat::Chat(value) => (
                value.id,
                value.title.clone(),
                "group",
                None,
                Some(&value.photo),
                false,
            ),
            TlChat::Channel(value) => (
                value.id,
                value.title.clone(),
                if value.megagroup.is_some() {
                    "group"
                } else {
                    "channel"
                },
                first_username(&value.username, &value.usernames),
                Some(&value.photo),
                true,
            ),
            _ => continue,
        };
        let peer_chat_id = if is_channel {
            chat_id_for_channel(raw_id)
        } else {
            chat_id_for_chat(raw_id)
        };
        let avatar_cache_key = match photo {
            Some(photo) => index_chat_photo(
                media_index,
                peer_chat_id,
                if is_channel {
                    PeerKind::Channel
                } else {
                    PeerKind::Chat
                },
                raw_id,
                0,
                photo,
            ),
            None => None,
        };
        let mut entry = serde_json::Map::new();
        entry.insert("id".into(), serde_json::Value::from(peer_chat_id));
        entry.insert("title".into(), serde_json::Value::from(title));
        entry.insert("kind".into(), serde_json::Value::from(kind));
        if let Some(username) = username {
            entry.insert("username".into(), serde_json::Value::from(username));
        }
        if let Some(key) = avatar_cache_key {
            entry.insert("avatar".into(), serde_json::Value::from(key));
        }
        out.push(serde_json::Value::Object(entry));
    }
    Ok(serde_json::json!({ "chats": out }).to_string())
}
