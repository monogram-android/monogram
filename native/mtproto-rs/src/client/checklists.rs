use super::*;
use crate::peers::input_peer_from_cached;
use tellers_mtproto::latest::api::*;

#[derive(Debug, Clone, uniffi::Record)]
pub struct ChecklistTextEditDto {
    pub item_id: Option<i32>,
    pub text: String,
    pub entities_json: Option<String>,
    pub expected_text: String,
    pub expected_entities_json: Option<String>,
}

pub fn edit_checklist_text(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    edit: ChecklistTextEditDto,
) -> Result<(), MtprotoError> {
    mutate_text(handle, chat_id, message_id, edit, false)
}

pub fn remove_checklist_item(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    edit: ChecklistTextEditDto,
) -> Result<(), MtprotoError> {
    if edit.item_id.is_none() {
        return Err(MtprotoError::Message("checklist item required".into()));
    }
    mutate_text(handle, chat_id, message_id, edit, true)
}

fn mutate_text(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    edit: ChecklistTextEditDto,
    remove: bool,
) -> Result<(), MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("checklist message required".into()));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                let rows = crate::dialogs::fetch_message(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                )?;
                let peer = input_peer_from_cached(crate::peers::require_usable_peer(
                    &state.peers,
                    chat_id,
                )?);
                let request = content_request(
                    &rows,
                    peer,
                    chat_id,
                    message_id,
                    &edit,
                    &state.peers,
                    remove,
                )?;
                let response: Updates =
                    crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request)?;
                super::management::cache_response_peers(state, &response);
                super::management::enqueue_response(state.snapshot.dc_id, &response)
            })
        })
    })
}

pub fn edit_checklist_title(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    title: String,
) -> Result<(), MtprotoError> {
    if message_id <= 0 || title.trim().is_empty() {
        return Err(MtprotoError::Message(
            "checklist title and message required".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                let messages = crate::dialogs::fetch_message(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                )?;
                let peer = input_peer_from_cached(crate::peers::require_usable_peer(
                    &state.peers,
                    chat_id,
                )?);
                let request = title_request(&messages, peer, chat_id, message_id, &title)?;
                let response: Updates =
                    crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request)?;
                super::management::cache_response_peers(state, &response);
                super::management::enqueue_response(state.snapshot.dc_id, &response)
            })
        })
    })
}

fn title_request(
    messages: &Vector<Box<Message>>,
    peer: InputPeer,
    chat_id: i64,
    message_id: i32,
    title: &str,
) -> Result<MessagesEditMessageRequest, MtprotoError> {
    let media = checked_media(messages, chat_id, message_id)?;
    Ok(media_request(
        peer,
        message_id,
        title_media(&media.todo, title)?,
    ))
}

fn checked_media(
    messages: &Vector<Box<Message>>,
    chat_id: i64,
    message_id: i32,
) -> Result<&MessageMediaToDoConstructor, MtprotoError> {
    let mut rows = crate::peers::vector_boxed_items(messages);
    let Some(Message::Message(message)) = rows.next() else {
        return Err(MtprotoError::Message("checklist unavailable".into()));
    };
    if rows.next().is_some()
        || message.id != message_id
        || crate::peers::peer_chat_id(&message.peer_id) != chat_id
    {
        return Err(MtprotoError::Message("unexpected message identity".into()));
    }
    if message.out.is_none() {
        return Err(MtprotoError::Message("checklist owner required".into()));
    }
    let Some(MessageMedia::MessageMediaToDo(media)) = message.media.as_deref() else {
        return Err(MtprotoError::Message("checklist unavailable".into()));
    };
    Ok(media)
}

fn media_request(
    peer: InputPeer,
    message_id: i32,
    media: InputMedia,
) -> MessagesEditMessageRequest {
    MessagesEditMessageRequest {
        flags: MessagesEditMessageRequest::MEDIA_FLAG,
        no_webpage: None,
        invert_media: None,
        peer: Box::new(peer),
        id: message_id,
        message: None,
        media: Some(Box::new(media)),
        reply_markup: None,
        entities: None,
        schedule_date: None,
        schedule_repeat_period: None,
        quick_reply_shortcut_id: None,
        rich_message: None,
    }
}

fn text_request(
    messages: &Vector<Box<Message>>,
    peer: InputPeer,
    chat_id: i64,
    message_id: i32,
    edit: &ChecklistTextEditDto,
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
) -> Result<MessagesEditMessageRequest, MtprotoError> {
    content_request(messages, peer, chat_id, message_id, edit, peers, false)
}

