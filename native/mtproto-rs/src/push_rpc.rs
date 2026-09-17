//! Device push registration, notify settings, and FCM payload decrypt.
//! https://core.telegram.org/api/push-updates
//! https://core.telegram.org/method/account.registerDevice

use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    AccountGetNotifyExceptionsRequest, AccountGetNotifySettingsRequest,
    AccountRegisterDeviceRequest, AccountResetNotifySettingsRequest,
    AccountSetContactSignUpNotificationRequest, AccountUnregisterDeviceRequest,
    AccountUpdateNotifySettingsRequest, Bool, BoolFalseConstructor, BoolTrueConstructor,
    InputNotifyBroadcastsConstructor, InputNotifyChatsConstructor, InputNotifyPeer,
    InputNotifyPeerConstructor, InputNotifyUsersConstructor, InputPeerNotifySettings,
    InputPeerNotifySettingsConstructor, NotificationSound, NotificationSoundDefaultConstructor,
    NotificationSoundNoneConstructor, NotifyPeer, PeerNotifySettings, True, TrueConstructor,
    Update, Updates, Vector, VectorConstructor,
};
use tellers_mtproto_crypto::{
    aes_ige_decrypt, aes_ige_encrypt, auth_key_id, constant_time_eq, message_aes_key_iv,
    message_key, Direction,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::peers::{self, input_peer_from_cached, peer_chat_id, vector_boxed_items, CachedPeer};
use crate::{MtprotoError, NotifyExceptionDto, NotifySettingsDto};

pub const TOKEN_TYPE_FCM: i32 = 2;
pub const TOKEN_TYPE_SIMPLE: i32 = 4;
pub const TOKEN_TYPE_WEB_PUSH: i32 = 10;

fn tl_bool(value: bool) -> Box<Bool> {
    if value {
        Box::new(Bool::BoolTrue(BoolTrueConstructor {}))
    } else {
        Box::new(Bool::BoolFalse(BoolFalseConstructor {}))
    }
}

fn tl_true() -> Box<True> {
    Box::new(True::True(TrueConstructor {}))
}

fn as_bool(value: &Option<Box<Bool>>) -> bool {
    matches!(value, Some(flag) if matches!(flag.as_ref(), Bool::BoolTrue(_)))
}

fn long_vector(values: Vec<i64>) -> Box<Vector<i64>> {
    Box::new(Vector::Vector(VectorConstructor {
        field_0: values.len() as u32,
        field_1: values,
    }))
}

fn input_notify(
    kind: &str,
    chat_id: i64,
    peers: &HashMap<i64, CachedPeer>,
) -> Result<Box<InputNotifyPeer>, MtprotoError> {
    Ok(Box::new(match kind {
        "users" => InputNotifyPeer::InputNotifyUsers(InputNotifyUsersConstructor {}),
        "chats" => InputNotifyPeer::InputNotifyChats(InputNotifyChatsConstructor {}),
        "broadcasts" => InputNotifyPeer::InputNotifyBroadcasts(InputNotifyBroadcastsConstructor {}),
        "peer" => {
            let cached = peers::require_usable_peer(peers, chat_id)?;
            InputNotifyPeer::InputNotifyPeer(InputNotifyPeerConstructor {
                peer: Box::new(input_peer_from_cached(cached)),
            })
        }
        other => {
            return Err(MtprotoError::Message(format!(
                "notify peer invalid: {other}"
            )));
        }
    }))
}

fn sound_from_name(name: &str) -> Option<Box<NotificationSound>> {
    match name {
        "" | "keep" => None,
        "none" => Some(Box::new(NotificationSound::NotificationSoundNone(
            NotificationSoundNoneConstructor {},
        ))),
        _ => Some(Box::new(NotificationSound::NotificationSoundDefault(
            NotificationSoundDefaultConstructor {},
        ))),
    }
}

fn sound_name(sound: Option<&NotificationSound>) -> String {
    match sound {
        Some(NotificationSound::NotificationSoundNone(_)) => "none".into(),
        Some(NotificationSound::NotificationSoundDefault(_)) => "default".into(),
        Some(_) => "custom".into(),
        None => "default".into(),
    }
}

fn settings_dto(settings: &PeerNotifySettings) -> NotifySettingsDto {
    let PeerNotifySettings::PeerNotifySettings(body) = settings else {
        return NotifySettingsDto::default();
    };
    NotifySettingsDto {
        show_previews: as_bool(&body.show_previews),
        silent: as_bool(&body.silent),
        mute_until: body.mute_until.unwrap_or(0),
        stories_muted: as_bool(&body.stories_muted),
        stories_hide_sender: as_bool(&body.stories_hide_sender),
        sound: sound_name(
            body.android_sound
                .as_deref()
                .or(body.other_sound.as_deref()),
        ),
    }
}

pub(crate) fn exception_from_update(update: &Update) -> Option<NotifyExceptionDto> {
    let Update::UpdateNotifySettings(body) = update else {
        return None;
    };
    let settings = settings_dto(body.notify_settings.as_ref());
    let (peer_kind, chat_id) = match body.peer.as_ref() {
        NotifyPeer::NotifyUsers(_) => ("users", 0_i64),
        NotifyPeer::NotifyChats(_) => ("chats", 0_i64),
        NotifyPeer::NotifyBroadcasts(_) => ("broadcasts", 0_i64),
        NotifyPeer::NotifyPeer(peer) => ("peer", peer_chat_id(peer.peer.as_ref())),
        NotifyPeer::NotifyForumTopic(topic) => ("peer", peer_chat_id(topic.peer.as_ref())),
        _ => return None,
    };
    Some(NotifyExceptionDto {
        peer_kind: peer_kind.into(),
        chat_id,
        show_previews: settings.show_previews,
        silent: settings.silent,
        mute_until: settings.mute_until,
        stories_muted: settings.stories_muted,
        stories_hide_sender: settings.stories_hide_sender,
        sound: settings.sound,
    })
}

fn collect_updates(updates: &Updates) -> Vec<&Update> {
    match updates {
        Updates::Updates(body) => vector_boxed_items(&body.updates).collect(),
        Updates::UpdatesCombined(body) => vector_boxed_items(&body.updates).collect(),
        Updates::UpdateShort(body) => vec![body.update.as_ref()],
        _ => Vec::new(),
    }
}

pub fn register_device(
    snapshot: &mut Snapshot,
    api_id: i32,
    token_type: i32,
    token: String,
    secret: Vec<u8>,
    no_muted: bool,
    app_sandbox: bool,
    other_uids: Vec<i64>,
) -> Result<(), MtprotoError> {
    if token.trim().is_empty() {
        return Err(MtprotoError::Message("TOKEN_EMPTY".into()));
    }
    if !matches!(
        token_type,
        TOKEN_TYPE_FCM | TOKEN_TYPE_SIMPLE | TOKEN_TYPE_WEB_PUSH
    ) {
        return Err(MtprotoError::Message("TOKEN_TYPE_INVALID".into()));
    }
    if token_type == TOKEN_TYPE_FCM && !secret.is_empty() && secret.len() != 256 {
        return Err(MtprotoError::Message(
            "push secret must be 256 bytes".into(),
        ));
    }
    let flags = if no_muted {
        AccountRegisterDeviceRequest::NO_MUTED_FLAG
    } else {
        0
    };
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountRegisterDeviceRequest {
            flags,
            no_muted: no_muted.then(tl_true),
            token_type,
            token,
            app_sandbox: tl_bool(app_sandbox),
            secret,
            other_uids: long_vector(other_uids),
        },
    )?;
    Ok(())
}

