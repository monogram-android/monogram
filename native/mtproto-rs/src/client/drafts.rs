#[cfg(test)]
#[path = "../../tests/unit/client_drafts_tests.rs"]
mod tests;

use super::{call_with_migrate, with_client_mut};
use crate::MtprotoError;
use crate::peers::{input_peer_from_cached, peer_chat_id, require_usable_peer, vector_boxed_items};
use serde::{Deserialize, Serialize};
use tellers_mtproto::latest::api::*;

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct CloudDraft {
    pub chat_id: i64,
    pub thread_id: i32,
    pub text: String,
    pub entities_json: Option<String>,
    pub reply_to_message_id: i32,
    pub date: i32,
    pub no_webpage: bool,
    pub has_unsupported_content: bool,
}

pub fn get_cloud_drafts(handle: u64) -> Result<Vec<CloudDraft>, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesGetAllDraftsRequest {},
            )?;
            super::management::cache_response_peers(state, &response);
            Ok(drafts_from_updates(&response))
        })
    })
}

pub fn save_cloud_draft(handle: u64, draft: CloudDraft) -> Result<(), MtprotoError> {
    if draft.has_unsupported_content {
        return Err(MtprotoError::Message(
            "this draft contains content that cannot be changed by the text composer".into(),
        ));
    }
    if draft.thread_id < 0 || draft.reply_to_message_id < 0 {
        return Err(MtprotoError::Message("invalid draft reply context".into()));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = input_peer_from_cached(require_usable_peer(&state.peers, draft.chat_id)?);
            let request = save_request(peer, &draft, &state.peers)?;
            let response: Bool =
                crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request)?;
            match response {
                Bool::BoolTrue(_) => Ok(()),
                Bool::BoolFalse(_) => Err(MtprotoError::Message("draft was not saved".into())),
            }
        })
    })
}

pub(crate) fn drafts_from_updates(updates: &Updates) -> Vec<CloudDraft> {
    let (updates, date) = match updates {
        Updates::Updates(value) => (&value.updates, value.date),
        Updates::UpdatesCombined(value) => (&value.updates, value.date),
        _ => return Vec::new(),
    };
    vector_boxed_items(updates)
        .filter_map(|update| match update {
            Update::UpdateDraftMessage(value) => Some(draft_from_update(value, date)),
            _ => None,
        })
        .collect()
}

pub(crate) fn draft_from_update(
    update: &UpdateDraftMessageConstructor,
    envelope_date: i32,
) -> CloudDraft {
    let mut result = CloudDraft {
        chat_id: peer_chat_id(&update.peer),
        thread_id: update.top_msg_id.unwrap_or(0),
        text: String::new(),
        entities_json: None,
        reply_to_message_id: 0,
        date: envelope_date,
        no_webpage: false,
        has_unsupported_content: update.saved_peer_id.is_some(),
    };
    match update.draft.as_ref() {
        DraftMessage::DraftMessageEmpty(draft) => result.date = draft.date.unwrap_or(envelope_date),
        DraftMessage::DraftMessage(draft) => {
            result.text = draft.message.clone();
            result.date = draft.date;
            result.no_webpage = draft.no_webpage.is_some();
            result.entities_json = draft_entities_json(draft.entities.as_deref());
            let rebuilt = crate::messages::entities_from_json(result.entities_json.as_deref())
                .ok()
                .and_then(|(_, entities)| entities);
            let original_count = draft
                .entities
                .as_ref()
                .map(|items| crate::peers::vector_items(items).len())
                .unwrap_or(0);
            let entities_changed =
                original_count > 0 && draft.entities.as_deref() != rebuilt.as_deref();
            result.has_unsupported_content |= draft.media.is_some()
                || draft.effect.is_some()
                || draft.suggested_post.is_some()
                || draft.rich_message.is_some()
                || draft.invert_media.is_some()
                || entities_changed;
            if let Some(reply) = &draft.reply_to {
                match reply.as_ref() {
                    InputReplyTo::InputReplyToMessage(reply) => {
                        result.thread_id = update.top_msg_id.or(reply.top_msg_id).unwrap_or(0);
                        result.reply_to_message_id = if reply.reply_to_msg_id == result.thread_id {
                            0
                        } else {
                            reply.reply_to_msg_id
                        };
                        result.has_unsupported_content |= reply.reply_to_peer_id.is_some()
                            || reply.quote_text.is_some()
                            || reply.quote_entities.is_some()
                            || reply.quote_offset.is_some()
                            || reply.monoforum_peer_id.is_some()
                            || reply.todo_item_id.is_some()
                            || reply.poll_option.is_some()
                            || reply.quote_rich.is_some();
                    }
                    _ => result.has_unsupported_content = true,
                }
            }
        }
    }
    result
}

pub(super) fn draft_entities_json(entities: Option<&Vector<Box<MessageEntity>>>) -> Option<String> {
    let entities = entities?;
    let raw = crate::dialogs::entities_to_json(Some(entities))?;
    let mut mapped: Vec<serde_json::Value> = serde_json::from_str(&raw).ok()?;
    if mapped.len() != crate::peers::vector_items(entities).len() {
        return None;
    }
    for (entity, json) in vector_boxed_items(entities).zip(&mut mapped) {
        match entity {
            MessageEntity::MessageEntityMentionName(mention) => {
                json["kind"] = "mention_name".into();
                json["url"] = mention.user_id.to_string().into();
            }
            MessageEntity::MessageEntityBlockquote(quote) if quote.collapsed.is_some() => {
                json["url"] = "collapsed".into();
            }
            _ => {}
        }
    }
    serde_json::to_string(&mapped).ok()
}

fn save_request(
    peer: InputPeer,
    draft: &CloudDraft,
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
) -> Result<MessagesSaveDraftRequest, MtprotoError> {
    let (_, reply_to) =
        crate::messages::input_reply_to_thread(draft.reply_to_message_id, draft.thread_id);
    let (_, entities) =
        crate::messages::input_entities_from_json(draft.entities_json.as_deref(), peers)?;
    Ok(MessagesSaveDraftRequest {
        flags: (u32::from(draft.no_webpage) << 1)
            | (u32::from(entities.is_some()) << 3)
            | (u32::from(reply_to.is_some()) << 4),
        no_webpage: draft
            .no_webpage
            .then(|| Box::new(True::True(TrueConstructor {}))),
        invert_media: None,
        reply_to,
        peer: Box::new(peer),
        message: draft.text.clone(),
        entities,
        media: None,
        effect: None,
        suggested_post: None,
        rich_message: None,
    })
}
