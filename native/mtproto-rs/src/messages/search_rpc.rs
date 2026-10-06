//! Global and people search.
//! https://core.telegram.org/api/search
//! https://core.telegram.org/api/offsets
//! https://core.telegram.org/method/contacts.search
//! https://core.telegram.org/method/messages.searchGlobal

use crate::{HashMap, HashMapExt};

use tellers_mtproto::latest::api::{
    Chat as TlChat, ContactsFound, ContactsSearchRequest, InputMessagesFilterEmptyConstructor,
    InputPeer, InputPeerEmptyConstructor, MessagesFilter, MessagesSearchGlobalRequest, Peer, User,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::dialogs;
use crate::media::MediaIndex;
use crate::messages::clamp_search_limit;
use crate::peers::{self, CachedPeer, input_peer_from_cached, peer_chat_id, vector_boxed_items};
use crate::{ContactsSearchDto, GlobalMessageSearchDto, MessageDto, MtprotoError, SearchPeerDto};

pub fn is_people_peer(peer: &Peer) -> bool {
    matches!(peer, Peer::PeerUser(_))
}

pub fn next_global_page(messages: &[MessageDto], next_rate: i32) -> (i32, i64, i32) {
    match messages.last() {
        Some(last) => (next_rate, last.chat_id, last.id),
        None => (0, 0, 0),
    }
}

pub fn contacts_search_query(query: &str) -> Result<String, MtprotoError> {
    let q = query.trim().to_string();
    if q.is_empty() {
        Err(MtprotoError::Message("empty search query".into()))
    } else {
        Ok(q)
    }
}

/// `folder_id` is always sent: 0 = main list, 1 = archive.
/// https://core.telegram.org/api/folders
pub fn search_global_folder(folder_id: i32) -> (u32, Option<i32>) {
    (MessagesSearchGlobalRequest::FOLDER_ID_FLAG, Some(folder_id))
}

pub fn contacts_search(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    query: &str,
    limit: i32,
) -> Result<ContactsSearchDto, MtprotoError> {
    let q = contacts_search_query(query)?;
    let response: ContactsFound = api_invoke::invoke_api(
        snapshot,
        api_id,
        ContactsSearchRequest {
            flags: 0,
            broadcasts: None,
            bots: None,
            q,
            limit: clamp_search_limit(limit),
        },
    )?;
    let ContactsFound::ContactsFound(body) = response;
    dialogs::cache_from_users_chats(
        peers,
        media,
        vector_boxed_items(&body.users).cloned(),
        vector_boxed_items(&body.chats).cloned(),
    );
    let users: Vec<&User> = vector_boxed_items(&body.users).collect();
    let chats: Vec<&TlChat> = vector_boxed_items(&body.chats).collect();
    let mut people = Vec::new();
    let mut found_chats = Vec::new();
    let mut seen = HashMap::<i64, ()>::new();
    for peer in vector_boxed_items(&body.my_results).chain(vector_boxed_items(&body.results)) {
        let dto = search_peer_from_peer(peer, users.iter().copied(), chats.iter().copied());
        if seen.insert(dto.peer_id, ()).is_some() {
            continue;
        }
        if is_people_peer(peer) {
            people.push(dto);
        } else {
            found_chats.push(dto);
        }
    }
    Ok(ContactsSearchDto {
        people,
        chats: found_chats,
    })
}

pub fn search_global(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    query: &str,
    offset_rate: i32,
    offset_peer_id: i64,
    offset_id: i32,
    limit: i32,
    folder_id: i32,
) -> Result<GlobalMessageSearchDto, MtprotoError> {
    let offset_peer = offset_input_peer(peers, offset_peer_id)?;
    let (flags, folder_id) = search_global_folder(folder_id);
    let request = MessagesSearchGlobalRequest {
        flags,
        broadcasts_only: None,
        groups_only: None,
        users_only: None,
        folder_id,
        community: None,
        q: query.to_string(),
        filter: Box::new(MessagesFilter::InputMessagesFilterEmpty(
            InputMessagesFilterEmptyConstructor {},
        )),
        min_date: 0,
        max_date: 0,
        offset_rate,
        offset_peer: Box::new(offset_peer),
        offset_id,
        limit: clamp_search_limit(limit),
    };
    let response: tellers_mtproto::latest::api::MessagesMessages =
        api_invoke::invoke_api(snapshot, api_id, request)?;
    let (messages, next_rate) = dialogs::dtos_from_messages_page(response, peers, media)?;
    let (next_rate, next_peer_id, next_offset_id) = next_global_page(&messages, next_rate);
    Ok(GlobalMessageSearchDto {
        messages,
        next_rate,
        next_peer_id,
        next_offset_id,
    })
}

fn offset_input_peer(
    peers: &HashMap<i64, CachedPeer>,
    offset_peer_id: i64,
) -> Result<InputPeer, MtprotoError> {
    if offset_peer_id == 0 {
        Ok(InputPeer::InputPeerEmpty(InputPeerEmptyConstructor {}))
    } else {
        Ok(input_peer_from_cached(peers::require_usable_peer(
            peers,
            offset_peer_id,
        )?))
    }
}

fn search_peer_from_peer<'a>(
    peer: &Peer,
    users: impl Iterator<Item = &'a User>,
    chats: impl Iterator<Item = &'a TlChat>,
) -> SearchPeerDto {
    let peer_id = peer_chat_id(peer);
    let mut title = String::new();
    let mut username = None;
    let mut is_bot = false;
    let mut is_group = matches!(peer, Peer::PeerChat(_));
    let mut is_channel = matches!(peer, Peer::PeerChannel(_));
    for user in users {
        if let User::User(u) = user {
            if peers::chat_id_for_user(u.id) == peer_id {
                title = display_name(
                    u.first_name.as_deref(),
                    u.last_name.as_deref(),
                    u.username.as_deref(),
                );
                username = u.username.clone();
                is_bot = u.bot.is_some();
                is_group = false;
                is_channel = false;
            }
        }
    }
    if title.is_empty() {
        for chat in chats {
            match chat {
                TlChat::Chat(c) if peers::chat_id_for_chat(c.id) == peer_id => {
                    title = c.title.clone();
                    is_group = true;
                    is_channel = false;
                }
                TlChat::Channel(c) if peers::chat_id_for_channel(c.id) == peer_id => {
                    title = c.title.clone();
                    username = c.username.clone();
                    is_group = c.megagroup.is_some();
                    is_channel = c.broadcast.is_some() || c.megagroup.is_none();
                }
                _ => {}
            }
        }
    }
    if title.is_empty() {
        title = peer_id.to_string();
    }
    let kind = if is_bot {
        "bot"
    } else if is_group {
        "group"
    } else if is_channel {
        "channel"
    } else {
        "user"
    };
    SearchPeerDto {
        peer_id,
        title,
        username,
        kind: kind.to_string(),
        is_bot,
        is_group,
        is_channel,
    }
}

fn display_name(first: Option<&str>, last: Option<&str>, username: Option<&str>) -> String {
    let joined = [first.unwrap_or(""), last.unwrap_or("")]
        .into_iter()
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    if !joined.is_empty() {
        joined
    } else {
        username.unwrap_or("").to_string()
    }
}

#[cfg(test)]
#[path = "../../tests/unit/messages_search_rpc_tests.rs"]
mod tests;
