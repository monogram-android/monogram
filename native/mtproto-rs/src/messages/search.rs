use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    InputMessagesFilterChatPhotosConstructor, InputMessagesFilterDocumentConstructor,
    InputMessagesFilterEmptyConstructor, InputMessagesFilterGifConstructor,
    InputMessagesFilterMusicConstructor, InputMessagesFilterPhotoVideoConstructor,
    InputMessagesFilterPhotosConstructor, InputMessagesFilterPinnedConstructor,
    InputMessagesFilterUrlConstructor, InputMessagesFilterVideoConstructor,
    InputMessagesFilterVoiceConstructor, MessagesFilter, MessagesGetSearchCountersRequest,
    MessagesSearchCounter, MessagesSearchRequest, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::media::MediaIndex;
use crate::peers::{self, input_peer_from_cached, CachedPeer};
use crate::{MessageDto, MtprotoError};

pub fn clamp_search_limit(limit: i32) -> i32 {
    limit.clamp(1, 100)
}

/// `messages.search` filter names used by UniFFI. Unknown → empty.
/// https://core.telegram.org/type/MessagesFilter
pub fn messages_filter(kind: &str) -> MessagesFilter {
    match kind {
        "photo_video" => MessagesFilter::InputMessagesFilterPhotoVideo(
            InputMessagesFilterPhotoVideoConstructor {},
        ),
        "document" => {
            MessagesFilter::InputMessagesFilterDocument(InputMessagesFilterDocumentConstructor {})
        }
        "url" => MessagesFilter::InputMessagesFilterUrl(InputMessagesFilterUrlConstructor {}),
        "gif" => MessagesFilter::InputMessagesFilterGif(InputMessagesFilterGifConstructor {}),
        "voice" => MessagesFilter::InputMessagesFilterVoice(InputMessagesFilterVoiceConstructor {}),
        "music" => MessagesFilter::InputMessagesFilterMusic(InputMessagesFilterMusicConstructor {}),
        "photos" => {
            MessagesFilter::InputMessagesFilterPhotos(InputMessagesFilterPhotosConstructor {})
        }
        "videos" => {
            MessagesFilter::InputMessagesFilterVideo(InputMessagesFilterVideoConstructor {})
        }
        "chat_photos" => MessagesFilter::InputMessagesFilterChatPhotos(
            InputMessagesFilterChatPhotosConstructor {},
        ),
        "pinned" => {
            MessagesFilter::InputMessagesFilterPinned(InputMessagesFilterPinnedConstructor {})
        }
        _ => MessagesFilter::InputMessagesFilterEmpty(InputMessagesFilterEmptyConstructor {}),
    }
}

/// Stable filter key mirroring [`messages_filter`]; unusable filters map to `None`.
pub fn messages_filter_key(filter: &MessagesFilter) -> Option<&'static str> {
    Some(match filter {
        MessagesFilter::InputMessagesFilterPhotoVideo(_) => "photo_video",
        MessagesFilter::InputMessagesFilterDocument(_) => "document",
        MessagesFilter::InputMessagesFilterUrl(_) => "url",
        MessagesFilter::InputMessagesFilterGif(_) => "gif",
        MessagesFilter::InputMessagesFilterVoice(_) => "voice",
        MessagesFilter::InputMessagesFilterMusic(_) => "music",
        MessagesFilter::InputMessagesFilterPhotos(_) => "photos",
        MessagesFilter::InputMessagesFilterVideo(_) => "videos",
        MessagesFilter::InputMessagesFilterChatPhotos(_) => "chat_photos",
        MessagesFilter::InputMessagesFilterPinned(_) => "pinned",
        _ => return None,
    })
}

/// `messages.getSearchCounters` — result counts per filter, as compact JSON
/// `{"photo_video":{"count":12,"inexact":false}, ...}`.
/// https://core.telegram.org/method/messages.getSearchCounters
pub fn get_search_counters(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    filters: &[String],
) -> Result<String, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let items: Vec<Box<MessagesFilter>> = filters
        .iter()
        .map(|name| Box::new(messages_filter(name)))
        .collect();
    if items.is_empty() {
        return Ok("{}".to_string());
    }
    let request = MessagesGetSearchCountersRequest {
        flags: 0,
        peer: Box::new(input_peer_from_cached(cached)),
        saved_peer_id: None,
        top_msg_id: None,
        filters: Box::new(Vector::Vector(VectorConstructor {
            field_0: items.len() as u32,
            field_1: items,
        })),
    };
    let response: Vector<MessagesSearchCounter> =
        api_invoke::invoke_api(snapshot, api_id, request)?;
    let Vector::Vector(list) = response;
    let mut out = serde_json::Map::new();
    for counter in list.field_1 {
        let MessagesSearchCounter::MessagesSearchCounter(value) = counter;
        let Some(key) = messages_filter_key(&value.filter) else {
            continue;
        };
        out.insert(
            key.to_string(),
            serde_json::json!({ "count": value.count, "inexact": value.inexact.is_some() }),
        );
    }
    Ok(serde_json::Value::Object(out).to_string())
}

pub fn search_messages(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    query: &str,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    search_messages_with_filter(
        snapshot,
        api_id,
        peers,
        media_index,
        chat_id,
        query,
        0,
        0,
        limit,
        messages_filter(""),
    )
}

/// `messages.search` with a named filter and history offsets.
/// https://core.telegram.org/method/messages.search
/// https://core.telegram.org/api/offsets
pub fn search_messages_filtered(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    query: &str,
    filter: &str,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    search_messages_with_filter(
        snapshot,
        api_id,
        peers,
        media_index,
        chat_id,
        query,
        offset_id,
        add_offset,
        limit,
        messages_filter(filter),
    )
}

/// `messages.search` with `inputMessagesFilterPinned`.
/// https://core.telegram.org/api/pin
pub fn get_pinned_messages(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    search_messages_with_filter(
        snapshot,
        api_id,
        peers,
        media_index,
        chat_id,
        "",
        0,
        0,
        limit,
        messages_filter("pinned"),
    )
}

pub(crate) fn search_messages_with_filter(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    query: &str,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    filter: MessagesFilter,
) -> Result<Vec<MessageDto>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let request = MessagesSearchRequest {
        flags: 0,
        peer: Box::new(input_peer_from_cached(cached)),
        q: query.to_string(),
        from_id: None,
        saved_peer_id: None,
        saved_reaction: None,
        top_msg_id: None,
        filter: Box::new(filter),
        min_date: 0,
        max_date: 0,
        offset_id,
        add_offset,
        limit: clamp_search_limit(limit),
        max_id: 0,
        min_id: 0,
        hash: 0,
    };
    let response: tellers_mtproto::latest::api::MessagesMessages =
        api_invoke::invoke_api(snapshot, api_id, request)?;
    crate::dialogs::dtos_from_messages(response, peers, media_index)
}
