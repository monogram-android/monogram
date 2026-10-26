use tellers_mtproto::latest::api::*;

use crate::{MessageDto, MtprotoError};

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct SendOptions {
    pub silent: bool,
    pub no_webpage: bool,
    pub schedule_date: Option<i32>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Enum)]
pub enum OutgoingMessageResult {
    Sent { message: MessageDto },
    Scheduled { message: crate::ScheduledMessageDto },
}

impl SendOptions {
    pub(crate) fn validate(&self, random_id: i64) -> Result<(), MtprotoError> {
        if let Some(date) = self.schedule_date {
            crate::client::scheduled::validate_date(date)?;
            if random_id == 0 {
                return Err(MtprotoError::Message(
                    "scheduled sends require a persisted random id".into(),
                ));
            }
        }
        Ok(())
    }

    pub(crate) fn apply_text(&self, request: &mut MessagesSendMessageRequest) {
        if self.no_webpage {
            request.flags |= MessagesSendMessageRequest::NO_WEBPAGE_FLAG;
        }
        if self.silent {
            request.flags |= MessagesSendMessageRequest::SILENT_FLAG;
        }
        if self.schedule_date.is_some() {
            request.flags |= MessagesSendMessageRequest::SCHEDULE_DATE_FLAG;
        }
        request.no_webpage = self
            .no_webpage
            .then(|| Box::new(True::True(TrueConstructor {})));
        request.silent = self
            .silent
            .then(|| Box::new(True::True(TrueConstructor {})));
        request.schedule_date = self.schedule_date;
    }

    pub(crate) fn apply_media(&self, request: &mut MessagesSendMediaRequest) {
        if self.silent {
            request.flags |= MessagesSendMediaRequest::SILENT_FLAG;
        }
        if self.schedule_date.is_some() {
            request.flags |= MessagesSendMediaRequest::SCHEDULE_DATE_FLAG;
        }
        request.silent = self
            .silent
            .then(|| Box::new(True::True(TrueConstructor {})));
        request.schedule_date = self.schedule_date;
    }

    pub(crate) fn apply_album(&self, request: &mut MessagesSendMultiMediaRequest) {
        if self.silent {
            request.flags |= MessagesSendMultiMediaRequest::SILENT_FLAG;
        }
        if self.schedule_date.is_some() {
            request.flags |= MessagesSendMultiMediaRequest::SCHEDULE_DATE_FLAG;
        }
        request.silent = self
            .silent
            .then(|| Box::new(True::True(TrueConstructor {})));
        request.schedule_date = self.schedule_date;
    }
}

pub(crate) fn send_result(
    updates: Updates,
    chat_id: i64,
    text: &str,
    random_id: i64,
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
    media: &mut crate::media::MediaIndex,
) -> Result<OutgoingMessageResult, MtprotoError> {
    let parts = match &updates {
        Updates::Updates(value) => Some((&value.updates, &value.users, &value.chats)),
        Updates::UpdatesCombined(value) => Some((&value.updates, &value.users, &value.chats)),
        _ => None,
    };
    if let Some((items, users, chats)) = parts {
        let acknowledgments = crate::peers::vector_boxed_items(items)
            .filter_map(|update| match update {
                Update::UpdateMessageId(value) if value.random_id == random_id => Some(value.id),
                _ => None,
            })
            .collect::<Vec<_>>();
        let scheduled = crate::peers::vector_boxed_items(items)
            .filter_map(|update| match update {
                Update::UpdateNewScheduledMessage(value) => Some(value.message.clone()),
                _ => None,
            })
            .filter(|message| match message.as_ref() {
                Message::Message(value) => {
                    acknowledgments.contains(&value.id)
                        && crate::peers::peer_chat_id(&value.peer_id) == chat_id
                }
                _ => false,
            })
            .collect::<Vec<_>>();
        let normal = crate::peers::vector_boxed_items(items)
            .filter_map(|update| match update {
                Update::UpdateNewMessage(value) => Some(&value.message),
                Update::UpdateNewChannelMessage(value) => Some(&value.message),
                _ => None,
            })
            .filter(|message| match message.as_ref() {
                Message::Message(value) => {
                    crate::peers::peer_chat_id(&value.peer_id) == chat_id
                        && (acknowledgments.is_empty() || acknowledgments.contains(&value.id))
                }
                _ => false,
            })
            .collect::<Vec<_>>();
        if scheduled.len() + normal.len() > 1 {
            return Err(MtprotoError::Message(
                "ambiguous acknowledged send response".into(),
            ));
        }
        if !scheduled.is_empty() {
            let response = MessagesMessages::MessagesMessages(MessagesMessagesConstructor {
                messages: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 1,
                    field_1: scheduled,
                })),
                topics: Box::new(Vector::Vector(VectorConstructor {
                    field_0: 0,
                    field_1: Vec::new(),
                })),
                users: users.clone(),
                chats: chats.clone(),
            });
            let message =
                crate::client::scheduled::map_response(response, &mut peers.clone(), media)?
                    .into_iter()
                    .next()
                    .ok_or_else(|| {
                        MtprotoError::Message("scheduled response has no message".into())
                    })?;
            return Ok(OutgoingMessageResult::Scheduled { message });
        }
        if let Some(message) = normal.first() {
            let message = crate::dialogs::message_to_dto(message, media)
                .ok_or_else(|| MtprotoError::Message("send response has no message".into()))?;
            return Ok(OutgoingMessageResult::Sent { message });
        }
        return Err(MtprotoError::Message(
            "send response missing a unique acknowledged message".into(),
        ));
    }
    let message = super::send::message_from_updates(updates, chat_id, text, media);
    if message.id <= 0 {
        return Err(MtprotoError::Message(
            "send response has no confirmed message id".into(),
        ));
    }
    Ok(OutgoingMessageResult::Sent { message })
}

