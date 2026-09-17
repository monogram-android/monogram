//! Inline bot queries and send.
//! https://core.telegram.org/api/bots/inline
//! https://core.telegram.org/method/contacts.resolveUsername
//! https://core.telegram.org/method/messages.getInlineBotResults
//! https://core.telegram.org/method/messages.sendInlineBotResult

use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    BotInlineResult, Chat as TlChat, ContactsResolveUsernameRequest, ContactsResolvedPeer,
    Document, InputPeer, InputPeerEmptyConstructor, InputUser, InputUserConstructor,
    MessagesBotResults, MessagesGetInlineBotResultsRequest, MessagesSendInlineBotResultRequest,
    Photo, True, TrueConstructor, Updates, User, WebDocument,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::dialogs;
use crate::media::{self, MediaIndex};
use crate::messages;
use crate::peers::{
    self, input_peer_from_cached, peer_chat_id, vector_boxed_items, CachedPeer, PeerKind,
};
use crate::{InlineBotResultDto, InlineBotResultsDto, MessageDto, MtprotoError, ResolvedPeerDto};

pub fn strip_username(raw: &str) -> String {
    raw.trim().trim_start_matches('@').trim().to_string()
}

pub fn resolve_username(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    username: &str,
) -> Result<ResolvedPeerDto, MtprotoError> {
    let username = strip_username(username);
    if username.is_empty() {
        return Err(MtprotoError::Message("empty username".into()));
    }
    let response: ContactsResolvedPeer = api_invoke::invoke_api(
        snapshot,
        api_id,
        ContactsResolveUsernameRequest {
            flags: 0,
            username,
            referer: None,
        },
    )?;
    let ContactsResolvedPeer::ContactsResolvedPeer(body) = response;
    dialogs::cache_from_users_chats(
        peers,
        media,
        vector_boxed_items(&body.users).cloned(),
        vector_boxed_items(&body.chats).cloned(),
    );
    Ok(resolved_from_peer(
        &body.peer,
        vector_boxed_items(&body.users),
        vector_boxed_items(&body.chats),
    ))
}

fn inline_peer_rejected(err: &MtprotoError) -> bool {
    let MtprotoError::Message(message) = err else {
        return false;
    };
    let text = message.to_ascii_uppercase();
    text.contains("PEER_ID_INVALID")
        || text.contains("CHANNEL_PRIVATE")
        || text.contains("CHANNEL_INVALID")
        || text.contains("CHAT_ID_INVALID")
        || text.contains("RPC 406")
}

pub fn get_inline_bot_results(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    bot_id: i64,
    query: &str,
    offset: &str,
) -> Result<InlineBotResultsDto, MtprotoError> {
    let bot = input_user(peers, bot_id)?;
    let request = |peer: InputPeer| MessagesGetInlineBotResultsRequest {
        flags: 0,
        bot: Box::new(bot.clone()),
        peer: Box::new(peer),
        geo_point: None,
        query: query.to_string(),
        offset: offset.to_string(),
    };
    let peer = if chat_id == 0 {
        InputPeer::InputPeerEmpty(InputPeerEmptyConstructor {})
    } else {
        input_peer_from_cached(peers::require_usable_peer(peers, chat_id)?)
    };
    let response: MessagesBotResults = match api_invoke::invoke_api(snapshot, api_id, request(peer))
    {
        Ok(value) => value,
        Err(err) if chat_id != 0 && inline_peer_rejected(&err) => api_invoke::invoke_api(
            snapshot,
            api_id,
            request(InputPeer::InputPeerEmpty(InputPeerEmptyConstructor {})),
        )?,
        Err(err) => return Err(err),
    };
    let MessagesBotResults::MessagesBotResults(body) = response;
    dialogs::cache_from_users_chats(
        peers,
        media,
        vector_boxed_items(&body.users).cloned(),
        std::iter::empty::<TlChat>(),
    );
    Ok(results_from_body(&body, media))
}

pub fn send_inline_bot_result(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    query_id: i64,
    result_id: &str,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (reply_flag, reply_to) = messages::input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let flags = reply_flag | MessagesSendInlineBotResultRequest::CLEAR_DRAFT_FLAG;
    let request = MessagesSendInlineBotResultRequest {
        flags,
        silent: None,
        background: None,
        clear_draft: Some(Box::new(True::True(TrueConstructor {}))),
        hide_via: None,
        peer: Box::new(input_peer_from_cached(cached)),
        reply_to,
        random_id: messages::random_id(),
        query_id,
        id: result_id.to_string(),
        schedule_date: None,
        send_as: None,
        quick_reply_shortcut: None,
        allow_paid_stars: None,
    };
    let updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(messages::message_from_updates(updates, chat_id, "", media))
}

