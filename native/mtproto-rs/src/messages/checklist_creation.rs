use crate::media::MediaIndex;
use crate::peers::{CachedPeer, input_peer_from_cached, require_usable_peer};
use crate::{HashMap, MtprotoError, OutgoingMessageResult, SendOptions};
use tellers_mtproto::latest::api::*;
use tellers_mtproto_session::Snapshot;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ChecklistSendSpec {
    pub title: String,
    pub items: Vec<String>,
    pub others_can_append: bool,
    pub others_can_complete: bool,
}

fn text(value: &str) -> Box<TextWithEntities> {
    Box::new(TextWithEntities::TextWithEntities(
        TextWithEntitiesConstructor {
            text: value.to_owned(),
            entities: Box::new(Vector::Vector(VectorConstructor {
                field_0: 0,
                field_1: vec![],
            })),
        },
    ))
}

fn checklist_media(spec: &ChecklistSendSpec) -> Result<InputMedia, MtprotoError> {
    if spec.title.trim().is_empty()
        || spec.title.encode_utf16().count() > 32
        || !(1..=30).contains(&spec.items.len())
        || spec
            .items
            .iter()
            .any(|v| v.trim().is_empty() || v.encode_utf16().count() > 64)
    {
        return Err(MtprotoError::Message(
            "checklist configuration invalid".into(),
        ));
    }
    let todo = TodoListConstructor {
        flags: if spec.others_can_append {
            TodoListConstructor::OTHERS_CAN_APPEND_FLAG
        } else {
            0
        } | if spec.others_can_complete {
            TodoListConstructor::OTHERS_CAN_COMPLETE_FLAG
        } else {
            0
        },
        others_can_append: spec
            .others_can_append
            .then(|| Box::new(True::True(TrueConstructor {}))),
        others_can_complete: spec
            .others_can_complete
            .then(|| Box::new(True::True(TrueConstructor {}))),
        title: text(&spec.title),
        list: Box::new(Vector::Vector(VectorConstructor {
            field_0: spec.items.len() as u32,
            field_1: spec
                .items
                .iter()
                .enumerate()
                .map(|(i, value)| {
                    Box::new(TodoItem::TodoItem(TodoItemConstructor {
                        id: i as i32 + 1,
                        title: text(value),
                    }))
                })
                .collect(),
        })),
    };
    Ok(InputMedia::InputMediaTodo(InputMediaTodoConstructor {
        todo: Box::new(TodoList::TodoList(todo)),
    }))
}

pub fn send_checklist_with_options(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    spec: &ChecklistSendSpec,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<OutgoingMessageResult, MtprotoError> {
    options.validate(random_id)?;
    if random_id == 0 || reply_to_msg_id < 0 || top_msg_id < 0 {
        return Err(MtprotoError::Message(
            "checklist send identity invalid".into(),
        ));
    }
    let checklist = checklist_media(spec)?;
    let peer = require_usable_peer(peers, chat_id)?;
    let (flags, reply_to) = super::input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let mut request = MessagesSendMediaRequest {
        flags,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(peer)),
        reply_to,
        media: Box::new(checklist),
        message: String::new(),
        random_id,
        reply_markup: None,
        entities: None,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
        suggested_post: None,
    };
    options.apply_media(&mut request);
    let updates: Updates = crate::api_invoke::invoke_api(snapshot, api_id, request)?;
    let mut staged = media.clone();
    let result = super::send_result(updates, chat_id, "", random_id, peers, &mut staged)?;
    let message = match &result {
        OutgoingMessageResult::Sent { message } => message,
        OutgoingMessageResult::Scheduled { message } => &message.message,
    };
    if message.media_kind.as_deref() != Some("todo") {
        return Err(MtprotoError::Message(
            "checklist response missing authoritative checklist".into(),
        ));
    }
    *media = staged;
    Ok(result)
}

#[cfg(test)]
#[path = "../../tests/unit/messages_checklist_creation_tests.rs"]
mod tests;