pub fn unregister_device(
    snapshot: &mut Snapshot,
    api_id: i32,
    token_type: i32,
    token: String,
    other_uids: Vec<i64>,
) -> Result<(), MtprotoError> {
    if token.trim().is_empty() {
        return Err(MtprotoError::Message("TOKEN_EMPTY".into()));
    }
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountUnregisterDeviceRequest {
            token_type,
            token,
            other_uids: long_vector(other_uids),
        },
    )?;
    Ok(())
}

pub fn get_notify_settings(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    peer_kind: String,
    chat_id: i64,
) -> Result<NotifySettingsDto, MtprotoError> {
    let response: PeerNotifySettings = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountGetNotifySettingsRequest {
            peer: input_notify(&peer_kind, chat_id, peers)?,
        },
    )?;
    Ok(settings_dto(&response))
}

pub fn update_notify_settings(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    peer_kind: String,
    chat_id: i64,
    show_previews: bool,
    silent: bool,
    mute_until: i32,
    stories_muted: bool,
    sound: String,
) -> Result<(), MtprotoError> {
    let mut flags = InputPeerNotifySettingsConstructor::SHOW_PREVIEWS_FLAG
        | InputPeerNotifySettingsConstructor::SILENT_FLAG
        | InputPeerNotifySettingsConstructor::MUTE_UNTIL_FLAG
        | InputPeerNotifySettingsConstructor::STORIES_MUTED_FLAG;
    let sound_value = sound_from_name(&sound);
    if sound_value.is_some() {
        flags |= InputPeerNotifySettingsConstructor::SOUND_FLAG;
    }
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountUpdateNotifySettingsRequest {
            peer: input_notify(&peer_kind, chat_id, peers)?,
            settings: Box::new(InputPeerNotifySettings::InputPeerNotifySettings(
                InputPeerNotifySettingsConstructor {
                    flags,
                    show_previews: Some(tl_bool(show_previews)),
                    silent: Some(tl_bool(silent)),
                    mute_until: Some(mute_until),
                    sound: sound_value,
                    stories_muted: Some(tl_bool(stories_muted)),
                    stories_hide_sender: None,
                    stories_sound: None,
                },
            )),
        },
    )?;
    Ok(())
}

