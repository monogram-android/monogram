//! https://core.telegram.org/method/users.getFullUser
//! https://core.telegram.org/method/messages.getFullChat
//! https://core.telegram.org/method/channels.getFullChannel

use crate::HashMap;

use serde_json::json;
use tellers_mtproto::latest::api::{
    ChannelsGetFullChannelRequest, Chat as TlChat, ChatFull, ChatPhoto, InputChannel,
    InputChannelConstructor, InputUser, InputUserConstructor, InputUserSelfConstructor,
    MessagesChatFull, MessagesGetFullChatRequest, Photo, User, UserFull, UserProfilePhoto,
    Username, UsersGetFullUserRequest, UsersUserFull, Vector,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::emoji_status::emoji_status_document_id;
use crate::media::{self, MediaIndex};
use crate::peers::{
    self, CachedPeer, PeerKind, chat_id_for_channel, chat_id_for_chat, chat_id_for_user,
    vector_boxed_items,
};
use crate::presence::user_status_parts;
use crate::{MtprotoError, ProfileDto};

pub(crate) const CHANNEL_OFFSET: i64 = 1_000_000_000_000;

pub(crate) fn first_username(
    username: &Option<String>,
    usernames: &Option<Box<Vector<Box<Username>>>>,
) -> Option<String> {
    username.clone().filter(|s| !s.is_empty()).or_else(|| {
        let names = usernames.as_ref()?;
        let mut fallback = None;
        for item in vector_boxed_items(names) {
            let Username::Username(n) = item else {
                continue;
            };
            if n.username.is_empty() {
                continue;
            }
            if n.active.is_some() {
                return Some(n.username.clone());
            }
            if fallback.is_none() {
                fallback = Some(n.username.clone());
            }
        }
        fallback
    })
}

pub(crate) fn display_user(user: &User) -> (String, Option<String>) {
    match user {
        User::User(u) => {
            let username = first_username(&u.username, &u.usernames);
            let first = u.first_name.clone().unwrap_or_default();
            let last = u.last_name.clone().unwrap_or_default();
            let title = format!("{first} {last}").trim().to_string();
            let title = if title.is_empty() {
                username.clone().unwrap_or_else(|| format!("User {}", u.id))
            } else {
                title
            };
            (title, username)
        }
        User::UserEmpty(u) => (format!("User {}", u.id), None),
        _ => ("User".into(), None),
    }
}

pub(crate) fn user_from_list(users: impl Iterator<Item = User>, id: i64) -> Option<User> {
    users.into_iter().find(|u| match u {
        User::User(x) => x.id == id,
        User::UserEmpty(x) => x.id == id,
        _ => false,
    })
}

pub(crate) fn index_user_avatar(
    media_index: &mut MediaIndex,
    peer_chat_id: i64,
    user: &User,
    profile_photo: Option<&Photo>,
    personal_photo: Option<&Photo>,
) -> Option<String> {
    let cache_key = format!("avatar:{peer_chat_id}");
    if let Some(photo) = profile_photo.or(personal_photo) {
        if let Some(media) = media::media_ref_from_photo(photo, cache_key.clone()) {
            return Some(media::index_avatar(media_index, peer_chat_id, media));
        }
    }
    if let User::User(u) = user {
        if let Some(photo) = u.photo.as_ref() {
            if let UserProfilePhoto::UserProfilePhoto(p) = photo.as_ref() {
                let access_hash = u.access_hash.unwrap_or(0);
                let has_video = p.has_video.is_some();
                let media = media::media_ref_peer_photo(
                    PeerKind::User,
                    u.id,
                    access_hash,
                    p.photo_id,
                    p.dc_id,
                    media::avatar_cache_key(peer_chat_id, has_video),
                    has_video,
                );
                return Some(media::index_avatar(media_index, peer_chat_id, media));
            }
        }
    }
    None
}

pub(crate) fn index_chat_photo(
    media_index: &mut MediaIndex,
    peer_chat_id: i64,
    kind: PeerKind,
    raw_id: i64,
    access_hash: i64,
    photo: &ChatPhoto,
) -> Option<String> {
    let ChatPhoto::ChatPhoto(p) = photo else {
        return None;
    };
    let has_video = p.has_video.is_some();
    let media = media::media_ref_peer_photo(
        kind,
        raw_id,
        access_hash,
        p.photo_id,
        p.dc_id,
        media::avatar_cache_key(peer_chat_id, has_video),
        has_video,
    );
    Some(media::index_avatar(media_index, peer_chat_id, media))
}

pub(crate) fn fetch_user_full(
    snapshot: &mut Snapshot,
    api_id: i32,
    media_index: &mut MediaIndex,
    input: InputUser,
    is_self: bool,
    expected_id: Option<i64>,
) -> Result<ProfileDto, MtprotoError> {
    let response: UsersUserFull = api_invoke::invoke_api(
        snapshot,
        api_id,
        UsersGetFullUserRequest {
            id: Box::new(input),
        },
    )?;
    let UsersUserFull::UsersUserFull(full) = response else {
        return Err(MtprotoError::Message("unexpected users.userFull".into()));
    };
    let UserFull::UserFull(info) = *full.full_user else {
        return Err(MtprotoError::Message("unexpected userFull".into()));
    };
    let users: Vec<User> = vector_boxed_items(&full.users).cloned().collect();
    let user = expected_id
        .and_then(|id| user_from_list(users.iter().cloned(), id))
        .or_else(|| users.into_iter().next())
        .ok_or_else(|| MtprotoError::Message("userFull missing user".into()))?;
    let id = match &user {
        User::User(u) => u.id,
        User::UserEmpty(u) => u.id,
        _ => info.id,
    };
    let peer_id = chat_id_for_user(id);
    let (title, username) = display_user(&user);
    let (status, status_at) = match &user {
        User::User(u) => user_status_parts(u.status.as_ref().map(|s| s.as_ref())),
        _ => (None, None),
    };
    let avatar_cache_key = index_user_avatar(
        media_index,
        peer_id,
        &user,
        info.profile_photo.as_ref().map(|p| p.as_ref()),
        info.personal_photo.as_ref().map(|p| p.as_ref()),
    );
    let (phone, is_bot, is_verified, is_scam, is_premium) = match &user {
        User::User(u) => (
            u.phone.clone().filter(|s| !s.is_empty()),
            u.bot.is_some(),
            u.verified.is_some(),
            u.scam.is_some() || u.fake.is_some(),
            u.premium.is_some(),
        ),
        _ => (None, false, false, false, false),
    };
    Ok(ProfileDto {
        id: peer_id,
        kind: if is_bot { "bot".into() } else { "user".into() },
        title,
        username,
        about: info.about,
        avatar_cache_key,
        is_self,
        status,
        status_at,
        extra_json: profile_extra_json(
            None,
            None,
            Some(info.common_chats_count).filter(|n| *n > 0),
            phone.as_deref(),
            is_bot,
            is_verified,
            is_scam,
            match &user {
                User::User(u) => {
                    emoji_status_document_id(u.emoji_status.as_ref().map(|s| s.as_ref()))
                }
                _ => None,
            },
            is_premium,
            None,
        ),
    })
}

pub(crate) fn fetch_basic_chat(
    snapshot: &mut Snapshot,
    api_id: i32,
    media_index: &mut MediaIndex,
    chat_id: i64,
) -> Result<ProfileDto, MtprotoError> {
    let response: MessagesChatFull =
        api_invoke::invoke_api(snapshot, api_id, MessagesGetFullChatRequest { chat_id })?;
    let MessagesChatFull::MessagesChatFull(full) = response else {
        return Err(MtprotoError::Message("unexpected messages.chatFull".into()));
    };
    let about = match *full.full_chat {
        ChatFull::ChatFull(c) => Some(c.about).filter(|s| !s.is_empty()),
        ChatFull::ChannelFull(c) => Some(c.about).filter(|s| !s.is_empty()),
        _ => None,
    };
    let peer_id = chat_id_for_chat(chat_id);
    let mut title = format!("Chat {chat_id}");
    let mut avatar_cache_key = None;
    let mut members_count = None;
    for chat in vector_boxed_items(&full.chats) {
        match chat {
            TlChat::Chat(c) if c.id == chat_id => {
                title = c.title.clone();
                members_count = Some(c.participants_count);
                avatar_cache_key =
                    index_chat_photo(media_index, peer_id, PeerKind::Chat, c.id, 0, &c.photo);
                break;
            }
            TlChat::ChatForbidden(c) if c.id == chat_id => {
                title = c.title.clone();
                break;
            }
            _ => {}
        }
    }
    Ok(ProfileDto {
        id: peer_id,
        kind: "chat".into(),
        title,
        username: None,
        about,
        avatar_cache_key,
        is_self: false,
        status: None,
        status_at: None,
        extra_json: profile_extra_json(
            members_count,
            None,
            None,
            None,
            false,
            false,
            false,
            None,
            false,
            None,
        ),
    })
}

pub(crate) fn fetch_channel(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    peer_id: i64,
) -> Result<ProfileDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, peer_id)?;
    if cached.kind != PeerKind::Channel {
        return Err(MtprotoError::Message("peer is not a channel".into()));
    }
    let response: MessagesChatFull = api_invoke::invoke_api(
        snapshot,
        api_id,
        ChannelsGetFullChannelRequest {
            channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                channel_id: cached.id,
                access_hash: cached.access_hash,
            })),
        },
    )?;
    let MessagesChatFull::MessagesChatFull(full) = response else {
        return Err(MtprotoError::Message(
            "unexpected channels.getFullChannel result".into(),
        ));
    };
    let (about, members_count, online_count, can_view_participants) = match *full.full_chat {
        ChatFull::ChannelFull(c) => (
            Some(c.about.clone()).filter(|s| !s.is_empty()),
            c.participants_count,
            c.online_count,
            Some(c.can_view_participants.is_some()),
        ),
        ChatFull::ChatFull(c) => (
            Some(c.about.clone()).filter(|s| !s.is_empty()),
            None,
            None,
            None,
        ),
        _ => (None, None, None, None),
    };
    let mut title = format!("Channel {}", cached.id);
    let mut username = None;
    let mut avatar_cache_key = None;
    let mut kind = "channel".to_string();
    let mut verified = false;
    let mut scam = false;
    let mut emoji_status = None;
    for chat in vector_boxed_items(&full.chats) {
        match chat {
            TlChat::Channel(c) if c.id == cached.id => {
                title = c.title.clone();
                username = first_username(&c.username, &c.usernames);
                emoji_status =
                    emoji_status_document_id(c.emoji_status.as_ref().map(|s| s.as_ref()));
                if c.megagroup.is_some() {
                    kind = "group".into();
                }
                verified = c.verified.is_some();
                scam = c.scam.is_some() || c.fake.is_some();
                avatar_cache_key = index_chat_photo(
                    media_index,
                    peer_id,
                    PeerKind::Channel,
                    c.id,
                    c.access_hash.unwrap_or(cached.access_hash),
                    &c.photo,
                );
                break;
            }
            TlChat::ChannelForbidden(c) if c.id == cached.id => {
                title = c.title.clone();
                break;
            }
            _ => {}
        }
    }
    // Broadcast channels expose the subscriber list only when the server allows it;
    // megagroups are attempted anyway and hidden on failure.
    let participants_visible = if kind == "channel" {
        can_view_participants
    } else {
        None
    };
    Ok(ProfileDto {
        id: chat_id_for_channel(cached.id),
        kind,
        title,
        username,
        about,
        avatar_cache_key,
        is_self: false,
        status: None,
        status_at: None,
        extra_json: profile_extra_json(
            members_count,
            online_count,
            None,
            None,
            false,
            verified,
            scam,
            emoji_status,
            false,
            participants_visible,
        ),
    })
}

