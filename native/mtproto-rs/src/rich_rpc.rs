use tellers_mtproto::codec::{Boxed, Decoder, Encoder, Limits, TlDecode, TlEncode};
use tellers_mtproto::latest::api::{InputPeer, InputPeerSelfConstructor, PageBlock, Vector};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::media;
use crate::peers::{self, vector_boxed_items, CachedPeer};
use crate::rpc;
use crate::MtprotoError;

/// `messages.getRichMessage#501569cf peer:InputPeer id:int = messages.Messages`
const GET_RICH_MESSAGE_ID: u32 = 0x5015_69cf;
/// `richMessage#baf39d8b`
const RICH_MESSAGE_ID: u32 = 0xbaf3_9d8b;
const RICH_LAYER: i32 = 229;

struct MessagesGetRichMessageRequest {
    peer: Box<InputPeer>,
    id: i32,
}

impl TlEncode for MessagesGetRichMessageRequest {
    fn encode(&self, encoder: &mut Encoder) -> Result<(), tellers_mtproto::codec::Error> {
        TlEncode::encode(&self.peer, encoder)?;
        TlEncode::encode(&self.id, encoder)?;
        Ok(())
    }
}

impl Boxed for MessagesGetRichMessageRequest {
    const CONSTRUCTOR_ID: u32 = GET_RICH_MESSAGE_ID;
}

pub fn fill_unsupported_rich_text(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &std::collections::HashMap<i64, CachedPeer>,
    user_id: Option<i64>,
    dtos: &mut [crate::MessageDto],
) {
    let mut remaining = 3_u8;
    for dto in dtos.iter_mut() {
        if remaining == 0 {
            break;
        }
        if dto.media_kind.as_deref() != Some("unsupported") {
            continue;
        }
        if dto.text.as_ref().is_some_and(|t| !t.is_empty()) {
            continue;
        }
        remaining -= 1;
        match fetch_rich_plain(snapshot, api_id, peers, user_id, dto.chat_id, dto.id) {
            Ok(Some(text)) => {
                dto.text = Some(text);
                dto.media_kind = None;
            }
            Ok(None) | Err(_) => {}
        }
    }
}

fn fetch_rich_plain(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &std::collections::HashMap<i64, CachedPeer>,
    user_id: Option<i64>,
    chat_id: i64,
    message_id: i32,
) -> Result<Option<String>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let peer = if user_id == Some(chat_id) {
        InputPeer::InputPeerSelf(InputPeerSelfConstructor {})
    } else {
        peers::input_peer_from_cached(cached)
    };
    let request = MessagesGetRichMessageRequest {
        peer: Box::new(peer),
        id: message_id,
    };
    let wrapped = api_invoke::wrap_init_connection_at_layer(api_id, RICH_LAYER, request);
    let body = rpc::encode_boxed_bytes(&wrapped)?;
    let raw = match rpc::invoke_raw(snapshot, body) {
        Ok(bytes) => bytes,
        Err(err) => {
            eprintln!("getRichMessage id={message_id} err={err}");
            return Err(err);
        }
    };
    let plain = extract_rich_plain(&raw);
    eprintln!(
        "getRichMessage id={message_id} bytes={} rich={}",
        raw.len(),
        plain.as_ref().map(|t| t.len()).unwrap_or(0),
    );
    Ok(plain)
}

fn extract_rich_plain(bytes: &[u8]) -> Option<String> {
    let unpacked = rpc::ungzip_if_needed(bytes).ok()?;
    let marker = RICH_MESSAGE_ID.to_le_bytes();
    let pos = unpacked.windows(4).position(|w| w == marker)?;
    let mut decoder = Decoder::new(&unpacked[pos..], Limits::default()).ok()?;
    let _ctor = decoder.read_u32().ok()?;
    let _flags: u32 = TlDecode::decode(&mut decoder).ok()?;
    let blocks: Vector<Box<PageBlock>> = TlDecode::decode(&mut decoder).ok()?;
    let text = media::page_blocks_plain(vector_boxed_items(&blocks));
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}
