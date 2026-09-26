//! https://core.telegram.org/mtproto/service_messages
//! https://core.telegram.org/mtproto/service_messages_about_messages

use flate2::read::GzDecoder;
use std::cell::RefCell;
use std::io::Read;
use tellers_mtproto::codec::{Boxed, Decoder, Encoder, Limits, TlDecode};
use tellers_mtproto::transport::{
    BadMsgNotificationConstructor, BadServerSaltConstructor, GzipPackedConstructor,
    MsgContainerConstructor, MsgCopyConstructor, MsgDetailedInfoConstructor,
    MsgNewDetailedInfoConstructor, MsgsAckConstructor, MsgsStateInfoConstructor,
    NewSessionCreatedConstructor, PongConstructor, RpcErrorConstructor, RpcResultConstructor,
};
use tellers_mtproto_session::{Clock, ReceivedMessageResult, Snapshot};

use crate::MtprotoError;

pub(crate) const RPC_RESULT: u32 = RpcResultConstructor::ID;
pub(crate) const GZIP_PACKED: u32 = GzipPackedConstructor::ID;
pub(crate) const NEW_SESSION_CREATED: u32 = NewSessionCreatedConstructor::ID;
pub(crate) const BAD_SERVER_SALT: u32 = BadServerSaltConstructor::ID;
pub(crate) const BAD_MSG_NOTIFICATION: u32 = BadMsgNotificationConstructor::ID;
pub(crate) const MSGS_ACK: u32 = MsgsAckConstructor::ID;
pub(crate) const MSG_CONTAINER: u32 = MsgContainerConstructor::ID;
pub(crate) const MSG_COPY: u32 = MsgCopyConstructor::ID;
pub(crate) const PONG: u32 = PongConstructor::ID;

