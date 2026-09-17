//! https://core.telegram.org/method/messages.getMessageReadParticipants
//! https://core.telegram.org/method/messages.getOutboxReadDate
//! https://core.telegram.org/api/config

use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    HelpAppConfig, HelpGetAppConfigRequest, JsonObjectValue, JsonValue,
    MessagesGetMessageReadParticipantsRequest, MessagesGetOutboxReadDateRequest, OutboxReadDate,
    ReadParticipantDate, Vector,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::peers::{self, input_peer_from_cached, CachedPeer};
use crate::MtprotoError;

pub const DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD: i32 = 100;
pub const DEFAULT_CHAT_READ_MARK_EXPIRE_PERIOD: i32 = 604_800;
pub const DEFAULT_PM_READ_DATE_EXPIRE_PERIOD: i32 = 604_800;

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReadParticipantDto {
    pub peer_id: i64,
    pub date: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReadParticipantsDto {
    pub participants: Vec<ReadParticipantDto>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct OutboxReadDto {
    pub date: i32,
    pub error: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReadReceiptConfigDto {
    pub chat_read_mark_size_threshold: i32,
    pub chat_read_mark_expire_period: i32,
    pub pm_read_date_expire_period: i32,
    pub from_server: bool,
}

fn known_error(err: &MtprotoError) -> Option<String> {
    let MtprotoError::Message(text) = err else {
        return None;
    };
    const KNOWN: [&str; 8] = [
        "CHAT_TOO_BIG",
        "MSG_TOO_OLD",
        "MSG_ID_INVALID",
        "PEER_ID_INVALID",
        "MESSAGE_TOO_OLD",
        "MESSAGE_NOT_READ_YET",
        "MESSAGE_ID_INVALID",
        "PRIVACY_RESTRICTED",
    ];
    KNOWN
        .iter()
        .find(|name| text.contains(**name))
        .map(|name| (*name).to_string())
}

pub fn get_message_read_participants(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers_map: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    msg_id: i32,
) -> Result<ReadParticipantsDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers_map, chat_id)?;
    let request = MessagesGetMessageReadParticipantsRequest {
        peer: Box::new(input_peer_from_cached(cached)),
        msg_id,
    };
    let response: Vector<Box<ReadParticipantDate>> =
        match api_invoke::invoke_api(snapshot, api_id, request) {
            Ok(value) => value,
            Err(err) => {
                return match known_error(&err) {
                    Some(name) => Ok(ReadParticipantsDto {
                        participants: Vec::new(),
                        error: Some(name),
                    }),
                    None => Err(err),
                };
            }
        };
    let mut participants = Vec::new();
    for entry in crate::peers::vector_boxed_items(&response) {
        if let ReadParticipantDate::ReadParticipantDate(value) = entry {
            participants.push(ReadParticipantDto {
                peer_id: value.user_id,
                date: value.date,
            });
        }
    }
    Ok(ReadParticipantsDto {
        participants,
        error: None,
    })
}

pub fn get_outbox_read_date(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers_map: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    msg_id: i32,
) -> Result<OutboxReadDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers_map, chat_id)?;
    let request = MessagesGetOutboxReadDateRequest {
        peer: Box::new(input_peer_from_cached(cached)),
        msg_id,
    };
    let response: OutboxReadDate = match api_invoke::invoke_api(snapshot, api_id, request) {
        Ok(value) => value,
        Err(err) => {
            return match known_error(&err) {
                Some(name) => Ok(OutboxReadDto {
                    date: -1,
                    error: Some(name),
                }),
                None => Err(err),
            };
        }
    };
    let OutboxReadDate::OutboxReadDate(value) = response;
    Ok(OutboxReadDto {
        date: value.date,
        error: None,
    })
}

pub fn fetch_read_receipt_config(
    snapshot: &mut Snapshot,
    api_id: i32,
) -> Result<ReadReceiptConfigDto, MtprotoError> {
    let response: HelpAppConfig =
        api_invoke::invoke_api(snapshot, api_id, HelpGetAppConfigRequest { hash: 0 })?;
    let HelpAppConfig::HelpAppConfig(config) = response else {
        return Ok(default_config(false));
    };
    let mut found = ReadReceiptConfigDto {
        chat_read_mark_size_threshold: DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD,
        chat_read_mark_expire_period: DEFAULT_CHAT_READ_MARK_EXPIRE_PERIOD,
        pm_read_date_expire_period: DEFAULT_PM_READ_DATE_EXPIRE_PERIOD,
        from_server: false,
    };
    collect_read_receipt_config(&config.config, &mut found);
    Ok(found)
}

