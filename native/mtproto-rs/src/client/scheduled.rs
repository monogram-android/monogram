use std::collections::HashSet;

use tellers_mtproto::latest::api::*;

use super::{call_with_migrate, with_client_mut};
use crate::peers::{input_peer_from_cached, require_usable_peer};
use crate::{MessageDto, MtprotoError};

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ScheduledMessageDto {
    pub message: MessageDto,
    #[serde(default = "unsupported_by_default")]
    pub has_unsupported_content: bool,
}

fn unsupported_by_default() -> bool {
    true
}

pub const SCHEDULE_WHEN_ONLINE: i32 = 0x7fff_fffe;

pub fn get_scheduled_history(
    handle: u64,
    chat_id: i64,
) -> Result<Vec<ScheduledMessageDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = input_peer_from_cached(require_usable_peer(&state.peers, chat_id)?);
            let response: MessagesMessages = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesGetScheduledHistoryRequest {
                    peer: Box::new(peer),
                    hash: 0,
                },
            )?;
            map_response(response, &mut state.peers, &mut state.media)
        })
    })
}

pub fn get_scheduled_messages(
    handle: u64,
    chat_id: i64,
    ids: Vec<i32>,
) -> Result<Vec<ScheduledMessageDto>, MtprotoError> {
    validate_ids(&ids)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = input_peer_from_cached(require_usable_peer(&state.peers, chat_id)?);
            let response: MessagesMessages = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesGetScheduledMessagesRequest {
                    peer: Box::new(peer),
                    id: vector(ids.clone()),
                },
            )?;
            map_response(response, &mut state.peers, &mut state.media)
        })
    })
}

pub fn send_scheduled_messages(
    handle: u64,
    chat_id: i64,
    ids: Vec<i32>,
) -> Result<(), MtprotoError> {
    change_queue(handle, chat_id, ids, true)
}

pub fn delete_scheduled_messages(
    handle: u64,
    chat_id: i64,
    ids: Vec<i32>,
) -> Result<(), MtprotoError> {
    change_queue(handle, chat_id, ids, false)
}

fn change_queue(handle: u64, chat_id: i64, ids: Vec<i32>, send: bool) -> Result<(), MtprotoError> {
    validate_ids(&ids)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = Box::new(input_peer_from_cached(require_usable_peer(
                &state.peers,
                chat_id,
            )?));
            let response: Updates = if send {
                crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    MessagesSendScheduledMessagesRequest {
                        peer,
                        id: vector(ids.clone()),
                    },
                )?
            } else {
                crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    MessagesDeleteScheduledMessagesRequest {
                        peer,
                        id: vector(ids.clone()),
                    },
                )?
            };
            super::management::cache_response_peers(state, &response);
            super::management::enqueue_response(state.snapshot.dc_id, &response)
        })
    })
}

pub fn edit_scheduled_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    schedule_date: i32,
    text: Option<String>,
    entities_json: Option<String>,
    no_webpage: bool,
) -> Result<(), MtprotoError> {
    validate_ids(&[message_id])?;
    validate_date(schedule_date)?;
    if text.is_none() && (entities_json.is_some() || no_webpage) {
        return Err(MtprotoError::Message(
            "scheduled formatting requires replacement text".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = input_peer_from_cached(require_usable_peer(&state.peers, chat_id)?);
            if text.is_some() {
                let response: MessagesMessages = crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    MessagesGetScheduledMessagesRequest {
                        peer: Box::new(peer.clone()),
                        id: vector(vec![message_id]),
                    },
                )?;
                let current = map_response(response, &mut state.peers, &mut state.media)?;
                require_editable(&current, chat_id, message_id)?;
                if current[0].message.date != i64::from(schedule_date) {
                    return Err(MtprotoError::Message(
                        "scheduled date changed; refresh before editing".into(),
                    ));
                }
            }
            let request = edit_request(
                peer,
                message_id,
                schedule_date,
                text.clone(),
                entities_json.as_deref(),
                no_webpage,
                &state.peers,
            )?;
            let response: Updates =
                crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request)?;
            super::management::cache_response_peers(state, &response);
            super::management::enqueue_response(state.snapshot.dc_id, &response)
        })
    })
}

fn require_editable(
    messages: &[ScheduledMessageDto],
    chat_id: i64,
    id: i32,
) -> Result<(), MtprotoError> {
    if matches!(messages, [value] if value.message.chat_id == chat_id && value.message.id == id && !value.has_unsupported_content
        && matches!(value.message.media_kind.as_deref(), None | Some("photo" | "video" | "gif" | "document" | "audio")))
    {
        Ok(())
    } else {
        Err(MtprotoError::Message(
            "scheduled content cannot be safely edited".into(),
        ))
    }
}

fn edit_request(
    peer: InputPeer,
    id: i32,
    date: i32,
    text: Option<String>,
    entities_json: Option<&str>,
    no_webpage: bool,
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
) -> Result<MessagesEditMessageRequest, MtprotoError> {
    let mut flags = MessagesEditMessageRequest::SCHEDULE_DATE_FLAG;
    let entities = if text.is_some() {
        flags |=
            MessagesEditMessageRequest::MESSAGE_FLAG | MessagesEditMessageRequest::ENTITIES_FLAG;
        Some(
            crate::messages::input_entities_from_json(entities_json, peers)?
                .1
                .unwrap_or_else(|| vector(Vec::new())),
        )
    } else {
        None
    };
    if no_webpage {
        flags |= MessagesEditMessageRequest::NO_WEBPAGE_FLAG;
    }
    Ok(MessagesEditMessageRequest {
        flags,
        no_webpage: no_webpage.then(|| Box::new(True::True(TrueConstructor {}))),
        invert_media: None,
        peer: Box::new(peer),
        id,
        message: text,
        media: None,
        reply_markup: None,
        entities,
        schedule_date: Some(date),
        schedule_repeat_period: None,
        quick_reply_shortcut_id: None,
        rich_message: None,
    })
}

