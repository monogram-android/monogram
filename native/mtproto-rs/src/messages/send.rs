//! https://core.telegram.org/method/messages.sendMessage
//! https://core.telegram.org/method/messages.editMessage
//! https://core.telegram.org/method/messages.deleteMessages
//! https://core.telegram.org/method/messages.forwardMessages

use crate::{HashMap, HashMapExt};

use tellers_mtproto::latest::api::{
    ChannelsDeleteMessagesRequest, InputChannel, InputChannelConstructor, InputMedia,
    InputMediaWebPageConstructor, InputReplyTo, InputReplyToMessageConstructor,
    MessagesAffectedMessages, MessagesDeleteMessagesRequest, MessagesEditMessageRequest,
    MessagesForwardMessagesRequest, MessagesSendMediaRequest, MessagesSendMessageRequest, True,
    TrueConstructor, Updates, Vector, VectorConstructor,
};
use tellers_mtproto_crypto::fill_random;
use tellers_mtproto_session::Snapshot;

use super::entities::entities_from_json;
use crate::api_invoke;
use crate::dialogs::message_to_dto;
use crate::media::MediaIndex;
use crate::peers::{
    self, CachedPeer, PeerKind, channel_id_from_chat_id, input_peer_from_cached, vector_boxed_items,
};
use crate::{MessageDto, MtprotoError};

pub(crate) fn random_id() -> i64 {
    let mut bytes = [0_u8; 8];
    let _ = fill_random(&mut bytes);
    let value = i64::from_le_bytes(bytes);
    if value == 0 { 1 } else { value }
}

pub(crate) fn message_from_updates(
    updates: Updates,
    chat_id: i64,
    text: &str,
    media_index: &mut MediaIndex,
) -> MessageDto {
    match updates {
        Updates::Updates(u) => first_new_message(&u.updates, media_index),
        Updates::UpdatesCombined(u) => first_new_message(&u.updates, media_index),
        Updates::UpdateShortSentMessage(s) => Some(MessageDto {
            chat_id,
            id: s.id,
            sender_id: None,
            text: Some(text.to_string()),
            date: i64::from(s.date),
            edit_date: None,
            outgoing: true,
            media_kind: None,
            media_cache_key: None,
            thumb_cache_key: None,
            media_duration: None,
            media_width: None,
            media_height: None,
            reply_quote: None,
            entities_json: None,
            noforwards: false,
            reply_to_msg_id: None,
            reply_to_top_id: None,
            fwd_from: None,
            fwd_from_id: None,
            fwd_date: None,
            via_bot: None,
            sender_name: None,
            sender_emoji_status_document_id: None,
            grouped_id: None,
            file_name: None,
            file_size: None,
            supports_streaming: false,
            reactions_json: None,
            replies_count: 0,
            discussion_peer_id: None,
            reply_markup_json: None,
        }),
        Updates::UpdateShortMessage(m) => Some(MessageDto {
            chat_id,
            id: m.id,
            sender_id: Some(m.user_id),
            text: Some(m.message.clone()),
            date: i64::from(m.date),
            edit_date: None,
            outgoing: m.out.is_some(),
            media_kind: None,
            media_cache_key: None,
            thumb_cache_key: None,
            media_duration: None,
            media_width: None,
            media_height: None,
            reply_quote: crate::dialogs::reply_meta(m.reply_to.as_deref()).2,
            entities_json: None,
            noforwards: false,
            reply_to_msg_id: crate::dialogs::reply_meta(m.reply_to.as_deref()).0,
            reply_to_top_id: crate::dialogs::reply_meta(m.reply_to.as_deref()).1,
            fwd_from: crate::dialogs::fwd_from_label(m.fwd_from.as_deref(), &crate::HashMap::new()),
            fwd_from_id: crate::dialogs::fwd_origin(m.fwd_from.as_deref()).0,
            fwd_date: crate::dialogs::fwd_origin(m.fwd_from.as_deref()).1,
            via_bot: crate::dialogs::via_bot_label(
                m.via_bot_id,
                &crate::HashMap::new(),
                &crate::HashMap::new(),
            ),
            sender_name: None,
            sender_emoji_status_document_id: None,
            grouped_id: None,
            file_name: None,
            file_size: None,
            supports_streaming: false,
            reactions_json: None,
            replies_count: 0,
            discussion_peer_id: None,
            reply_markup_json: None,
        }),
        Updates::UpdateShortChatMessage(m) => Some(MessageDto {
            chat_id,
            id: m.id,
            sender_id: Some(m.from_id),
            text: Some(m.message.clone()),
            date: i64::from(m.date),
            edit_date: None,
            outgoing: m.out.is_some(),
            media_kind: None,
            media_cache_key: None,
            thumb_cache_key: None,
            media_duration: None,
            media_width: None,
            media_height: None,
            reply_quote: crate::dialogs::reply_meta(m.reply_to.as_deref()).2,
            entities_json: None,
            noforwards: false,
            reply_to_msg_id: crate::dialogs::reply_meta(m.reply_to.as_deref()).0,
            reply_to_top_id: crate::dialogs::reply_meta(m.reply_to.as_deref()).1,
            fwd_from: crate::dialogs::fwd_from_label(m.fwd_from.as_deref(), &crate::HashMap::new()),
            fwd_from_id: crate::dialogs::fwd_origin(m.fwd_from.as_deref()).0,
            fwd_date: crate::dialogs::fwd_origin(m.fwd_from.as_deref()).1,
            via_bot: crate::dialogs::via_bot_label(
                m.via_bot_id,
                &crate::HashMap::new(),
                &crate::HashMap::new(),
            ),
            sender_name: None,
            sender_emoji_status_document_id: None,
            grouped_id: None,
            file_name: None,
            file_size: None,
            supports_streaming: false,
            reactions_json: None,
            replies_count: 0,
            discussion_peer_id: None,
            reply_markup_json: None,
        }),
        _ => None,
    }
    .unwrap_or(MessageDto {
        chat_id,
        id: 0,
        sender_id: None,
        text: Some(text.to_string()),
        date: 0,
        edit_date: None,
        outgoing: true,
        media_kind: None,
        media_cache_key: None,
        thumb_cache_key: None,
        media_duration: None,
        media_width: None,
        media_height: None,
        reply_quote: None,
        entities_json: None,
        noforwards: false,
        reply_to_msg_id: None,
        reply_to_top_id: None,
        fwd_from: None,
        fwd_from_id: None,
        fwd_date: None,
        via_bot: None,
        sender_name: None,
        sender_emoji_status_document_id: None,
        grouped_id: None,
        file_name: None,
        file_size: None,
        supports_streaming: false,
        reactions_json: None,
        replies_count: 0,
        discussion_peer_id: None,
        reply_markup_json: None,
    })
}

