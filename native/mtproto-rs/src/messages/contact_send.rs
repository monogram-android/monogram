use crate::media::MediaIndex;
use crate::peers::{CachedPeer, input_peer_from_cached, require_usable_peer};
use crate::{HashMap, MtprotoError, OutgoingMessageResult, SendOptions};
use tellers_mtproto::latest::api::*;
use tellers_mtproto_session::Snapshot;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ContactSendSpec {
    pub phone: String,
    pub first_name: String,
    pub last_name: String,
    pub vcard: String,
}

pub(crate) fn validate_contact(
    phone: &str,
    first_name: &str,
    last_name: &str,
) -> Result<(), MtprotoError> {
    let digits = phone.strip_prefix('+').unwrap_or(phone);
    if !(3..=15).contains(&digits.len())
        || !digits.bytes().all(|v| v.is_ascii_digit())
        || first_name.trim().is_empty()
        || first_name.encode_utf16().count() > 256
        || last_name.encode_utf16().count() > 256
    {
        return Err(MtprotoError::Message(
            "contact configuration invalid".into(),
        ));
    }
    Ok(())
}

fn contact_media(spec: &ContactSendSpec) -> Result<InputMedia, MtprotoError> {
    validate_contact(&spec.phone, &spec.first_name, &spec.last_name)?;
    if spec.vcard.len() > 65536 {
        return Err(MtprotoError::Message("contact vcard too large".into()));
    }
    Ok(InputMedia::InputMediaContact(
        InputMediaContactConstructor {
            phone_number: spec.phone.clone(),
            first_name: spec.first_name.clone(),
            last_name: spec.last_name.clone(),
            vcard: spec.vcard.clone(),
        },
    ))
}

pub fn send_contact_with_options(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    spec: &ContactSendSpec,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<OutgoingMessageResult, MtprotoError> {
    options.validate(random_id)?;
    if random_id == 0 || reply_to_msg_id < 0 || top_msg_id < 0 {
        return Err(MtprotoError::Message(
            "contact send identity invalid".into(),
        ));
    }
    let contact = contact_media(spec)?;
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
        media: Box::new(contact),
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
    if message.media_kind.as_deref() != Some("contact") {
        return Err(MtprotoError::Message(
            "contact response missing authoritative contact".into(),
        ));
    }
    *media = staged;
    Ok(result)
}

#[cfg(test)]
#[path = "../../tests/unit/messages_contact_send_tests.rs"]
mod tests;
