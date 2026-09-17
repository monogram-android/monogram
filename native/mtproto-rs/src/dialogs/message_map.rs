use crate::{HashMap, HashMapExt};

use tellers_mtproto::latest::api::{
    Chat as TlChat, Message, MessageEntity, MessageFwdHeader, MessageReplyHeader, MessagesMessages,
    RichMessage, User, Vector,
};

use super::peers::{cache_from_users_chats, display_name};
use crate::emoji_status::emoji_status_document_id;
use crate::media::{self, MediaIndex};
use crate::peers::{
    CachedPeer, chat_id_for_channel, chat_id_for_chat, chat_id_for_user, peer_chat_id,
    vector_boxed_items,
};
use crate::service_messages::service_action_text;
use crate::{MessageDto, MtprotoError};

pub(crate) fn message_to_dto(msg: &Message, media_index: &mut MediaIndex) -> Option<MessageDto> {
    message_to_dto_named(
        msg,
        media_index,
        &HashMap::new(),
        &HashMap::new(),
        &HashMap::new(),
    )
}

pub(crate) fn message_to_dto_named(
    msg: &Message,
    media_index: &mut MediaIndex,
    titles: &HashMap<i64, String>,
    usernames: &HashMap<i64, String>,
    emoji_status: &HashMap<i64, i64>,
) -> Option<MessageDto> {
    match msg {
        Message::Message(m) => {
            let chat_id = peer_chat_id(&m.peer_id);
            let indexed = media::index_message_media(msg, media_index);
            let fallback = m
                .media
                .as_ref()
                .and_then(|media| media::media_fallback_text(media));
            let rich = m
                .rich_message
                .as_ref()
                .and_then(|rich| match rich.as_ref() {
                    RichMessage::RichMessage(body) => {
                        let photos: Vec<_> = vector_boxed_items(&body.photos).cloned().collect();
                        let formatted = media::page_blocks_formatted_media(
                            vector_boxed_items(&body.blocks),
                            &photos,
                        );
                        (!formatted.text.is_empty()).then_some(formatted)
                    }
                });
            let text = if !m.message.is_empty() {
                Some(m.message.clone())
            } else if let Some(rich) = rich.as_ref() {
                Some(rich.text.clone())
            } else {
                match indexed.kind.as_deref() {
                    Some(
                        "sticker" | "sticker_animated" | "sticker_video" | "photo" | "video"
                        | "gif" | "voice",
                    ) => None,
                    _ => fallback,
                }
            };
            if text.is_none()
                && indexed.kind.is_none()
                && m.fwd_from.is_none()
                && m.via_bot_id.is_none()
                && m.rich_message.is_none()
            {
                return None;
            }
            let media_kind = match (indexed.kind.as_deref(), text.as_ref()) {
                (Some("unsupported"), Some(_)) => None,
                _ => indexed.kind.clone(),
            };
            let (reply_to_msg_id, reply_to_top_id, reply_quote) = reply_meta(m.reply_to.as_deref());
            Some(MessageDto {
                chat_id,
                id: m.id,
                sender_id: m.from_id.as_ref().map(|p| peer_chat_id(p)),
                text,
                date: i64::from(m.date),
                edit_date: if m.edit_hide.is_some() {
                    None
                } else {
                    m.edit_date.map(i64::from)
                },
                outgoing: m.out.is_some(),
                media_kind,
                media_cache_key: indexed.cache_key,
                thumb_cache_key: indexed.thumb_cache_key,
                media_duration: indexed.duration,
                media_width: indexed.width,
                media_height: indexed.height,
                reply_quote,
                entities_json: rich
                    .as_ref()
                    .and_then(|formatted| {
                        (!formatted.entities.is_empty())
                            .then(|| serde_json::to_string(&formatted.entities).ok())
                            .flatten()
                    })
                    .or_else(|| entities_to_json(m.entities.as_deref())),
                noforwards: m.noforwards.is_some(),
                reply_to_msg_id,
                reply_to_top_id,
                fwd_from: fwd_from_label(m.fwd_from.as_deref(), titles),
                fwd_from_id: fwd_origin(m.fwd_from.as_deref()).0,
                fwd_date: fwd_origin(m.fwd_from.as_deref()).1,
                via_bot: via_bot_label(m.via_bot_id, usernames, titles),
                sender_name: m
                    .from_id
                    .as_ref()
                    .and_then(|peer| titles.get(&peer_chat_id(peer)).cloned()),
                sender_emoji_status_document_id: m
                    .from_id
                    .as_ref()
                    .and_then(|peer| emoji_status.get(&peer_chat_id(peer)).copied()),
                grouped_id: m.grouped_id,
                file_name: indexed.file_name,
                file_size: indexed.file_size,
                reactions_json: crate::extras_rpc::reactions_to_json(
                    m.reactions.as_ref().map(|r| r.as_ref()),
                ),
                replies_count: crate::extras_rpc::replies_meta(
                    m.replies.as_ref().map(|r| r.as_ref()),
                )
                .0,
                discussion_peer_id: crate::extras_rpc::replies_meta(
                    m.replies.as_ref().map(|r| r.as_ref()),
                )
                .1,
                reply_markup_json: crate::reply_markup::to_json(m.reply_markup.as_deref()),
            })
        }
        Message::MessageService(m) => {
            let sender_id = m.from_id.as_ref().map(|p| peer_chat_id(p));
            let sender_name = sender_id.and_then(|id| titles.get(&id).cloned());
            let (reply_to_msg_id, reply_to_top_id, _) = reply_meta(m.reply_to.as_deref());
            Some(MessageDto {
                chat_id: peer_chat_id(&m.peer_id),
                id: m.id,
                sender_id,
                text: Some(service_action_text(
                    m.action.as_ref(),
                    sender_id,
                    sender_name.as_deref().unwrap_or(""),
                    titles,
                )),
                date: i64::from(m.date),
                edit_date: None,
                outgoing: m.out.is_some(),
                media_kind: Some("service".into()),
                media_cache_key: None,
                thumb_cache_key: None,
                media_duration: None,
                media_width: None,
                media_height: None,
                reply_quote: None,
                entities_json: None,
                noforwards: false,
                reply_to_msg_id,
                reply_to_top_id,
                fwd_from: None,
                fwd_from_id: None,
                fwd_date: None,
                via_bot: None,
                sender_name,
                sender_emoji_status_document_id: sender_id
                    .and_then(|id| emoji_status.get(&id).copied()),
                grouped_id: None,
                file_name: None,
                file_size: None,
                reactions_json: None,
                replies_count: 0,
                discussion_peer_id: None,
                reply_markup_json: None,
            })
        }
        _ => None,
    }
}