pub fn reset_notify_settings(snapshot: &mut Snapshot, api_id: i32) -> Result<(), MtprotoError> {
    let _: Bool = api_invoke::invoke_api(snapshot, api_id, AccountResetNotifySettingsRequest {})?;
    Ok(())
}

pub fn set_contact_joined_silent(
    snapshot: &mut Snapshot,
    api_id: i32,
    silent: bool,
) -> Result<(), MtprotoError> {
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountSetContactSignUpNotificationRequest {
            silent: tl_bool(silent),
        },
    )?;
    Ok(())
}

pub fn get_notify_exceptions(
    snapshot: &mut Snapshot,
    api_id: i32,
    compare_sound: bool,
) -> Result<Vec<NotifyExceptionDto>, MtprotoError> {
    let mut flags = 0_u32;
    let compare = if compare_sound {
        flags |= AccountGetNotifyExceptionsRequest::COMPARE_SOUND_FLAG;
        Some(tl_true())
    } else {
        None
    };
    let updates: Updates = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountGetNotifyExceptionsRequest {
            flags,
            compare_sound: compare,
            compare_stories: None,
            peer: None,
        },
    )?;
    Ok(collect_updates(&updates)
        .into_iter()
        .filter_map(exception_from_update)
        .collect())
}

fn b64url_decode(input: &str) -> Result<Vec<u8>, MtprotoError> {
    let mut padded = input.replace('-', "+").replace('_', "/");
    while padded.len() % 4 != 0 {
        padded.push('=');
    }
    decode_std_base64(&padded).ok_or_else(|| MtprotoError::Message("push payload invalid".into()))
}

fn b64url_encode(bytes: &[u8]) -> String {
    let mut out = encode_std_base64(bytes);
    out = out.replace('+', "-").replace('/', "_");
    while out.ends_with('=') {
        out.pop();
    }
    out
}

