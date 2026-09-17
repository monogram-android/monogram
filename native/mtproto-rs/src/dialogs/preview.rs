use std::collections::HashMap;

use tellers_mtproto::latest::api::{Message, Peer, RichMessage};

use super::peers::ChatMeta;
use crate::media;
use crate::peers::{peer_chat_id, vector_boxed_items};
use crate::service_messages::service_action_text;

pub(crate) fn compose_chat_list_preview(
    is_group: bool,
    outgoing: bool,
    sender_name: Option<&str>,
    media_label: Option<&str>,
    text: &str,
) -> Option<String> {
    let text = text.trim();
    let body = match (
        media_label.filter(|s| !s.is_empty()),
        (!text.is_empty()).then_some(text),
    ) {
        (Some(kind), Some(t)) => format!("{kind}, {t}"),
        (Some(kind), None) => kind.to_string(),
        (None, Some(t)) => t.to_string(),
        (None, None) => String::new(),
    };
    let preview = if is_group {
        let sender = if outgoing {
            Some("You")
        } else {
            sender_name.filter(|s| !s.is_empty())
        };
        match (sender, body.is_empty()) {
            (Some(name), false) => format!("{name}: {body}"),
            (Some(name), true) => format!("{name}:"),
            (None, false) => body,
            (None, true) => String::new(),
        }
    } else {
        body
    };
    if preview.is_empty() {
        None
    } else {
        Some(preview)
    }
}

pub(crate) fn chat_list_media_word(kind: &str) -> Option<&'static str> {
    match kind {
        "photo" => Some("Photo"),
        "video" => Some("Video"),
        "gif" => Some("GIF"),
        "sticker" | "sticker_animated" | "sticker_video" => Some("Sticker"),
        "document" => Some("Document"),
        "audio" => Some("Audio"),
        "voice" => Some("Voice message"),
        "todo" => Some("Checklist"),
        "webpage" => Some("Link"),
        "poll" => Some("Poll"),
        "geo" => Some("Location"),
        "venue" => Some("Venue"),
        "contact" => Some("Contact"),
        "dice" => Some("Dice"),
        _ => None,
    }
}

/// Sticker and animation names Telegram generates; they never carry user text.
pub(crate) fn is_sticker_file_name(name: &str) -> bool {
    let lower = name.trim().to_ascii_lowercase();
    lower.ends_with(".webp") || lower.ends_with(".webm") || lower.ends_with(".tgs")
}

pub(crate) fn preview_hides_file_name(kind: Option<&str>, file_name: &str) -> bool {
    if is_sticker_file_name(file_name) {
        return true;
    }
    matches!(
        kind,
        Some(
            "photo"
                | "video"
                | "gif"
                | "sticker"
                | "sticker_animated"
                | "sticker_video"
                | "voice"
                | "webpage"
                | "todo"
                | "poll"
                | "geo"
                | "venue"
                | "contact"
                | "dice"
        )
    )
}

pub(crate) fn dialog_list_caption(
    m: &tellers_mtproto::latest::api::MessageConstructor,
    indexed: &media::IndexedMessageMedia,
) -> String {
    let plain = m.message.trim();
    if !plain.is_empty() {
        return m.message.clone();
    }
    if let Some(RichMessage::RichMessage(body)) = m.rich_message.as_ref().map(|rich| rich.as_ref())
    {
        let photos: Vec<_> = vector_boxed_items(&body.photos).cloned().collect();
        let formatted =
            media::page_blocks_formatted_media(vector_boxed_items(&body.blocks), &photos);
        if !formatted.text.is_empty() {
            return formatted.text;
        }
    }
    indexed
        .file_name
        .clone()
        .filter(|name| !name.is_empty())
        .filter(|name| !preview_hides_file_name(indexed.kind.as_deref(), name))
        .or_else(|| {
            m.media
                .as_ref()
                .and_then(|media| media::media_fallback_text(media))
        })
        .unwrap_or_default()
}

pub(crate) fn message_preview(
    msg: &Message,
    user_names: &HashMap<i64, String>,
    chat_meta: &HashMap<i64, ChatMeta>,
    indexed: &media::IndexedMessageMedia,
) -> Option<(i32, i64, Option<String>, bool)> {
    match msg {
        Message::Message(m) => {
            let is_group = match &*m.peer_id {
                Peer::PeerChat(_) => true,
                Peer::PeerChannel(c) => chat_meta
                    .get(&c.channel_id)
                    .map(|meta| meta.is_group)
                    .unwrap_or(true),
                _ => false,
            };
            let outgoing = m.out.is_some();
            let sender = m
                .from_id
                .as_ref()
                .and_then(|p| {
                    let id = peer_chat_id(p);
                    user_names.get(&id).or_else(|| match p.as_ref() {
                        Peer::PeerUser(u) => user_names.get(&u.user_id),
                        _ => None,
                    })
                })
                .map(String::as_str);
            let media = indexed.kind.as_deref().and_then(chat_list_media_word);
            let caption = dialog_list_caption(m, indexed);
            Some((
                m.id,
                i64::from(m.date),
                compose_chat_list_preview(is_group, outgoing, sender, media, &caption),
                outgoing,
            ))
        }
        Message::MessageService(m) => {
            let actor = m
                .from_id
                .as_ref()
                .and_then(|peer| user_names.get(&peer_chat_id(peer)).map(String::as_str));
            Some((
                m.id,
                i64::from(m.date),
                Some(service_action_text(
                    m.action.as_ref(),
                    m.from_id.as_ref().map(|peer| peer_chat_id(peer)),
                    actor.unwrap_or(""),
                    user_names,
                )),
                m.out.is_some(),
            ))
        }
        _ => None,
    }
}