pub(crate) fn first_new_message(
    updates: &tellers_mtproto::latest::api::Vector<Box<tellers_mtproto::latest::api::Update>>,
    media_index: &mut MediaIndex,
) -> Option<MessageDto> {
    use tellers_mtproto::latest::api::Update;
    for update in vector_boxed_items(updates) {
        match update {
            Update::UpdateNewMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    return Some(dto);
                }
            }
            Update::UpdateNewChannelMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    return Some(dto);
                }
            }
            Update::UpdateEditMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    return Some(dto);
                }
            }
            Update::UpdateEditChannelMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    return Some(dto);
                }
            }
            _ => {}
        }
    }
    None
}

pub(crate) fn all_new_messages(
    updates: &tellers_mtproto::latest::api::Vector<Box<tellers_mtproto::latest::api::Update>>,
    media_index: &mut MediaIndex,
) -> Vec<MessageDto> {
    use tellers_mtproto::latest::api::Update;
    let mut out = Vec::new();
    for update in vector_boxed_items(updates) {
        match update {
            Update::UpdateNewMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    out.push(dto);
                }
            }
            Update::UpdateNewChannelMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    out.push(dto);
                }
            }
            _ => {}
        }
    }
    out
}

pub(crate) fn input_reply_to(reply_to_msg_id: i32) -> (u32, Option<Box<InputReplyTo>>) {
    input_reply_to_thread(reply_to_msg_id, 0)
}