fn decode_std_base64(input: &str) -> Option<Vec<u8>> {
    fn val(c: u8) -> Option<u8> {
        match c {
            b'A'..=b'Z' => Some(c - b'A'),
            b'a'..=b'z' => Some(c - b'a' + 26),
            b'0'..=b'9' => Some(c - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            b'=' => Some(0),
            _ => None,
        }
    }
    let bytes = input.as_bytes();
    if bytes.len() % 4 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(bytes.len() / 4 * 3);
    for chunk in bytes.chunks(4) {
        let a = val(chunk[0])?;
        let b = val(chunk[1])?;
        let c = val(chunk[2])?;
        let d = val(chunk[3])?;
        out.push((a << 2) | (b >> 4));
        if chunk[2] != b'=' {
            out.push((b << 4) | (c >> 2));
        }
        if chunk[3] != b'=' {
            out.push((c << 6) | d);
        }
    }
    Some(out)
}

fn encode_std_base64(bytes: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    let mut i = 0;
    while i < bytes.len() {
        let remaining = bytes.len() - i;
        let b0 = bytes[i];
        let b1 = if remaining > 1 { bytes[i + 1] } else { 0 };
        let b2 = if remaining > 2 { bytes[i + 2] } else { 0 };
        out.push(TABLE[(b0 >> 2) as usize] as char);
        out.push(TABLE[(((b0 & 0x03) << 4) | (b1 >> 4)) as usize] as char);
        if remaining == 1 {
            out.push('=');
            out.push('=');
        } else {
            out.push(TABLE[(((b1 & 0x0f) << 2) | (b2 >> 6)) as usize] as char);
            if remaining == 2 {
                out.push('=');
            } else {
                out.push(TABLE[(b2 & 0x3f) as usize] as char);
            }
        }
        i += 3;
    }
    out
}

/// Decrypt Telegram FCM `p` (base64url MTProto v2) into JSON.
/// Unencrypted JSON and simple-push `version=` wake hints are accepted as-is.
pub fn decrypt_push_payload(secret: Vec<u8>, payload: String) -> Result<String, MtprotoError> {
    let trimmed = payload.trim();
    if trimmed.is_empty() {
        return Err(MtprotoError::Message("TOKEN_EMPTY".into()));
    }
    if trimmed.starts_with('{') {
        return Ok(trimmed.to_string());
    }
    if trimmed.starts_with("version=") {
        return Ok("{\"loc_key\":\"WAKE\"}".into());
    }
    let packet = b64url_decode(trimmed)?;
    if secret.is_empty() {
        if let Ok(text) = std::str::from_utf8(&packet) {
            let text = text.trim();
            if text.starts_with('{') {
                return Ok(text.to_string());
            }
        }
        return Err(MtprotoError::Message("push secret missing".into()));
    }
    if secret.len() != 256 {
        return Err(MtprotoError::Message(
            "push secret must be 256 bytes".into(),
        ));
    }
    if packet.len() < 24 + 16 || (packet.len() - 24) % 16 != 0 {
        return Err(MtprotoError::Message("push payload invalid".into()));
    }
    let key_id = i64::from_le_bytes(packet[0..8].try_into().expect("key id"));
    if key_id != auth_key_id(&secret) {
        return Err(MtprotoError::Message("push auth_key_id mismatch".into()));
    }
    let msg_key: [u8; 16] = packet[8..24].try_into().expect("msg key");
    let (aes_key, aes_iv) = message_aes_key_iv(&secret, &msg_key, Direction::ServerToClient)
        .map_err(|_| MtprotoError::Message("push decrypt failed".into()))?;
    let plaintext = aes_ige_decrypt(&packet[24..], &aes_key, &aes_iv)
        .map_err(|_| MtprotoError::Message("push decrypt failed".into()))?;
    let calculated = message_key(&secret, &plaintext, Direction::ServerToClient)
        .map_err(|_| MtprotoError::Message("push decrypt failed".into()))?;
    if !constant_time_eq(&msg_key, &calculated) {
        return Err(MtprotoError::Message("push msg_key mismatch".into()));
    }
    if plaintext.len() < 4 {
        return Err(MtprotoError::Message("push payload invalid".into()));
    }
    let json_len = u32::from_le_bytes(plaintext[0..4].try_into().expect("len")) as usize;
    if json_len == 0 || 4 + json_len > plaintext.len() {
        return Err(MtprotoError::Message("push payload invalid".into()));
    }
    let json = std::str::from_utf8(&plaintext[4..4 + json_len])
        .map_err(|_| MtprotoError::Message("push payload invalid".into()))?;
    Ok(json.to_string())
}

pub fn encrypt_push_payload_for_test(secret: &[u8], json: &str) -> Result<String, MtprotoError> {
    if secret.len() != 256 {
        return Err(MtprotoError::Message(
            "push secret must be 256 bytes".into(),
        ));
    }
    let mut plaintext = Vec::new();
    plaintext.extend_from_slice(&(json.len() as u32).to_le_bytes());
    plaintext.extend_from_slice(json.as_bytes());
    let mut pad = 16 - (plaintext.len() % 16);
    if pad < 12 {
        pad += 16;
    }
    plaintext.extend(std::iter::repeat(0xAB).take(pad));
    let msg_key = message_key(secret, &plaintext, Direction::ServerToClient)
        .map_err(|_| MtprotoError::Message("push encrypt failed".into()))?;
    let (aes_key, aes_iv) = message_aes_key_iv(secret, &msg_key, Direction::ServerToClient)
        .map_err(|_| MtprotoError::Message("push encrypt failed".into()))?;
    let encrypted = aes_ige_encrypt(&plaintext, &aes_key, &aes_iv)
        .map_err(|_| MtprotoError::Message("push encrypt failed".into()))?;
    let mut packet = Vec::with_capacity(24 + encrypted.len());
    packet.extend_from_slice(&auth_key_id(secret).to_le_bytes());
    packet.extend_from_slice(&msg_key);
    packet.extend_from_slice(&encrypted);
    Ok(b64url_encode(&packet))
}

#[cfg(test)]
#[path = "push_rpc_tests.rs"]
mod tests;