fn default_config(from_server: bool) -> ReadReceiptConfigDto {
    ReadReceiptConfigDto {
        chat_read_mark_size_threshold: DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD,
        chat_read_mark_expire_period: DEFAULT_CHAT_READ_MARK_EXPIRE_PERIOD,
        pm_read_date_expire_period: DEFAULT_PM_READ_DATE_EXPIRE_PERIOD,
        from_server,
    }
}

fn collect_read_receipt_config(value: &JsonValue, out: &mut ReadReceiptConfigDto) {
    let JsonValue::JsonObject(object) = value else {
        return;
    };
    for entry in crate::peers::vector_boxed_items(&object.value) {
        let JsonObjectValue::JsonObjectValue(entry) = entry else {
            continue;
        };
        let number = match &*entry.value {
            JsonValue::JsonNumber(number) => number.value,
            _ => continue,
        };
        match entry.key.as_str() {
            "chat_read_mark_size_threshold" => {
                out.chat_read_mark_size_threshold = number as i32;
                out.from_server = true;
            }
            "chat_read_mark_expire_period" => {
                out.chat_read_mark_expire_period = number as i32;
                out.from_server = true;
            }
            "pm_read_date_expire_period" => {
                out.pm_read_date_expire_period = number as i32;
                out.from_server = true;
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{
        JsonNumberConstructor, JsonObjectConstructor, JsonObjectValueConstructor,
        JsonStringConstructor, VectorConstructor,
    };

    fn object(entries: Vec<(&str, JsonValue)>) -> JsonValue {
        JsonValue::JsonObject(JsonObjectConstructor {
            value: Box::new(Vector::Vector(VectorConstructor {
                field_0: 0x1cb5_c415,
                field_1: entries
                    .into_iter()
                    .map(|(key, value)| {
                        Box::new(JsonObjectValue::JsonObjectValue(
                            JsonObjectValueConstructor {
                                key: key.to_string(),
                                value: Box::new(value),
                            },
                        ))
                    })
                    .collect(),
            })),
        })
    }

    fn number(value: f64) -> JsonValue {
        JsonValue::JsonNumber(JsonNumberConstructor { value })
    }

    fn text(value: &str) -> JsonValue {
        JsonValue::JsonString(JsonStringConstructor {
            value: value.to_string(),
        })
    }

    #[test]
    fn reads_thresholds_from_config_object() {
        let mut out = default_config(false);
        collect_read_receipt_config(
            &object(vec![
                ("chat_read_mark_size_threshold", number(50.0)),
                ("chat_read_mark_expire_period", number(3600.0)),
                ("pm_read_date_expire_period", text("ignored")),
            ]),
            &mut out,
        );
        assert_eq!(out.chat_read_mark_size_threshold, 50);
        assert_eq!(out.chat_read_mark_expire_period, 3600);
        assert_eq!(
            out.pm_read_date_expire_period,
            DEFAULT_PM_READ_DATE_EXPIRE_PERIOD
        );
        assert!(out.from_server);
    }

    #[test]
    fn ignores_non_object_config() {
        let mut out = default_config(false);
        collect_read_receipt_config(&number(1.0), &mut out);
        assert!(!out.from_server);
        assert_eq!(
            out.chat_read_mark_size_threshold,
            DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD
        );
    }

    #[test]
    fn known_errors_are_reported_not_raised() {
        assert_eq!(
            known_error(&MtprotoError::Message("RPC 400: MSG_TOO_OLD".into())).as_deref(),
            Some("MSG_TOO_OLD")
        );
        assert_eq!(
            known_error(&MtprotoError::Message(
                "RPC 403: USER_PRIVACY_RESTRICTED".into()
            ))
            .as_deref(),
            Some("PRIVACY_RESTRICTED")
        );
        assert!(known_error(&MtprotoError::Message("connection reset".into())).is_none());
    }
}