fn content_request(
    messages: &Vector<Box<Message>>,
    peer: InputPeer,
    chat_id: i64,
    message_id: i32,
    edit: &ChecklistTextEditDto,
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
    remove: bool,
) -> Result<MessagesEditMessageRequest, MtprotoError> {
    let media = checked_media(messages, chat_id, message_id)?;
    let TodoList::TodoList(mut todo) = (*media.todo).clone();
    let target = if let Some(id) = edit.item_id {
        let Vector::Vector(items) = todo.list.as_mut();
        let mut found = None;
        for item in &mut items.field_1 {
            let TodoItem::TodoItem(item) = item.as_mut();
            if item.id == id {
                if found.is_some() {
                    return Err(MtprotoError::Message("ambiguous checklist item".into()));
                }
                found = Some(&mut item.title);
            }
        }
        found.ok_or_else(|| MtprotoError::Message("checklist item unavailable".into()))?
    } else {
        &mut todo.title
    };
    let TextWithEntities::TextWithEntities(original) = target.as_ref();
    let expected = parse_entity_rows(edit.expected_entities_json.as_deref())?;
    let current =
        parse_entity_rows(crate::dialogs::entities_to_json(Some(&original.entities)).as_deref())?;
    if original.text != edit.expected_text
        || expected != current
        || crate::peers::vector_boxed_items(&original.entities).count() != current.len()
    {
        return Err(MtprotoError::Message(
            "checklist text changed or formatting unsupported".into(),
        ));
    }
    if remove {
        let id = edit
            .item_id
            .ok_or_else(|| MtprotoError::Message("checklist item required".into()))?;
        let Vector::Vector(items) = todo.list.as_mut();
        if items.field_1.len() <= 1 {
            return Err(MtprotoError::Message(
                "cannot remove final checklist item".into(),
            ));
        }
        items.field_1.retain(|item| match item.as_ref() {
            TodoItem::TodoItem(item) => item.id != id,
        });
        items.field_0 = items.field_1.len() as u32;
        return Ok(media_request(
            peer,
            message_id,
            InputMedia::InputMediaTodo(InputMediaTodoConstructor {
                todo: Box::new(TodoList::TodoList(todo)),
            }),
        ));
    }
    let limit = if edit.item_id.is_some() { 64 } else { 32 };
    if edit.text.trim().is_empty() || edit.text.encode_utf16().count() > limit {
        return Err(MtprotoError::Message(
            "invalid checklist text length".into(),
        ));
    }
    let rows = parse_entity_rows(edit.entities_json.as_deref())?;
    let mut boundaries = vec![0_i64];
    for ch in edit.text.chars() {
        boundaries.push(boundaries.last().copied().unwrap() + ch.len_utf16() as i64);
    }
    for row in &rows {
        let start = row.get("offset").and_then(|v| v.as_i64()).unwrap_or(-1);
        let length = row.get("length").and_then(|v| v.as_i64()).unwrap_or(0);
        if length <= 0
            || !boundaries.contains(&start)
            || !start
                .checked_add(length)
                .is_some_and(|end| boundaries.contains(&end))
        {
            return Err(MtprotoError::Message(
                "invalid checklist entity range".into(),
            ));
        }
    }
    let (_, entities) =
        crate::messages::input_entities_from_json(edit.entities_json.as_deref(), peers)?;
    if entities
        .as_deref()
        .map(crate::peers::vector_boxed_items)
        .map(|v| v.count())
        .unwrap_or(0)
        != rows.len()
    {
        return Err(MtprotoError::Message(
            "unsupported checklist formatting".into(),
        ));
    }
    *target = Box::new(TextWithEntities::TextWithEntities(
        TextWithEntitiesConstructor {
            text: edit.text.clone(),
            entities: entities.unwrap_or_else(|| {
                Box::new(Vector::Vector(VectorConstructor {
                    field_0: 0,
                    field_1: vec![],
                }))
            }),
        },
    ));
    Ok(media_request(
        peer,
        message_id,
        InputMedia::InputMediaTodo(InputMediaTodoConstructor {
            todo: Box::new(TodoList::TodoList(todo)),
        }),
    ))
}

fn parse_entity_rows(raw: Option<&str>) -> Result<Vec<serde_json::Value>, MtprotoError> {
    let raw = raw.unwrap_or("[]");
    if raw.len() > 65536 {
        return Err(MtprotoError::Message(
            "checklist formatting too large".into(),
        ));
    }
    serde_json::from_str(raw)
        .map_err(|_| MtprotoError::Message("invalid checklist formatting".into()))
}

fn title_media(todo: &TodoList, title: &str) -> Result<InputMedia, MtprotoError> {
    let title = title.trim();
    if title.is_empty() {
        return Err(MtprotoError::Message("checklist title required".into()));
    }
    let TodoList::TodoList(mut todo) = todo.clone();
    todo.title = Box::new(TextWithEntities::TextWithEntities(
        TextWithEntitiesConstructor {
            text: title.to_owned(),
            entities: Box::new(Vector::Vector(VectorConstructor {
                field_0: 0,
                field_1: Vec::new(),
            })),
        },
    ));
    Ok(InputMedia::InputMediaTodo(InputMediaTodoConstructor {
        todo: Box::new(TodoList::TodoList(todo)),
    }))
}

#[cfg(test)]
#[path = "../../tests/unit/client_checklists_tests.rs"]
mod tests;
