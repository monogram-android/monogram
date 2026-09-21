use tellers_mtproto::transport::{
    MsgResendReqConstructor, MsgsAckConstructor, PingRequest, Vector, VectorConstructor,
};
use tellers_mtproto_crypto::fill_random;
use tellers_mtproto_engine::{Engine, OutboundMessage};
use tellers_mtproto_session::{Clock, OsRandom, Snapshot};
use tellers_mtproto_transport::{Connection, Framing, PaddedIntermediate};

use crate::tcp::{self, ObfuscatedTcp};
use crate::MtprotoError;

use super::dc::{rotated_endpoints, rotated_same_ip};
use super::inbound::{encode_boxed_bytes, MAX_UNPACKED_BYTES};
use super::live::LiveTransport;
use super::supervisor::is_waitable_io;
use super::timeout::{
    fail_fast_idle, idle_empty_first_byte, idle_needs_liveness_probe, is_mid_frame,
    recv_wait_deadline, rpc_timeout_message, LAST_INBOUND_CTOR,
};

pub struct SystemClock;
impl Clock for SystemClock {
    fn unix_micros(&self) -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_micros() as i64)
            .unwrap_or(0)
    }
}

pub(crate) fn make_padding(body_len: usize) -> Result<Vec<u8>, MtprotoError> {
    // 32 + body + padding must be % 16 == 0, padding in 12..=1024.
    let mut pad_len = 12;
    while (32 + body_len + pad_len) % 16 != 0 {
        pad_len += 1;
    }
    let mut padding = vec![0_u8; pad_len];
    fill_random(&mut padding).map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(padding)
}

pub(crate) fn send_framed(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    payload: &[u8],
) -> Result<(), MtprotoError> {
    let packet = framing
        .encode(payload)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    conn.send(&packet)
        .map_err(|e| MtprotoError::Message(e.to_string()))
}

pub(crate) fn try_decode_complete(
    framing: &mut PaddedIntermediate,
    input: &mut Vec<u8>,
) -> Result<Option<Vec<u8>>, MtprotoError> {
    match framing.decode(input) {
        Ok(packet) => {
            let consumed = packet.consumed;
            let payload = packet.payload;
            input.drain(..consumed);
            Ok(Some(payload))
        }
        Err(tellers_mtproto_transport::Error::Incomplete { .. }) => Ok(None),
        Err(e) => Err(MtprotoError::Message(e.to_string())),
    }
}

pub(crate) fn recv_framed(
    conn: &mut crate::tcp::ObfuscatedTcp,
    framing: &mut PaddedIntermediate,
    input: &mut Vec<u8>,
    received: &mut usize,
    attempt_deadline: std::time::Instant,
    overall_deadline: std::time::Instant,
    reused: bool,
    idle_wait_ms: u64,
) -> Result<Vec<u8>, MtprotoError> {
    let mut buf = vec![0_u8; 64 * 1024];
    loop {
        // Decode before the deadline check. A blocking recv can return a complete
        // padded-intermediate frame after the attempt clock; timing out first is
        // `recv=2880 needed=4 prefix=488 last_ctor=0x0` (never decrypted).
        match framing.decode(input) {
            Ok(packet) => {
                let consumed = packet.consumed;
                let payload = packet.payload;
                input.drain(..consumed);
                return Ok(payload);
            }
            Err(tellers_mtproto_transport::Error::Incomplete {
                needed: n,
                available: a,
            }) => {
                let needed = n;
                let available = a;
                if std::time::Instant::now()
                    > recv_wait_deadline(attempt_deadline, overall_deadline, needed, available)
                {
                    return Err(MtprotoError::Message(rpc_timeout_message(
                        *received, input, needed, available,
                    )));
                }
                let now = std::time::Instant::now();
                let last_ctor = LAST_INBOUND_CTOR.with(|c| c.get());
                let wait_ms = if fail_fast_idle(reused, *received, needed, available, last_ctor) {
                    500
                } else if idle_empty_first_byte(*received, needed, available)
                    || idle_needs_liveness_probe(*received, needed, available, last_ctor)
                {
                    idle_wait_ms
                } else if is_mid_frame(needed, available) {
                    overall_deadline.saturating_duration_since(now).as_millis() as u64
                } else {
                    attempt_deadline.saturating_duration_since(now).as_millis() as u64
                };
                conn.set_io_timeout_ms(wait_ms.max(50));
                let n = match conn.receive(&mut buf) {
                    Ok(n) => n,
                    Err(e) if is_waitable_io(&e.to_string()) => {
                        let last_ctor = LAST_INBOUND_CTOR.with(|c| c.get());
                        if fail_fast_idle(reused, *received, needed, available, last_ctor)
                            || idle_empty_first_byte(*received, needed, available)
                            || idle_needs_liveness_probe(*received, needed, available, last_ctor)
                        {
                            return Err(MtprotoError::Message(rpc_timeout_message(
                                *received, input, needed, available,
                            )));
                        }
                        continue;
                    }
                    Err(e) => return Err(MtprotoError::Message(e.to_string())),
                };
                if n == 0 {
                    return Err(MtprotoError::Message(format!(
                        "connection closed recv={received}"
                    )));
                }
                *received = received.saturating_add(n);
                input.extend_from_slice(&buf[..n]);
            }
            Err(e) => return Err(MtprotoError::Message(e.to_string())),
        }
    }
}