pub(crate) fn reply_meta(
    header: Option<&MessageReplyHeader>,
) -> (Option<i32>, Option<i32>, Option<String>) {
    let Some(header) = header else {
        return (None, None, None);
    };
    let MessageReplyHeader::MessageReplyHeader(h) = header else {
        return (None, None, None);
    };
    (
        h.reply_to_msg_id,
        h.reply_to_top_id,
        h.quote_text.clone().filter(|s| !s.is_empty()),
    )
}

pub(crate) fn fwd_origin(header: Option<&MessageFwdHeader>) -> (Option<i64>, Option<i64>) {
    match header {
        Some(MessageFwdHeader::MessageFwdHeader(h)) => (
            h.from_id.as_ref().map(|peer| peer_chat_id(peer)),
            Some(i64::from(h.date)),
        ),
        _ => (None, None),
    }
}

pub(crate) fn fwd_from_label(
    header: Option<&MessageFwdHeader>,
    titles: &HashMap<i64, String>,
) -> Option<String> {
    let MessageFwdHeader::MessageFwdHeader(h) = header? else {
        return None;
    };
    if let Some(name) = h.from_name.as_ref().filter(|s| !s.is_empty()) {
        return Some(name.clone());
    }
    if let Some(author) = h.post_author.as_ref().filter(|s| !s.is_empty()) {
        return Some(author.clone());
    }
    h.from_id
        .as_ref()
        .and_then(|peer| titles.get(&peer_chat_id(peer)).cloned())
}

pub(crate) fn via_bot_label(
    via_bot_id: Option<i64>,
    usernames: &HashMap<i64, String>,
    titles: &HashMap<i64, String>,
) -> Option<String> {
    let id = via_bot_id?;
    usernames
        .get(&id)
        .cloned()
        .or_else(|| titles.get(&id).cloned())
}

pub(crate) fn collect_peer_labels(
    users: impl Iterator<Item = User>,
    chats: impl Iterator<Item = TlChat>,
) -> (
    HashMap<i64, String>,
    HashMap<i64, String>,
    HashMap<i64, i64>,
) {
    let mut titles = HashMap::new();
    let mut usernames = HashMap::new();
    let mut emoji_status = HashMap::new();
    for user in users {
        match &user {
            User::User(u) => {
                let chat_id = chat_id_for_user(u.id);
                titles.insert(chat_id, display_name(&user));
                if let Some(name) = u.username.as_ref().filter(|s| !s.is_empty()) {
                    usernames.insert(u.id, name.clone());
                }
                if let Some(document_id) =
                    emoji_status_document_id(u.emoji_status.as_ref().map(|s| s.as_ref()))
                {
                    emoji_status.insert(chat_id, document_id);
                }
            }
            User::UserEmpty(u) => {
                titles.insert(chat_id_for_user(u.id), format!("User {}", u.id));
            }
            _ => {}
        }
    }
    for chat in chats {
        match &chat {
            TlChat::Chat(c) => {
                titles.insert(chat_id_for_chat(c.id), c.title.clone());
            }
            TlChat::Channel(c) => {
                let chat_id = chat_id_for_channel(c.id);
                titles.insert(chat_id, c.title.clone());
                if let Some(document_id) =
                    emoji_status_document_id(c.emoji_status.as_ref().map(|s| s.as_ref()))
                {
                    emoji_status.insert(chat_id, document_id);
                }
            }
            _ => {}
        }
    }
    (titles, usernames, emoji_status)
}

