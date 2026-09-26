use crate::HashMap;

use tellers_mtproto::latest::api::{
    ChannelParticipant, ChannelParticipantsAdminsConstructor, ChannelParticipantsBotsConstructor,
    ChannelParticipantsFilter, ChannelParticipantsRecentConstructor, ChannelsChannelParticipants,
    ChannelsGetParticipantsRequest, ChatFull, ChatParticipant, ChatParticipants, InputChannel,
    InputChannelConstructor, MessagesChatFull, MessagesGetFullChatRequest, User,
};
use tellers_mtproto_session::Snapshot;

use super::full::{display_user, index_user_avatar, user_from_list};
use crate::MtprotoError;
use crate::api_invoke;
use crate::media::MediaIndex;
use crate::peers::{self, CachedPeer, PeerKind, chat_id_for_user, vector_boxed_items};
use crate::presence::user_status_parts;

pub fn get_group_admin_tags(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
) -> Result<String, MtprotoError> {
    let chat = peers::require_usable_peer(peers, chat_id)?;
    let mut tags = serde_json::Map::new();
    if chat.kind == PeerKind::Channel {
        let response: ChannelsChannelParticipants = api_invoke::invoke_api(
            snapshot,
            api_id,
            ChannelsGetParticipantsRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id: chat.id,
                    access_hash: chat.access_hash,
                })),
                filter: Box::new(ChannelParticipantsFilter::ChannelParticipantsAdmins(
                    ChannelParticipantsAdminsConstructor {},
                )),
                offset: 0,
                limit: 200,
                hash: 0,
            },
        )?;
        if let ChannelsChannelParticipants::ChannelsChannelParticipants(result) = response {
            for participant in vector_boxed_items(&result.participants) {
                if let Some((user_id, tag)) = channel_participant_tag(participant) {
                    tags.insert(user_id.to_string(), serde_json::Value::String(tag));
                }
            }
        }
    } else if chat.kind == PeerKind::Chat {
        let response: MessagesChatFull = api_invoke::invoke_api(
            snapshot,
            api_id,
            MessagesGetFullChatRequest { chat_id: chat.id },
        )?;
        let MessagesChatFull::MessagesChatFull(full) = response;
        if let ChatFull::ChatFull(chat_full) = full.full_chat.as_ref() {
            if let ChatParticipants::ChatParticipants(participants) =
                chat_full.participants.as_ref()
            {
                for participant in vector_boxed_items(&participants.participants) {
                    if let Some((user_id, tag)) = basic_participant_tag(participant) {
                        tags.insert(user_id.to_string(), serde_json::Value::String(tag));
                    }
                }
            }
        }
    }
    Ok(serde_json::Value::Object(tags).to_string())
}

pub(crate) fn custom_or_role(rank: &Option<String>, role: &str) -> String {
    rank.as_ref()
        .filter(|value| !value.is_empty())
        .map(|value| format!("rank:{value}"))
        .unwrap_or_else(|| format!("role:{role}"))
}

pub(crate) fn channel_participant_tag(participant: &ChannelParticipant) -> Option<(i64, String)> {
    match participant {
        ChannelParticipant::ChannelParticipantCreator(value) => {
            Some((value.user_id, custom_or_role(&value.rank, "owner")))
        }
        ChannelParticipant::ChannelParticipantAdmin(value) => {
            Some((value.user_id, custom_or_role(&value.rank, "admin")))
        }
        _ => None,
    }
}

pub(crate) fn basic_participant_tag(participant: &ChatParticipant) -> Option<(i64, String)> {
    match participant {
        ChatParticipant::ChatParticipantCreator(value) => {
            Some((value.user_id, custom_or_role(&value.rank, "owner")))
        }
        ChatParticipant::ChatParticipantAdmin(value) => {
            Some((value.user_id, custom_or_role(&value.rank, "admin")))
        }
        _ => None,
    }
}

pub(crate) fn channel_participant_role(participant: &ChannelParticipant) -> &'static str {
    match participant {
        ChannelParticipant::ChannelParticipantCreator(_) => "owner",
        ChannelParticipant::ChannelParticipantAdmin(_) => "admin",
        _ => "member",
    }
}

pub(crate) fn channel_participant_rank(participant: &ChannelParticipant) -> Option<String> {
    match participant {
        ChannelParticipant::ChannelParticipantCreator(value) => value.rank.clone(),
        ChannelParticipant::ChannelParticipantAdmin(value) => value.rank.clone(),
        _ => None,
    }
}

pub(crate) fn participant_user_id(participant: &ChannelParticipant) -> Option<i64> {
    match participant {
        ChannelParticipant::ChannelParticipant(value) => Some(value.user_id),
        ChannelParticipant::ChannelParticipantSelf(value) => Some(value.user_id),
        ChannelParticipant::ChannelParticipantCreator(value) => Some(value.user_id),
        ChannelParticipant::ChannelParticipantAdmin(value) => Some(value.user_id),
        _ => None,
    }
}