pub(crate) fn input_reply_to_thread(
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> (u32, Option<Box<InputReplyTo>>) {
    if reply_to_msg_id <= 0 && top_msg_id <= 0 {
        return (0, None);
    }
    let reply_id = if reply_to_msg_id > 0 {
        reply_to_msg_id
    } else {
        top_msg_id
    };
    let (top_flag, top) = if top_msg_id > 0 {
        (
            InputReplyToMessageConstructor::TOP_MSG_ID_FLAG,
            Some(top_msg_id),
        )
    } else {
        (0, None)
    };
    (
        MessagesSendMessageRequest::REPLY_TO_FLAG,
        Some(Box::new(InputReplyTo::InputReplyToMessage(
            InputReplyToMessageConstructor {
                flags: top_flag,
                reply_to_msg_id: reply_id,
                top_msg_id: top,
                reply_to_peer_id: None,
                quote_text: None,
                quote_entities: None,
                quote_offset: None,
                monoforum_peer_id: None,
                todo_item_id: None,
                poll_option: None,
            },
        ))),
    )
}

pub fn send_text(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    text: &str,
    reply_to_msg_id: i32,
    entities_json: Option<&str>,
    top_msg_id: i32,
    webpage_url: Option<&str>,
) -> Result<MessageDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (reply_flag, reply_to) = input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let (entities_flag, entities) = entities_from_json(entities_json)?;
    let peer = Box::new(input_peer_from_cached(cached));
    let updates: Updates = if let Some(url) = webpage_url.map(str::trim).filter(|s| !s.is_empty()) {
        api_invoke::invoke_api(
            snapshot,
            api_id,
            MessagesSendMediaRequest {
                flags: reply_flag | entities_flag,
                silent: None,
                background: None,
                clear_draft: None,
                noforwards: None,
                update_stickersets_order: None,
                invert_media: None,
                allow_paid_floodskip: None,
                peer,
                reply_to,
                media: Box::new(InputMedia::InputMediaWebPage(InputMediaWebPageConstructor {
                    flags: InputMediaWebPageConstructor::OPTIONAL_FLAG,
                    force_large_media: None,
                    force_small_media: None,
                    optional: Some(Box::new(True::True(TrueConstructor {}))),
                    url: url.to_string(),
                })),
                message: text.to_string(),
                random_id: random_id(),
                reply_markup: None,
                entities,
                schedule_date: None,
                schedule_repeat_period: None,
                send_as: None,
                quick_reply_shortcut: None,
                effect: None,
                allow_paid_stars: None,
                suggested_post: None,
            },
        )?
    } else {
        api_invoke::invoke_api(
            snapshot,
            api_id,
            MessagesSendMessageRequest {
                flags: reply_flag | entities_flag,
                no_webpage: None,
                silent: None,
                background: None,
                clear_draft: None,
                noforwards: None,
                update_stickersets_order: None,
                invert_media: None,
                allow_paid_floodskip: None,
                peer,
                reply_to,
                message: text.to_string(),
                random_id: random_id(),
                reply_markup: None,
                entities,
                schedule_date: None,
                schedule_repeat_period: None,
                send_as: None,
                quick_reply_shortcut: None,
                effect: None,
                allow_paid_stars: None,
                suggested_post: None,
                rich_message: None,
            },
        )?
    };
    let mut dto = message_from_updates(updates, chat_id, text, media_index);
    if dto.entities_json.is_none() {
        dto.entities_json = entities_json
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string);
    }
    Ok(dto)
}

pub(crate) const PHOTO_PART: usize = crate::upload_rpc::FILE_PART;
pub(crate) const PHOTO_MAX: u64 = crate::upload_rpc::PHOTO_MAX;

pub fn edit_text(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    message_id: i32,
    text: &str,
    entities_json: Option<&str>,
) -> Result<MessageDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (entities_flag, entities) = entities_from_json(entities_json)?;
    let request = MessagesEditMessageRequest {
        flags: MessagesEditMessageRequest::MESSAGE_FLAG | entities_flag,
        no_webpage: None,
        invert_media: None,
        peer: Box::new(input_peer_from_cached(cached)),
        id: message_id,
        message: Some(text.to_string()),
        media: None,
        reply_markup: None,
        entities,
        schedule_date: None,
        schedule_repeat_period: None,
        quick_reply_shortcut_id: None,
        rich_message: None,
    };
    let updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(message_from_updates(updates, chat_id, text, media_index))
}

pub fn delete_message(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
    revoke: bool,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let ids = Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![message_id],
    }));
    if cached.kind == PeerKind::Channel {
        let channel_id = channel_id_from_chat_id(chat_id)
            .ok_or_else(|| MtprotoError::Message("not a channel".into()))?;
        let _: MessagesAffectedMessages = api_invoke::invoke_api(
            snapshot,
            api_id,
            ChannelsDeleteMessagesRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id,
                    access_hash: cached.access_hash,
                })),
                id: ids,
            },
        )?;
        return Ok(());
    }
    let revoke_flag = if revoke {
        Some(Box::new(True::True(TrueConstructor {})))
    } else {
        None
    };
    let _: MessagesAffectedMessages = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesDeleteMessagesRequest {
            flags: if revoke {
                MessagesDeleteMessagesRequest::REVOKE_FLAG
            } else {
                0
            },
            revoke: revoke_flag,
            id: ids,
        },
    )?;
    Ok(())
}