pub fn get_profile(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    self_user_id: Option<i64>,
    peer_id: i64,
) -> Result<ProfileDto, MtprotoError> {
    let is_self = peer_id == 0 || self_user_id == Some(peer_id);
    if is_self {
        return fetch_user_full(
            snapshot,
            api_id,
            media_index,
            InputUser::InputUserSelf(InputUserSelfConstructor {}),
            true,
            self_user_id,
        );
    }
    if peer_id > 0 {
        let cached = peers::require_usable_peer(peers, peer_id)?;
        return fetch_user_full(
            snapshot,
            api_id,
            media_index,
            InputUser::InputUser(InputUserConstructor {
                user_id: peer_id,
                access_hash: cached.access_hash,
            }),
            false,
            Some(peer_id),
        );
    }
    if peer_id > -CHANNEL_OFFSET {
        return fetch_basic_chat(snapshot, api_id, media_index, -peer_id);
    }
    fetch_channel(snapshot, api_id, peers, media_index, peer_id)
}

pub(crate) fn profile_extra_json(
    members: Option<i32>,
    online: Option<i32>,
    common: Option<i32>,
    phone: Option<&str>,
    bot: bool,
    verified: bool,
    scam: bool,
    emoji: Option<i64>,
    premium: bool,
    participants: Option<bool>,
) -> Option<String> {
    if members.is_none()
        && online.is_none()
        && common.is_none()
        && phone.is_none()
        && !bot
        && !verified
        && !scam
        && emoji.is_none()
        && !premium
        && participants.is_none()
    {
        return None;
    }
    Some(
        json!({
            "members": members,
            "online": online,
            "common": common,
            "phone": phone,
            "bot": bot,
            "verified": verified,
            "scam": scam,
            "emoji": emoji,
            "premium": premium,
            "participants": participants,
        })
        .to_string(),
    )
}