pub(crate) fn member_json(
    media_index: &mut MediaIndex,
    users: &[User],
    user_id: i64,
    role: &str,
    rank: Option<String>,
) -> Option<serde_json::Value> {
    let user = user_from_list(users.iter().cloned(), user_id)?;
    let (title, username) = display_user(&user);
    let peer_id = chat_id_for_user(user_id);
    let avatar_cache_key = index_user_avatar(media_index, peer_id, &user, None, None);
    let (status, status_at) = match &user {
        User::User(u) => user_status_parts(u.status.as_ref().map(|s| s.as_ref())),
        _ => (None, None),
    };
    let mut entry = serde_json::Map::new();
    entry.insert("id".into(), serde_json::Value::from(user_id));
    entry.insert("peer".into(), serde_json::Value::from(peer_id));
    entry.insert("title".into(), serde_json::Value::from(title));
    if let Some(username) = username {
        entry.insert("username".into(), serde_json::Value::from(username));
    }
    if let Some(key) = avatar_cache_key {
        entry.insert("avatar".into(), serde_json::Value::from(key));
    }
    if let Some(status) = status {
        entry.insert("status".into(), serde_json::Value::from(status));
    }
    if let Some(status_at) = status_at {
        entry.insert("status_at".into(), serde_json::Value::from(status_at));
    }
    entry.insert("role".into(), serde_json::Value::from(role));
    if let Some(rank) = rank.filter(|value| !value.is_empty()) {
        entry.insert("rank".into(), serde_json::Value::from(rank));
    }
    Some(serde_json::Value::Object(entry))
}

/// `channels.getParticipants` / `messages.getFullChat` participant page as compact JSON
/// `{"count":N,"members":[{id,peer,title,username,avatar,status,role,rank}]}`.
/// Basic groups always carry the full participant list in `chatFull`.
/// https://core.telegram.org/method/channels.getParticipants
/// https://core.telegram.org/method/messages.getFullChat
pub fn get_participants(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    filter: &str,
    query: &str,
    offset: i32,
    limit: i32,
) -> Result<String, MtprotoError> {
    let chat = peers::require_usable_peer(peers, chat_id)?;
    let limit = limit.clamp(1, 200);
    let mut members = Vec::new();
    let mut count = 0i32;
    if chat.kind == PeerKind::Channel {
        let channel_filter: Box<ChannelParticipantsFilter> = match filter {
            "admins" => Box::new(ChannelParticipantsFilter::ChannelParticipantsAdmins(
                ChannelParticipantsAdminsConstructor {},
            )),
            "bots" => Box::new(ChannelParticipantsFilter::ChannelParticipantsBots(
                ChannelParticipantsBotsConstructor {},
            )),
            _ => Box::new(ChannelParticipantsFilter::ChannelParticipantsRecent(
                ChannelParticipantsRecentConstructor {},
            )),
        };
        let response: ChannelsChannelParticipants = api_invoke::invoke_api(
            snapshot,
            api_id,
            ChannelsGetParticipantsRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id: chat.id,
                    access_hash: chat.access_hash,
                })),
                filter: channel_filter,
                offset: offset.max(0),
                limit,
                hash: 0,
            },
        )?;
        if let ChannelsChannelParticipants::ChannelsChannelParticipants(result) = response {
            count = result.count;
            let users: Vec<User> = vector_boxed_items(&result.users).cloned().collect();
            for participant in vector_boxed_items(&result.participants) {
                let Some(user_id) = participant_user_id(participant) else {
                    continue;
                };
                if let Some(entry) = member_json(
                    media_index,
                    &users,
                    user_id,
                    channel_participant_role(participant),
                    channel_participant_rank(participant),
                ) {
                    members.push(entry);
                }
            }
        }
    } else if chat.kind == PeerKind::Chat {
        let response: MessagesChatFull = api_invoke::invoke_api(
            snapshot,
            api_id,
            MessagesGetFullChatRequest { chat_id: chat.id },
        )?;
        let MessagesChatFull::MessagesChatFull(full) = response;
        let users: Vec<User> = vector_boxed_items(&full.users).cloned().collect();
        if let ChatFull::ChatFull(chat_full) = full.full_chat.as_ref() {
            if let ChatParticipants::ChatParticipants(participants) =
                chat_full.participants.as_ref()
            {
                let all: Vec<&ChatParticipant> =
                    vector_boxed_items(&participants.participants).collect();
                count = all.len() as i32;
                let skip = offset.max(0) as usize;
                for participant in all.into_iter().skip(skip).take(limit as usize) {
                    let (user_id, role, rank) = match participant {
                        ChatParticipant::ChatParticipantCreator(value) => {
                            (value.user_id, "owner", value.rank.clone())
                        }
                        ChatParticipant::ChatParticipantAdmin(value) => {
                            (value.user_id, "admin", value.rank.clone())
                        }
                        ChatParticipant::ChatParticipant(value) => (value.user_id, "member", None),
                        _ => continue,
                    };
                    if let Some(entry) = member_json(media_index, &users, user_id, role, rank) {
                        members.push(entry);
                    }
                }
            }
        }
    } else {
        return Err(MtprotoError::Message("peer has no participants".into()));
    }
    let _ = query;
    Ok(serde_json::json!({ "count": count, "members": members }).to_string())
}