pub(crate) fn validate_date(date: i32) -> Result<(), MtprotoError> {
    if date <= 0 || date == i32::MAX {
        return Err(MtprotoError::Message("invalid scheduled date".into()));
    }
    Ok(())
}

fn validate_ids(ids: &[i32]) -> Result<(), MtprotoError> {
    if ids.is_empty()
        || ids.len() > 100
        || ids.iter().any(|id| *id <= 0)
        || ids.iter().collect::<HashSet<_>>().len() != ids.len()
    {
        return Err(MtprotoError::Message(
            "invalid scheduled message selection".into(),
        ));
    }
    Ok(())
}

fn vector<T>(items: Vec<T>) -> Box<Vector<T>> {
    Box::new(Vector::Vector(VectorConstructor {
        field_0: items.len() as u32,
        field_1: items,
    }))
}

pub(super) fn refresh_media(
    state: &mut super::ClientState,
    chat_id: i64,
    id: i32,
) -> Result<(), MtprotoError> {
    let peer = input_peer_from_cached(require_usable_peer(&state.peers, chat_id)?);
    let response: MessagesMessages = crate::api_invoke::invoke_api(
        &mut state.snapshot,
        state.api_id,
        MessagesGetScheduledMessagesRequest {
            peer: Box::new(peer),
            id: vector(vec![id]),
        },
    )?;
    state
        .media
        .shift_remove(&(chat_id, crate::media::scheduled_media_id(id)?));
    map_response(response, &mut state.peers, &mut state.media)?;
    Ok(())
}

pub(crate) fn map_update(
    message: &Message,
    media: &mut crate::media::MediaIndex,
) -> Result<ScheduledMessageDto, MtprotoError> {
    if let Message::Message(value) = message {
        media.shift_remove(&(
            crate::peers::peer_chat_id(&value.peer_id),
            crate::media::scheduled_media_id(value.id)?,
        ));
    }
    let response = MessagesMessages::MessagesMessages(MessagesMessagesConstructor {
        messages: vector(vec![Box::new(message.clone())]),
        topics: vector(Vec::new()),
        users: vector(Vec::new()),
        chats: vector(Vec::new()),
    });
    map_response(response, &mut crate::HashMap::default(), media)?
        .into_iter()
        .next()
        .ok_or_else(|| MtprotoError::Message("scheduled update has no message".into()))
}

pub(crate) fn map_response(
    mut response: MessagesMessages,
    peers: &mut crate::HashMap<i64, crate::peers::CachedPeer>,
    media: &mut crate::media::MediaIndex,
) -> Result<Vec<ScheduledMessageDto>, MtprotoError> {
    let messages = match &mut response {
        MessagesMessages::MessagesMessages(value) => &mut value.messages,
        MessagesMessages::MessagesMessagesSlice(value) => &mut value.messages,
        MessagesMessages::MessagesChannelMessages(value) => &mut value.messages,
        _ => {
            return Err(MtprotoError::Message(
                "unexpected scheduled history response".into(),
            ));
        }
    };
    let mut entities = crate::peers::vector_boxed_items(messages)
        .filter_map(|message| match message {
            Message::Message(value) => {
                let json = super::drafts::draft_entities_json(value.entities.as_deref());
                let unsupported = (value
                    .entities
                    .as_deref()
                    .is_some_and(|items| !crate::peers::vector_items(items).is_empty())
                    && json.is_none())
                    || value.rich_message.is_some()
                    || value.invert_media.is_some()
                    || value.media.as_deref().is_some_and(|media| {
                        !matches!(
                            media,
                            MessageMedia::MessageMediaEmpty(_)
                                | MessageMedia::MessageMediaPhoto(_)
                                | MessageMedia::MessageMediaDocument(_)
                        )
                    });
                Some((value.id, (json, unsupported)))
            }
            _ => None,
        })
        .collect::<std::collections::HashMap<_, _>>();
    if let Vector::Vector(items) = messages.as_mut() {
        for message in &mut items.field_1 {
            let id = match message.as_mut() {
                Message::Message(value) => &mut value.id,
                Message::MessageService(value) => &mut value.id,
                Message::MessageEmpty(value) => &mut value.id,
                _ => return Err(MtprotoError::Message("unexpected scheduled message".into())),
            };
            *id = crate::media::scheduled_media_id(*id)?;
        }
    }
    Ok(crate::dialogs::dtos_from_messages(response, peers, media)?
        .into_iter()
        .map(|mut message| {
            message.id = crate::media::scheduled_message_id(message.id).expect("mapped queue ID");
            let has_unsupported_content =
                if let Some((value, unsupported)) = entities.remove(&message.id) {
                    message.entities_json = value;
                    unsupported
                } else {
                    true
                };
            ScheduledMessageDto {
                message,
                has_unsupported_content,
            }
        })
        .collect())
}

#[cfg(test)]
#[path = "../../tests/unit/client_scheduled_tests.rs"]
mod tests;
