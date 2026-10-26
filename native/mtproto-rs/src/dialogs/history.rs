use crate::HashMap;

use tellers_mtproto::latest::api::{
    ChannelsGetMessagesRequest, InputChannel, InputChannelConstructor, InputMessage,
    InputMessageIdConstructor, Message, MessagesGetHistoryRequest, MessagesGetMessagesRequest,
    MessagesGetRepliesRequest, MessagesMessages, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use super::message_map::{dtos_from_messages, message_to_dto};
use crate::api_invoke;
use crate::media::MediaIndex;
use crate::peers::{
    self, CachedPeer, PeerKind, channel_id_from_chat_id, input_peer_from_cached, vector_boxed_items,
};
use crate::{MessageDto, MtprotoError};

pub fn get_history(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    limit: i32,
    offset_id: i32,
    offset_date: i32,
    add_offset: i32,
    user_id: Option<i64>,
) -> Result<Vec<MessageDto>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let request = MessagesGetHistoryRequest {
        peer: Box::new(input_peer_from_cached(cached)),
        offset_id,
        offset_date,
        add_offset,
        limit: limit.clamp(1, 100),
        max_id: 0,
        min_id: 0,
        hash: 0,
    };
    let response: MessagesMessages = api_invoke::invoke_api(snapshot, api_id, request)?;
    let mut dtos = dtos_from_messages(response, peers, media_index)?;
    crate::dialogs::rich_rpc::fill_unsupported_rich_text(
        snapshot, api_id, peers, user_id, &mut dtos,
    );
    Ok(dtos)
}

/// https://core.telegram.org/method/messages.getReplies
/// https://core.telegram.org/api/discussion
pub fn get_replies(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    msg_id: i32,
    limit: i32,
    offset_id: i32,
    add_offset: i32,
    user_id: Option<i64>,
) -> Result<Vec<MessageDto>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let request = MessagesGetRepliesRequest {
        peer: Box::new(input_peer_from_cached(cached)),
        msg_id,
        offset_id,
        offset_date: 0,
        add_offset,
        limit: limit.clamp(1, 100),
        max_id: 0,
        min_id: 0,
        hash: 0,
    };
    let response: MessagesMessages = api_invoke::invoke_api(snapshot, api_id, request)?;
    let mut dtos = dtos_from_messages(response, peers, media_index)?;
    crate::dialogs::rich_rpc::fill_unsupported_rich_text(
        snapshot, api_id, peers, user_id, &mut dtos,
    );
    Ok(dtos)
}

/// https://core.telegram.org/method/messages.getForumTopics

pub(crate) fn input_message_ids(message_id: i32) -> Box<Vector<Box<InputMessage>>> {
    Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(InputMessage::InputMessageId(
            InputMessageIdConstructor { id: message_id },
        ))],
    }))
}

pub(crate) fn messages_from_response(
    response: MessagesMessages,
) -> Result<Vector<Box<Message>>, MtprotoError> {
    match response {
        MessagesMessages::MessagesMessages(m) => Ok(*m.messages),
        MessagesMessages::MessagesMessagesSlice(m) => Ok(*m.messages),
        MessagesMessages::MessagesChannelMessages(m) => Ok(*m.messages),
        MessagesMessages::MessagesMessagesNotModified(_) => {
            Err(MtprotoError::Message("messages not modified".into()))
        }
        _ => Err(MtprotoError::Message("unexpected messages.messages".into())),
    }
}

/// Refetch one dialog message and re-index its `file_reference`.
/// https://core.telegram.org/api/file-references (`fileSourceMessage` / getMessageOp)
pub fn refresh_message_media(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    message_id: i32,
) -> Result<(), MtprotoError> {
    let messages = fetch_message(snapshot, api_id, peers, chat_id, message_id)?;
    let _ = vector_boxed_items(&messages)
        .filter_map(|msg| message_to_dto(msg, media_index))
        .count();
    Ok(())
}

pub fn get_message(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    message_id: i32,
) -> Result<Option<crate::MessageDto>, MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    let messages = fetch_message(snapshot, api_id, peers, chat_id, message_id)?;
    select_message(&messages, media_index, chat_id, message_id)
}

fn select_message(
    messages: &Vector<Box<Message>>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    message_id: i32,
) -> Result<Option<crate::MessageDto>, MtprotoError> {
    let mut rows = vector_boxed_items(messages);
    let Some(message) = rows.next() else {
        return Ok(None);
    };
    if rows.next().is_some() {
        return Err(MtprotoError::Message("unexpected message identity".into()));
    }
    if let Message::MessageEmpty(empty) = message {
        if empty.id != message_id
            || empty
                .peer_id
                .as_ref()
                .is_some_and(|peer| crate::peers::peer_chat_id(peer) != chat_id)
        {
            return Err(MtprotoError::Message("unexpected message identity".into()));
        }
        return Ok(None);
    }
    let mut staged = media_index.clone();
    let message = message_to_dto(message, &mut staged)
        .filter(|message| message.id == message_id && message.chat_id == chat_id)
        .ok_or_else(|| MtprotoError::Message("unexpected message identity".into()))?;
    *media_index = staged;
    Ok(Some(message))
}

pub(crate) fn fetch_message(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
) -> Result<Vector<Box<Message>>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let ids = input_message_ids(message_id);
    let response: MessagesMessages = if cached.kind == PeerKind::Channel {
        let channel_id = channel_id_from_chat_id(chat_id)
            .ok_or_else(|| MtprotoError::Message("not a channel".into()))?;
        api_invoke::invoke_api(
            snapshot,
            api_id,
            ChannelsGetMessagesRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id,
                    access_hash: cached.access_hash,
                })),
                id: ids,
            },
        )?
    } else {
        api_invoke::invoke_api(snapshot, api_id, MessagesGetMessagesRequest { id: ids })?
    };
    messages_from_response(response)
}

#[cfg(test)]
#[path = "../../tests/unit/dialogs_message_lookup_tests.rs"]
mod message_lookup_tests;