pub fn encode_boxed_bytes<T: Boxed>(value: &T) -> Result<Vec<u8>, MtprotoError> {
    let mut encoder = Encoder::new();
    value
        .encode_boxed(&mut encoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(encoder.into_bytes())
}

/// Wrapper so nested `!X` query fields write a constructor id.
pub struct BoxedQuery<T>(pub T);
impl<T: Boxed> tellers_mtproto::codec::TlEncode for BoxedQuery<T> {
    fn encode(&self, encoder: &mut Encoder) -> Result<(), tellers_mtproto::codec::Error> {
        self.0.encode_boxed(encoder)
    }
}

pub(crate) const MAX_UNPACKED_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const MAX_WRAPPER_DEPTH: usize = 8;

pub(crate) fn ungzip_if_needed(bytes: &[u8]) -> Result<Vec<u8>, MtprotoError> {
    if bytes.len() < 4 {
        return Ok(bytes.to_vec());
    }
    let ctor = u32::from_le_bytes(bytes[0..4].try_into().unwrap());
    if ctor != GZIP_PACKED {
        return Ok(bytes.to_vec());
    }
    let mut decoder =
        Decoder::new(bytes, Limits::default()).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let _ = decoder
        .read_u32()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let packed: Vec<u8> =
        TlDecode::decode(&mut decoder).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut gz = GzDecoder::new(packed.as_slice()).take(MAX_UNPACKED_BYTES as u64 + 1);
    let mut out = Vec::new();
    gz.read_to_end(&mut out)
        .map_err(|e| MtprotoError::Message(format!("gzip: {e}")))?;
    if out.len() > MAX_UNPACKED_BYTES {
        return Err(MtprotoError::Message("gzip output limit exceeded".into()));
    }
    Ok(out)
}

pub(crate) enum InboundEvent {
    Updates(Vec<u8>),
    RpcResult { req_msg_id: i64, body: Vec<u8> },
    SaltUpdated { body: Vec<u8> },
    RetryableFailure { message_id: i64 },
    BadMessage { bad_msg_id: i64, error_code: i32 },
    Pong { ping_id: i64 },
    AnswerAvailable { answer_msg_id: i64 },
    Ignored,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct NewSessionMetadata {
    pub first_msg_id: i64,
    pub unique_id: i64,
    pub server_salt: i64,
}

thread_local! {
    static LAST_NEW_SESSION: RefCell<Option<NewSessionMetadata>> = const { RefCell::new(None) };
}

pub fn clear_new_session_metadata() {
    LAST_NEW_SESSION.with(|value| *value.borrow_mut() = None);
}

pub fn take_new_session_metadata() -> Option<NewSessionMetadata> {
    LAST_NEW_SESSION.with(|value| value.borrow_mut().take())
}

pub(crate) fn parse_service_or_result(body: &[u8]) -> Result<Vec<InboundEvent>, MtprotoError> {
    parse_service_at_depth(body, 0)
}

/// Validate child messages before committing any replay/ack state. A malformed
/// child invalidates the container, including its otherwise valid siblings.
pub(crate) fn parse_authenticated(
    body: &[u8],
    outer_id: i64,
    snapshot: &mut Snapshot,
    clock: &impl Clock,
) -> Result<Vec<InboundEvent>, MtprotoError> {
    let mut validated = snapshot.clone();
    let events = parse_authenticated_inner(body, outer_id, &mut validated, clock, 0, false)?;
    *snapshot = validated;
    Ok(events)
}

pub(crate) fn parse_authenticated_inner(
    body: &[u8],
    outer_id: i64,
    snapshot: &mut Snapshot,
    clock: &impl Clock,
    depth: usize,
    in_container: bool,
) -> Result<Vec<InboundEvent>, MtprotoError> {
    let invalid = || MtprotoError::Message("invalid MTProto container framing".into());
    if depth > MAX_WRAPPER_DEPTH || body.len() < 4 {
        return Err(invalid());
    }
    let constructor = peek_u32(body, 0).ok_or_else(invalid)?;
    if constructor == GZIP_PACKED {
        return parse_authenticated_inner(
            &ungzip_if_needed(body)?,
            outer_id,
            snapshot,
            clock,
            depth + 1,
            in_container,
        );
    }
    if constructor != MSG_CONTAINER && constructor != MSG_COPY {
        return parse_service_at_depth(body, depth);
    }
    if constructor == MSG_CONTAINER && in_container {
        return Err(invalid());
    }
    let mut decoder = Decoder::new(body, Limits::default()).map_err(|_| invalid())?;
    decoder.read_u32().map_err(|_| invalid())?;
    let count = if constructor == MSG_CONTAINER {
        if peek_u32(body, decoder.offset()) == Some(0x1cb5_c415) {
            decoder.read_u32().map_err(|_| invalid())?;
        }
        let count = i32::decode(&mut decoder).map_err(|_| invalid())?;
        if !(0..=1024).contains(&count) {
            return Err(invalid());
        }
        count
    } else {
        1
    };
    let mut events = Vec::new();
    for _ in 0..count {
        if peek_u32(body, decoder.offset()) == Some(0x5bb8_e511) {
            decoder.read_u32().map_err(|_| invalid())?;
        }
        let id = i64::decode(&mut decoder).map_err(|_| invalid())?;
        let seqno = i32::decode(&mut decoder).map_err(|_| invalid())?;
        let length = i32::decode(&mut decoder).map_err(|_| invalid())?;
        if id >= outer_id || id & 1 == 0 || seqno < 0 || length < 4 || length % 4 != 0 {
            return Err(MtprotoError::Message(
                "invalid MTProto container child metadata".into(),
            ));
        }
        let payload = decoder.read_raw(length as usize).map_err(|_| invalid())?;
        // A freshly authenticated container can carry an old retransmitted
        // message. Its outer time was checked by Engine; children use replay
        // protection rather than rejecting legitimate delayed answers.
        let disposition = snapshot
            .register_received_message(id, seqno & 1 != 0, clock, false)
            .map_err(|_| invalid())?;
        if disposition == ReceivedMessageResult::InvalidTime {
            return Err(invalid());
        }
        if should_process_inbound(disposition) {
            events.extend(parse_authenticated_inner(
                payload,
                id,
                snapshot,
                clock,
                depth + 1,
                in_container || constructor == MSG_CONTAINER,
            )?);
        }
    }
    decoder.finish().map_err(|_| invalid())?;
    Ok(events)
}

pub(crate) fn parse_service_at_depth(
    body: &[u8],
    depth: usize,
) -> Result<Vec<InboundEvent>, MtprotoError> {
    if depth > MAX_WRAPPER_DEPTH {
        return Err(MtprotoError::Message(
            "MTProto wrapper depth exceeded".into(),
        ));
    }
    if body.len() < 4 {
        return Ok(vec![InboundEvent::Ignored]);
    }
    let ctor = u32::from_le_bytes(body[0..4].try_into().unwrap());
    match ctor {
        MSG_CONTAINER => parse_container(body, depth),
        GZIP_PACKED => {
            let inner = ungzip_if_needed(body)?;
            parse_service_at_depth(&inner, depth + 1)
        }
        RPC_RESULT => {
            let mut decoder = Decoder::new(body, Limits::default())
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let _ = decoder
                .read_u32()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let req_msg_id = <i64 as TlDecode>::decode(&mut decoder)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let rest = ungzip_if_needed(&body[decoder.offset()..])?;
            Ok(vec![InboundEvent::RpcResult {
                req_msg_id,
                body: rest,
            }])
        }
        NEW_SESSION_CREATED => Ok(vec![InboundEvent::SaltUpdated {
            body: body.to_vec(),
        }]),
        BAD_SERVER_SALT => {
            let mut decoder = Decoder::new(body, Limits::default())
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let _ = decoder
                .read_u32()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let bad = BadServerSaltConstructor::decode(&mut decoder)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            Ok(vec![
                InboundEvent::SaltUpdated {
                    body: body.to_vec(),
                },
                InboundEvent::RetryableFailure {
                    message_id: bad.bad_msg_id,
                },
            ])
        }
        ctor if is_updates_type(ctor) => Ok(vec![InboundEvent::Updates(body.to_vec())]),
        MSG_COPY => parse_msg_copy(body, depth),
        BAD_MSG_NOTIFICATION => {
            let mut decoder = Decoder::new(body, Limits::default())
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let _ = decoder
                .read_u32()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let bad = BadMsgNotificationConstructor::decode(&mut decoder)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            Ok(vec![InboundEvent::BadMessage {
                bad_msg_id: bad.bad_msg_id,
                error_code: bad.error_code,
            }])
        }
        PONG => {
            let mut decoder = Decoder::new(body, Limits::default())
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let _ = decoder
                .read_u32()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let pong = PongConstructor::decode(&mut decoder)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            Ok(vec![InboundEvent::Pong {
                ping_id: pong.ping_id,
            }])
        }
        MsgDetailedInfoConstructor::ID | MsgNewDetailedInfoConstructor::ID => {
            let invalid = || MtprotoError::Message("invalid MTProto detailed info".into());
            let mut decoder = Decoder::new(body, Limits::default()).map_err(|_| invalid())?;
            decoder.read_u32().map_err(|_| invalid())?;
            let (answer_msg_id, bytes) = if ctor == MsgDetailedInfoConstructor::ID {
                let info =
                    MsgDetailedInfoConstructor::decode(&mut decoder).map_err(|_| invalid())?;
                (info.answer_msg_id, info.bytes)
            } else {
                let info =
                    MsgNewDetailedInfoConstructor::decode(&mut decoder).map_err(|_| invalid())?;
                (info.answer_msg_id, info.bytes)
            };
            decoder.finish().map_err(|_| invalid())?;
            if answer_msg_id & 1 == 0 || bytes < 0 {
                return Err(invalid());
            }
            Ok(vec![InboundEvent::AnswerAvailable { answer_msg_id }])
        }
        MsgsStateInfoConstructor::ID => {
            // A resend request can receive status instead of a forgotten answer.
            // Keep waiting for the RPC under its existing bounded deadline.
            let invalid = || MtprotoError::Message("invalid MTProto message status".into());
            let mut decoder = Decoder::new(body, Limits::default()).map_err(|_| invalid())?;
            decoder.read_u32().map_err(|_| invalid())?;
            MsgsStateInfoConstructor::decode(&mut decoder).map_err(|_| invalid())?;
            decoder.finish().map_err(|_| invalid())?;
            Ok(vec![InboundEvent::Ignored])
        }
        MSGS_ACK => Ok(vec![InboundEvent::Ignored]),
        // The constructor ID is protocol metadata, not message content. Keep it
        // in the error so a new, legitimate service constructor can be added
        // deliberately instead of weakening the unknown-message fail-closed
        // path.
        _ => Err(MtprotoError::Message(format!(
            "unknown MTProto constructor 0x{ctor:08x}; updates recovery required"
        ))),
    }
}

/// Unsolicited `Updates` on the RPC socket. Query lanes use invokeWithoutUpdates;
/// if one still arrives, keep waiting for `rpc_result` instead of treating it as unknown.
pub(crate) fn is_updates_type(ctor: u32) -> bool {
    use tellers_mtproto::latest::api::{
        UpdateShortChatMessageConstructor, UpdateShortConstructor, UpdateShortMessageConstructor,
        UpdateShortSentMessageConstructor, UpdatesCombinedConstructor, UpdatesConstructor,
        UpdatesTooLongConstructor,
    };
    matches!(
        ctor,
        UpdatesConstructor::ID
            | UpdatesCombinedConstructor::ID
            | UpdatesTooLongConstructor::ID
            | UpdateShortConstructor::ID
            | UpdateShortMessageConstructor::ID
            | UpdateShortChatMessageConstructor::ID
            | UpdateShortSentMessageConstructor::ID
    )
}

pub(crate) fn parse_msg_copy(body: &[u8], depth: usize) -> Result<Vec<InboundEvent>, MtprotoError> {
    const MT_MESSAGE: u32 = 0x5bb8_e511;
    let mut decoder =
        Decoder::new(body, Limits::default()).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let _ = decoder
        .read_u32()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    if peek_u32(body, decoder.offset()) == Some(MT_MESSAGE) {
        let _ = decoder
            .read_u32()
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
    }
    let _msg_id = <i64 as TlDecode>::decode(&mut decoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let _seqno = <i32 as TlDecode>::decode(&mut decoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let bytes = <i32 as TlDecode>::decode(&mut decoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))? as usize;
    let payload = decoder
        .read_raw(bytes)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    parse_service_at_depth(payload, depth + 1)
}

pub(crate) fn parse_container(
    body: &[u8],
    depth: usize,
) -> Result<Vec<InboundEvent>, MtprotoError> {
    const VECTOR: u32 = 0x1cb5_c415;
    const MT_MESSAGE: u32 = 0x5bb8_e511;
    let mut out = Vec::new();
    let mut decoder =
        Decoder::new(body, Limits::default()).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let _ = decoder
        .read_u32()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    // Bare `vector<%Message>` is count+messages. Tolerate a boxed vector ctor.
    if let Some(peek) = peek_u32(body, decoder.offset()) {
        if peek == VECTOR {
            let _ = decoder
                .read_u32()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
        }
    }
    let count = <i32 as TlDecode>::decode(&mut decoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))? as usize;
    if count > 1024 {
        return Err(MtprotoError::Message(format!(
            "msg_container count too large: {count}"
        )));
    }
    for _ in 0..count {
        if peek_u32(body, decoder.offset()) == Some(MT_MESSAGE) {
            let _ = decoder
                .read_u32()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
        }
        let _msg_id = <i64 as TlDecode>::decode(&mut decoder)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        let _seqno = <i32 as TlDecode>::decode(&mut decoder)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        let bytes = <i32 as TlDecode>::decode(&mut decoder)
            .map_err(|e| MtprotoError::Message(e.to_string()))? as usize;
        let payload = decoder
            .read_raw(bytes)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        let pad = (4 - (bytes % 4)) % 4;
        if pad > 0 {
            let _ = decoder.read_raw(pad);
        }
        let events = parse_service_at_depth(payload, depth + 1)?;
        out.extend(events);
    }
    Ok(out)
}

pub(crate) fn peek_u32(body: &[u8], offset: usize) -> Option<u32> {
    body.get(offset..offset + 4)?
        .try_into()
        .ok()
        .map(u32::from_le_bytes)
}

pub(crate) fn apply_new_session_salt(
    snapshot: &mut Snapshot,
    body: &[u8],
) -> Result<(), MtprotoError> {
    let mut decoder =
        Decoder::new(body, Limits::default()).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let ctor = decoder
        .read_u32()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    if ctor == NEW_SESSION_CREATED {
        let ns = NewSessionCreatedConstructor::decode(&mut decoder)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        snapshot.server_salt = ns.server_salt;
        LAST_NEW_SESSION.with(|value| {
            *value.borrow_mut() = Some(NewSessionMetadata {
                first_msg_id: ns.first_msg_id,
                unique_id: ns.unique_id,
                server_salt: ns.server_salt,
            });
        });
    } else if ctor == BAD_SERVER_SALT {
        let bad = BadServerSaltConstructor::decode(&mut decoder)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        snapshot.server_salt = bad.new_server_salt;
    }
    Ok(())
}

pub(crate) fn should_process_inbound(disposition: ReceivedMessageResult) -> bool {
    disposition == ReceivedMessageResult::Accepted
}

pub(crate) fn map_rpc_error(bytes: &[u8]) -> Option<MtprotoError> {
    if bytes.len() < 4 {
        return None;
    }
    let ctor = u32::from_le_bytes(bytes[0..4].try_into().unwrap());
    if ctor != RpcErrorConstructor::ID {
        return None;
    }
    let mut decoder = Decoder::new(bytes, Limits::default()).ok()?;
    let _ = decoder.read_u32().ok()?;
    let err = RpcErrorConstructor::decode(&mut decoder).ok()?;
    let rpc = tellers_mtproto_engine::RpcError::new(err.error_code, err.error_message);
    if rpc.message.contains("SESSION_PASSWORD_NEEDED") {
        return Some(MtprotoError::PasswordRequired);
    }
    if rpc.message.contains("PHONE_NUMBER_UNOCCUPIED") || rpc.message.contains("SIGN_UP_REQUIRED") {
        return Some(MtprotoError::RegistrationRequired);
    }
    // Telegram asks the client to restart the user-auth flow (sendCode again).
    if rpc.message.contains("AUTH_RESTART") {
        return Some(MtprotoError::Message("AUTH_RESTART".into()));
    }
    Some(MtprotoError::Message(format!(
        "RPC {}: {}",
        rpc.code, rpc.message
    )))
}
