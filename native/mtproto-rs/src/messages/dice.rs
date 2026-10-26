use crate::media::MediaIndex;
use crate::peers::{CachedPeer, input_peer_from_cached, require_usable_peer};
use crate::{HashMap, MtprotoError, OutgoingMessageResult, SendOptions};
use tellers_mtproto::latest::api::*;
use tellers_mtproto_session::Snapshot;

fn dice_request(
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    emoticon: &str,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<MessagesSendMediaRequest, MtprotoError> {
    if random_id == 0 || reply_to_msg_id < 0 || top_msg_id < 0 {
        return Err(MtprotoError::Message("dice send identity invalid".into()));
    }
    if !matches!(emoticon, "🎲" | "🎯" | "🏀" | "⚽" | "🎳" | "🎰") {
        return Err(MtprotoError::Message("dice emoticon invalid".into()));
    }
    options.validate(random_id)?;
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
        media: Box::new(InputMedia::InputMediaDice(InputMediaDiceConstructor {
            emoticon: emoticon.to_owned(),
        })),
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
    Ok(request)
}

pub fn send_dice_with_options(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    emoticon: &str,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<OutgoingMessageResult, MtprotoError> {
    let request = dice_request(
        peers,
        chat_id,
        emoticon,
        reply_to_msg_id,
        top_msg_id,
        random_id,
        options,
    )?;
    let updates: Updates = crate::api_invoke::invoke_api(snapshot, api_id, request)?;
    let mut staged = media.clone();
    let result = super::send_result(updates, chat_id, "", random_id, peers, &mut staged)?;
    let (message, scheduled) = match &result {
        OutgoingMessageResult::Sent { message } => (message, false),
        OutgoingMessageResult::Scheduled { message } => (&message.message, true),
    };
    if !valid_dice_payload(
        message.media_kind.as_deref(),
        message.file_name.as_deref(),
        emoticon,
        scheduled,
    ) {
        return Err(MtprotoError::Message(
            "dice response lacks authoritative result".into(),
        ));
    }
    *media = staged;
    Ok(result)
}

fn valid_dice_payload(
    kind: Option<&str>,
    payload: Option<&str>,
    emoticon: &str,
    scheduled: bool,
) -> bool {
    let Some(payload) =
        payload.and_then(|value| serde_json::from_str::<serde_json::Value>(value).ok())
    else {
        return false;
    };
    kind == Some("dice")
        && payload["e"].as_str() == Some(emoticon)
        && payload["v"]
            .as_i64()
            .is_some_and(|value| value > 0 || scheduled && value == 0)
}

#[cfg(test)]
#[path = "../../tests/unit/messages_dice_tests.rs"]
mod tests;