pub(crate) fn fill_missing_reply_quotes(dtos: &mut [MessageDto]) {
    let texts: HashMap<i32, String> = dtos
        .iter()
        .filter_map(|message| {
            message
                .text
                .as_ref()
                .filter(|text| !text.is_empty())
                .map(|text| (message.id, text.clone()))
        })
        .collect();
    for dto in dtos.iter_mut() {
        if dto
            .reply_quote
            .as_ref()
            .is_some_and(|quote| !quote.is_empty())
        {
            continue;
        }
        if let Some(id) = dto.reply_to_msg_id {
            if let Some(text) = texts.get(&id) {
                dto.reply_quote = Some(text.clone());
            }
        }
    }
}

pub(crate) fn dtos_from_messages(
    response: MessagesMessages,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
) -> Result<Vec<MessageDto>, MtprotoError> {
    Ok(dtos_from_messages_page(response, peers, media_index)?.0)
}

/// Maps `messages.Messages` and returns `next_rate` from `messages.messagesSlice` (else 0).
/// https://core.telegram.org/api/offsets
pub(crate) fn dtos_from_messages_page(
    response: MessagesMessages,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
) -> Result<(Vec<MessageDto>, i32), MtprotoError> {
    let (messages, users, chats, next_rate) = match response {
        MessagesMessages::MessagesMessages(m) => (m.messages, m.users, m.chats, 0),
        MessagesMessages::MessagesMessagesSlice(m) => {
            (m.messages, m.users, m.chats, m.next_rate.unwrap_or(0))
        }
        MessagesMessages::MessagesChannelMessages(m) => (m.messages, m.users, m.chats, 0),
        MessagesMessages::MessagesMessagesNotModified(_) => return Ok((Vec::new(), 0)),
        _ => return Err(MtprotoError::Message("unexpected messages.messages".into())),
    };
    let user_list = vector_boxed_items(&users).cloned().collect::<Vec<_>>();
    let chat_list = vector_boxed_items(&chats).cloned().collect::<Vec<_>>();
    cache_from_users_chats(
        peers,
        media_index,
        user_list.iter().cloned(),
        chat_list.iter().cloned(),
    );
    let (titles, usernames, emoji_status) =
        collect_peer_labels(user_list.into_iter(), chat_list.into_iter());
    let mut dtos: Vec<MessageDto> = vector_boxed_items(&messages)
        .filter_map(|msg| {
            message_to_dto_named(msg, media_index, &titles, &usernames, &emoji_status)
        })
        .collect();
    fill_missing_reply_quotes(&mut dtos);
    Ok((dtos, next_rate))
}

#[derive(serde::Serialize)]
pub(crate) struct EntityJson {
    kind: String,
    offset: i32,
    length: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    url: Option<String>,
}

pub(crate) fn entity_json(kind: &str, offset: i32, length: i32, url: Option<String>) -> EntityJson {
    EntityJson {
        kind: kind.to_string(),
        offset,
        length,
        url,
    }
}

pub(crate) fn entities_to_json(entities: Option<&Vector<Box<MessageEntity>>>) -> Option<String> {
    let items = entities?;
    let mapped: Vec<EntityJson> = vector_boxed_items(items)
        .filter_map(|entity| match entity {
            MessageEntity::MessageEntityBold(e) => {
                Some(entity_json("bold", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityItalic(e) => {
                Some(entity_json("italic", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityUnderline(e) => {
                Some(entity_json("underline", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityStrike(e) => {
                Some(entity_json("strike", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityCode(e) => {
                Some(entity_json("code", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityPre(e) => Some(entity_json(
                "pre",
                e.offset,
                e.length,
                Some(e.language.clone()).filter(|s| !s.is_empty()),
            )),
            MessageEntity::MessageEntitySpoiler(e) => {
                Some(entity_json("spoiler", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityBlockquote(e) => {
                Some(entity_json("blockquote", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityUrl(e) => {
                Some(entity_json("url", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityTextUrl(e) => Some(entity_json(
                "text_url",
                e.offset,
                e.length,
                Some(e.url.clone()),
            )),
            MessageEntity::MessageEntityMention(e) => {
                Some(entity_json("mention", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityMentionName(e) => {
                Some(entity_json("mention", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityHashtag(e) => {
                Some(entity_json("hashtag", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityBotCommand(e) => {
                Some(entity_json("bot_command", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityEmail(e) => {
                Some(entity_json("email", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityPhone(e) => {
                Some(entity_json("phone", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityCashtag(e) => {
                Some(entity_json("cashtag", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityBankCard(e) => {
                Some(entity_json("bank_card", e.offset, e.length, None))
            }
            MessageEntity::MessageEntityCustomEmoji(e) => Some(entity_json(
                "custom_emoji",
                e.offset,
                e.length,
                Some(e.document_id.to_string()),
            )),
            MessageEntity::MessageEntityFormattedDate(e) => {
                Some(entity_json("date", e.offset, e.length, None))
            }
            _ => None,
        })
        .collect();
    if mapped.is_empty() {
        None
    } else {
        serde_json::to_string(&mapped).ok()
    }
}