fn input_user(peers: &HashMap<i64, CachedPeer>, bot_id: i64) -> Result<InputUser, MtprotoError> {
    let cached = peers::require_usable_peer(peers, bot_id)?;
    if cached.kind != PeerKind::User {
        return Err(MtprotoError::Message("inline bot is not a user".into()));
    }
    Ok(InputUser::InputUser(InputUserConstructor {
        user_id: cached.id,
        access_hash: cached.access_hash,
    }))
}

fn resolved_from_peer<'a>(
    peer: &tellers_mtproto::latest::api::Peer,
    users: impl Iterator<Item = &'a User>,
    chats: impl Iterator<Item = &'a TlChat>,
) -> ResolvedPeerDto {
    let peer_id = peer_chat_id(peer);
    let mut title = String::new();
    let mut username = None;
    let mut is_bot = false;
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
            }
        }
    }
    if title.is_empty() {
        for chat in chats {
            match chat {
                TlChat::Chat(c) if peers::chat_id_for_chat(c.id) == peer_id => {
                    title = c.title.clone();
                }
                TlChat::Channel(c) if peers::chat_id_for_channel(c.id) == peer_id => {
                    title = c.title.clone();
                    username = c.username.clone();
                }
                _ => {}
            }
        }
    }
    if title.is_empty() {
        title = peer_id.to_string();
    }
    ResolvedPeerDto {
        peer_id,
        username,
        title,
        is_bot,
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

fn results_from_body(
    body: &tellers_mtproto::latest::api::MessagesBotResultsConstructor,
    media: &mut MediaIndex,
) -> InlineBotResultsDto {
    let mut results = Vec::new();
    for item in vector_boxed_items(&body.results) {
        results.push(map_inline_result(item, media));
    }
    InlineBotResultsDto {
        query_id: body.query_id,
        gallery: body.gallery.is_some(),
        next_offset: body.next_offset.clone().filter(|s| !s.is_empty()),
        cache_time: body.cache_time,
        results,
    }
}

fn web_thumb_url(doc: Option<&WebDocument>) -> Option<String> {
    match doc? {
        WebDocument::WebDocument(body) => Some(body.url.clone()),
        WebDocument::WebDocumentNoProxy(body) => Some(body.url.clone()),
        _ => None,
    }
    .filter(|url| url.starts_with("https://") || url.starts_with("http://"))
}

fn map_inline_result(item: &BotInlineResult, media: &mut MediaIndex) -> InlineBotResultDto {
    match item {
        BotInlineResult::BotInlineResult(body) => InlineBotResultDto {
            id: body.id.clone(),
            kind: body.type_.clone(),
            title: body.title.clone(),
            description: body.description.clone(),
            url: body.url.clone(),
            document_id: None,
            thumb_cache_key: web_thumb_url(body.thumb.as_deref()).or_else(|| {
                if body.type_ == "photo" {
                    web_thumb_url(body.content.as_deref())
                } else {
                    None
                }
            }),
        },
        BotInlineResult::BotInlineMediaResult(body) => {
            let mut document_id = None;
            let mut thumb_cache_key = None;
            if let Some(doc) = body.document.as_ref() {
                if let Document::Document(d) = doc.as_ref() {
                    document_id = Some(d.id);
                    let mut indexed = media::media_ref_from_document(d);
                    indexed.cache_key = format!("doc:{}", d.id);
                    media::attach_document_thumbs(d, &mut indexed);
                    thumb_cache_key = indexed
                        .thumb_cache_key
                        .clone()
                        .or_else(|| Some(indexed.cache_key.clone()));
                    media.insert((d.id, 0), indexed);
                }
            } else if let Some(photo) = body.photo.as_ref() {
                if let Photo::Photo(p) = photo.as_ref() {
                    let cache_key = format!("photo:{}", p.id);
                    if let Some(indexed) =
                        media::media_ref_from_photo_with_thumbs(photo.as_ref(), cache_key)
                    {
                        thumb_cache_key = indexed
                            .thumb_cache_key
                            .clone()
                            .or_else(|| Some(indexed.cache_key.clone()));
                        media.insert((p.id, 0), indexed);
                    }
                }
            }
            InlineBotResultDto {
                id: body.id.clone(),
                kind: body.type_.clone(),
                title: body.title.clone(),
                description: body.description.clone(),
                url: None,
                document_id,
                thumb_cache_key,
            }
        }
    }
}

#[cfg(test)]
#[path = "inline_rpc_tests.rs"]
mod tests;