pub(crate) fn album_send_results(
    updates: Updates,
    chat_id: i64,
    requests: &[(i64, &str)],
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
    media: &mut crate::media::MediaIndex,
) -> Result<Vec<OutgoingMessageResult>, MtprotoError> {
    let ids = requests
        .iter()
        .map(|(id, _)| *id)
        .collect::<std::collections::HashSet<_>>();
    if requests.is_empty() || requests.len() > 10 || ids.len() != requests.len() || ids.contains(&0)
    {
        return Err(MtprotoError::Message(
            "album requires distinct persisted random ids".into(),
        ));
    }
    if requests.len() > 1 {
        let items = match &updates {
            Updates::Updates(value) => &value.updates,
            Updates::UpdatesCombined(value) => &value.updates,
            _ => {
                return Err(MtprotoError::Message(
                    "album response missing acknowledgments".into(),
                ));
            }
        };
        let mut acknowledged = std::collections::HashMap::new();
        let mut message_ids = std::collections::HashSet::new();
        for update in crate::peers::vector_boxed_items(items) {
            if let Update::UpdateMessageId(value) = update {
                if ids.contains(&value.random_id)
                    && (value.id <= 0
                        || acknowledged.insert(value.random_id, value.id).is_some()
                        || !message_ids.insert(value.id))
                {
                    return Err(MtprotoError::Message(
                        "ambiguous album acknowledgment".into(),
                    ));
                }
            }
        }
        if acknowledged.len() != requests.len() {
            return Err(MtprotoError::Message(
                "album response missing acknowledgments".into(),
            ));
        }
    }
    let mut mapped_media = media.clone();
    let results = requests
        .iter()
        .map(|(id, text)| {
            send_result(
                updates.clone(),
                chat_id,
                text,
                *id,
                peers,
                &mut mapped_media,
            )
        })
        .collect::<Result<Vec<_>, _>>()?;
    *media = mapped_media;
    Ok(results)
}

// Short RPC results bind the confirmed message ID to the request's random ID implicitly.
pub(crate) fn implicit_ack(
    updates: &Updates,
    result: &OutgoingMessageResult,
    random_id: i64,
) -> Option<Updates> {
    if random_id == 0 {
        return None;
    }
    let date = match updates {
        Updates::UpdateShortSentMessage(value) => value.date,
        Updates::UpdateShortMessage(value) => value.date,
        Updates::UpdateShortChatMessage(value) => value.date,
        Updates::Updates(value) => {
            if crate::peers::vector_boxed_items(&value.updates).any(|item| {
                matches!(item,
                Update::UpdateMessageId(ack) if ack.random_id == random_id)
            }) {
                return None;
            }
            value.date
        }
        Updates::UpdatesCombined(value) => {
            if crate::peers::vector_boxed_items(&value.updates).any(|item| {
                matches!(item,
                Update::UpdateMessageId(ack) if ack.random_id == random_id)
            }) {
                return None;
            }
            value.date
        }
        _ => return None,
    };
    let id = match result {
        OutgoingMessageResult::Sent { message } => message.id,
        OutgoingMessageResult::Scheduled { message } => message.message.id,
    };
    Some(Updates::UpdateShort(UpdateShortConstructor {
        date,
        update: Box::new(Update::UpdateMessageId(UpdateMessageIdConstructor {
            id,
            random_id,
        })),
    }))
}

#[cfg(test)]
#[path = "../../tests/unit/messages_send_options_tests.rs"]
mod tests;