pub(crate) fn open_transport_skip(
    snapshot: &Snapshot,
    skip: usize,
    connect_secs: u64,
) -> Result<(ObfuscatedTcp, PaddedIntermediate, &'static str), MtprotoError> {
    let addrs = if snapshot.auth_key.as_ref().map(|k| k.len()) == Some(256) {
        rotated_same_ip(snapshot.dc_id, skip)
    } else {
        rotated_endpoints(snapshot.dc_id, skip)
    };
    let addr = addrs
        .first()
        .copied()
        .ok_or_else(|| MtprotoError::Message("no DC endpoints".into()))?;
    let secret = crate::dns_txt::secret_for(snapshot.dc_id, addr);
    let conn = if let Some(secret) = secret {
        tcp::connect_obfuscated_timeout_obf(
            addr,
            connect_secs,
            Some(snapshot.dc_id as i16),
            Some(&secret),
        )
    } else {
        tcp::connect_obfuscated_timeout(addr, connect_secs)
    }
    .map_err(|e| MtprotoError::Message(format!("{e} via {addr}")))?;
    Ok((conn, PaddedIntermediate::default(), addr))
}

pub(crate) fn open_live(
    dc_id: i32,
    snapshot: &Snapshot,
    timeout_secs: u64,
    skip: usize,
) -> Result<LiveTransport, MtprotoError> {
    let (mut conn, framing, endpoint) = open_transport_skip(snapshot, skip, timeout_secs)?;
    conn.set_io_timeout(timeout_secs);
    Ok(LiveTransport {
        ping_sent: None,
        updates: Vec::new(),
        updates_bytes: 0,
        dc_id,
        endpoint: endpoint.to_owned(),
        conn,
        framing,
        input: Vec::new(),
        last_io: std::time::Instant::now(),
    })
}

pub(crate) fn queue_update(
    transport: &mut LiveTransport,
    body: Vec<u8>,
) -> Result<(), MtprotoError> {
    if transport.updates.len() >= 256
        || transport.updates_bytes.saturating_add(body.len()) > MAX_UNPACKED_BYTES
    {
        return Err(MtprotoError::Message(
            "updates queue overflow; recovery required".into(),
        ));
    }
    transport.updates_bytes += body.len();
    transport.updates.push(body);
    Ok(())
}

/// Read a subscribed socket without issuing another getDifference. It does not
/// use RPC's fail-fast reused-socket policy: a quiet subscribed socket is
/// expected, so it can wait for a bounded idle window before yielding. The

pub(crate) fn open_live_addr(
    dc_id: i32,
    addr: &str,
    secret: Option<&[u8]>,
    timeout_secs: u64,
) -> Result<LiveTransport, MtprotoError> {
    let mut conn =
        tcp::connect_obfuscated_timeout_obf(addr, timeout_secs, Some(dc_id as i16), secret)
            .map_err(|e| MtprotoError::Message(format!("{e} via {addr}")))?;
    conn.set_io_timeout(timeout_secs);
    Ok(LiveTransport {
        ping_sent: None,
        updates: Vec::new(),
        updates_bytes: 0,
        dc_id,
        endpoint: addr.to_owned(),
        conn,
        framing: PaddedIntermediate::default(),
        input: Vec::new(),
        last_io: std::time::Instant::now(),
    })
}