/// https://core.telegram.org/method/messages.forwardMessages
pub fn forward_messages(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    from_chat_id: i64,
    message_ids: Vec<i32>,
    to_chat_id: i64,
    drop_author: bool,
) -> Result<Vec<MessageDto>, MtprotoError> {
    if message_ids.is_empty() || message_ids.iter().any(|&message_id| message_id <= 0) {
        return Err(MtprotoError::Message("valid message ids required".into()));
    }
    let from = peers::require_usable_peer(peers, from_chat_id)?;
    let to = peers::require_usable_peer(peers, to_chat_id)?;
    let message_count = message_ids.len() as u32;
    let ids = Box::new(Vector::Vector(VectorConstructor {
        field_0: message_count,
        field_1: message_ids,
    }));
    let random_ids = Box::new(Vector::Vector(VectorConstructor {
        field_0: message_count,
        field_1: (0..message_count).map(|_| random_id()).collect(),
    }));
    let request = MessagesForwardMessagesRequest {
        flags: if drop_author {
            MessagesForwardMessagesRequest::DROP_AUTHOR_FLAG
        } else {
            0
        },
        silent: None,
        background: None,
        with_my_score: None,
        drop_author: drop_author.then(|| Box::new(True::True(TrueConstructor {}))),
        drop_media_captions: None,
        noforwards: None,
        allow_paid_floodskip: None,
        from_peer: Box::new(input_peer_from_cached(from)),
        id: ids,
        random_id: random_ids,
        to_peer: Box::new(input_peer_from_cached(to)),
        top_msg_id: None,
        reply_to: None,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        video_timestamp: None,
        allow_paid_stars: None,
        suggested_post: None,
        from_ephemeral: None,
    };
    let updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(messages_from_forward_updates(
        updates,
        to_chat_id,
        media_index,
    ))
}

pub(crate) fn messages_from_forward_updates(
    updates: Updates,
    chat_id: i64,
    media_index: &mut MediaIndex,
) -> Vec<MessageDto> {
    match updates {
        Updates::Updates(u) => collect_new_messages(&u.updates, media_index),
        Updates::UpdatesCombined(u) => collect_new_messages(&u.updates, media_index),
        other => first_new_from_short(other, chat_id).into_iter().collect(),
    }
}

pub(crate) fn first_new_from_short(updates: Updates, chat_id: i64) -> Option<MessageDto> {
    match updates {
        Updates::UpdateShortSentMessage(s) => Some(MessageDto {
            chat_id,
            id: s.id,
            sender_id: None,
            text: None,
            date: i64::from(s.date),
            edit_date: None,
            outgoing: true,
            media_kind: None,
            media_cache_key: None,
            thumb_cache_key: None,
            media_duration: None,
            media_width: None,
            media_height: None,
            reply_quote: None,
            entities_json: None,
            noforwards: false,
            reply_to_msg_id: None,
            reply_to_top_id: None,
            fwd_from: None,
            fwd_from_id: None,
            fwd_date: None,
            via_bot: None,
            sender_name: None,
            sender_emoji_status_document_id: None,
            grouped_id: None,
            file_name: None,
            file_size: None,
            supports_streaming: false,
            reactions_json: None,
            replies_count: 0,
            discussion_peer_id: None,
            reply_markup_json: None,
        }),
        _ => None,
    }
}

pub(crate) fn collect_new_messages(
    updates: &tellers_mtproto::latest::api::Vector<Box<tellers_mtproto::latest::api::Update>>,
    media_index: &mut MediaIndex,
) -> Vec<MessageDto> {
    use tellers_mtproto::latest::api::Update;
    let mut out = Vec::new();
    for update in vector_boxed_items(updates) {
        let message = match update {
            Update::UpdateNewMessage(u) => message_to_dto(&u.message, media_index),
            Update::UpdateNewChannelMessage(u) => message_to_dto(&u.message, media_index),
            _ => None,
        };
        if let Some(dto) = message {
            out.push(dto);
        }
    }
    out
}
