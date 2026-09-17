use std::collections::HashMap;

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
    self, channel_id_from_chat_id, input_peer_from_cached, vector_boxed_items, CachedPeer, PeerKind,
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
    crate::rich_rpc::fill_unsupported_rich_text(snapshot, api_id, peers, user_id, &mut dtos);
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
    crate::rich_rpc::fill_unsupported_rich_text(snapshot, api_id, peers, user_id, &mut dtos);
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
    let messages = messages_from_response(response)?;
    let _ = vector_boxed_items(&messages)
        .filter_map(|msg| message_to_dto(msg, media_index))
        .count();
    Ok(())
}