/// Content-unrelated ping. Does not start the server disconnect timer.
/// `ping_delay_disconnect` is omitted: without a background pinger it would
/// close a parked TCP after disconnect_delay (docs: 75s if pinging every 60s).
/// https://core.telegram.org/mtproto/service_messages#ping-messages-ping-pong
/// https://core.telegram.org/mtproto/service_messages_about_messages
/// A status notification does not deliver its answer. Never acknowledge unseen data.
pub(crate) fn detailed_answer_request(
    snapshot: &mut Snapshot,
    answer_msg_id: i64,
) -> Result<Option<Vec<u8>>, MtprotoError> {
    if snapshot.received_message_ids.contains_key(&answer_msg_id) {
        snapshot
            .acknowledge(answer_msg_id)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        return Ok(None);
    }
    let request = MsgResendReqConstructor {
        msg_ids: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![answer_msg_id],
        })),
    };
    encode_boxed_bytes(&request).map(Some)
}

pub(crate) fn recover_detailed_answer<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    transport: &mut LiveTransport,
    answer_msg_id: i64,
    clock: &SystemClock,
) -> Result<(), MtprotoError> {
    let Some(body) = detailed_answer_request(&mut engine.session, answer_msg_id)? else {
        return Ok(());
    };
    let message_id = engine
        .session
        .next_message_id(clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let sequence = engine
        .session
        .next_sequence(false)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let outbound = OutboundMessage {
        message_id,
        sequence,
        body,
    };
    let padding = make_padding(outbound.body.len())?;
    let sealed = engine
        .seal_outbound(&outbound, &padding)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    send_framed(&mut transport.conn, &mut transport.framing, &sealed)?;
    transport.last_io = std::time::Instant::now();
    Ok(())
}

pub(crate) fn send_ping<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    clock: &SystemClock,
) -> Result<i64, MtprotoError> {
    let mut raw = [0_u8; 8];
    fill_random(&mut raw).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let ping_id = i64::from_le_bytes(raw);
    let ping = PingRequest { ping_id };
    let body = encode_boxed_bytes(&ping)?;
    let message_id = engine
        .session
        .next_message_id(clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let sequence = engine
        .session
        .next_sequence(false)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let outbound = OutboundMessage {
        message_id,
        sequence,
        body,
    };
    let padding = make_padding(outbound.body.len())?;
    let sealed = engine
        .seal_outbound(&outbound, &padding)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    send_framed(conn, framing, &sealed)?;
    Ok(ping_id)
}

pub(crate) fn flush_acks<P: tellers_mtproto_engine::RetryPolicy>(
    engine: &mut Engine<P>,
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    clock: &SystemClock,
) -> Result<(), MtprotoError> {
    let ids = engine.session.take_acknowledgements(64);
    if ids.is_empty() {
        return Ok(());
    }
    let count = ids.len() as u32;
    let ack = MsgsAckConstructor {
        msg_ids: Box::new(Vector::Vector(VectorConstructor {
            field_0: count,
            field_1: ids,
        })),
    };
    let body = encode_boxed_bytes(&ack)?;
    let message_id = engine
        .session
        .next_message_id(clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let sequence = engine
        .session
        .next_sequence(false)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let outbound = OutboundMessage {
        message_id,
        sequence,
        body,
    };
    let padding = make_padding(outbound.body.len())?;
    let sealed = engine
        .seal_outbound(&outbound, &padding)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    send_framed(conn, framing, &sealed)
}

pub(crate) fn bad_msg_should_reconnect(error_code: i32) -> bool {
    matches!(error_code, 17 | 18 | 19 | 32 | 33 | 64)
}

pub(crate) fn recreate_session_after_bad_message(
    snapshot: &mut Snapshot,
) -> Result<(), MtprotoError> {
    let fresh = Snapshot::new(snapshot.dc_id, &mut OsRandom)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    snapshot.session_id = fresh.session_id;
    snapshot.last_message_id = 0;
    snapshot.content_sequence = 0;
    snapshot.pending_acknowledgements.clear();
    snapshot.received_message_ids.clear();
    snapshot.reconnect = Default::default();
    Ok(())
}

pub(crate) fn repair_clock_from_server_msg_id(
    snapshot: &mut Snapshot,
    server_msg_id: i64,
    clock: &SystemClock,
) {
    snapshot.time_offset_micros = snapshot
        .time_offset_micros
        .saturating_add(clock_delta_micros(
            server_msg_id,
            clock.unix_micros(),
            snapshot.time_offset_micros,
        ));
}

pub(crate) fn clock_delta_micros(
    server_msg_id: i64,
    local_micros: i64,
    current_offset: i64,
) -> i64 {
    let server_seconds = server_msg_id >> 32;
    let local_seconds = local_micros
        .saturating_add(current_offset)
        .div_euclid(1_000_000);
    server_seconds.saturating_sub(local_seconds) * 1_000_000
}
