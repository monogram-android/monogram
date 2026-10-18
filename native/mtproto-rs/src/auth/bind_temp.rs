//! Bind a temporary auth key so an extra home-DC session does not reuse the
//! permanent key. https://core.telegram.org/api/pfs
//! https://core.telegram.org/method/auth.bindTempAuthKey
//! https://core.telegram.org/mtproto/description_v1

use tellers_mtproto::codec::{Boxed, Decoder, Encoder, Limits, TlDecode};
use tellers_mtproto::transport::BindAuthKeyInnerConstructor;
use tellers_mtproto_crypto::{aes_ige_decrypt, aes_ige_encrypt, auth_key_id, fill_random, sha1};
use tellers_mtproto_session::{Clock, OsRandom, Snapshot};
use tellers_mtproto_transport::{Connection, Framing, PaddedIntermediate};

use crate::MtprotoError;
use crate::auth::auth_key::create_temp_auth_key;

const TEMP_EXPIRES_IN: i32 = 24 * 60 * 60;
const BOOL_TRUE: u32 = 0x9972_75b5;
const BOOL_FALSE: u32 = 0xbc79_9737;
const RPC_RESULT: u32 = 0xf35c_6d01;
const RPC_ERROR: u32 = 0x2144_ca19;
const MSG_CONTAINER: u32 = 0x73f1_f8dc;

pub(crate) fn unix_now() -> i32 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| i32::try_from(d.as_secs()).unwrap_or(i32::MAX))
        .unwrap_or(0)
}

/// MTProto v1 `encrypted_message` for `auth.bindTempAuthKey`.
/// `msg_key` covers the envelope before alignment padding.
pub(crate) fn encrypt_bind_message(
    perm_key: &[u8],
    msg_id: i64,
    inner: &BindAuthKeyInnerConstructor,
    random_prefix: &[u8; 16],
    padding: &[u8],
) -> Result<Vec<u8>, MtprotoError> {
    if perm_key.len() != 256 {
        return Err(MtprotoError::Message("permanent key required".into()));
    }
    let body = encode_inner(inner)?;
    if body.len() != 40 {
        return Err(MtprotoError::Message("bind inner length".into()));
    }
    let mut plain = Vec::with_capacity(72 + padding.len());
    plain.extend_from_slice(random_prefix);
    plain.extend_from_slice(&msg_id.to_le_bytes());
    plain.extend_from_slice(&0_i32.to_le_bytes());
    plain.extend_from_slice(&40_i32.to_le_bytes());
    plain.extend_from_slice(&body);
    let msg_key = v1_msg_key(&plain);
    if padding.len() > 15 || (plain.len() + padding.len()) % 16 != 0 {
        return Err(MtprotoError::Message("bind padding".into()));
    }
    plain.extend_from_slice(padding);
    let (aes_key, aes_iv) = v1_aes(&msg_key, perm_key);
    let encrypted = aes_ige_encrypt(&plain, &aes_key, &aes_iv)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut out = Vec::with_capacity(24 + encrypted.len());
    out.extend_from_slice(&auth_key_id(perm_key).to_le_bytes());
    out.extend_from_slice(&msg_key);
    out.extend_from_slice(&encrypted);
    Ok(out)
}

fn v1_msg_key(unpadded: &[u8]) -> [u8; 16] {
    sha1(unpadded)[4..20].try_into().expect("sha1 slice")
}

fn v1_aes(msg_key: &[u8; 16], auth_key: &[u8]) -> ([u8; 32], [u8; 32]) {
    let sha_a = sha1_parts(&[msg_key, &auth_key[0..32]]);
    let sha_b = sha1_parts(&[&auth_key[32..48], msg_key, &auth_key[48..64]]);
    let sha_c = sha1_parts(&[&auth_key[64..96], msg_key]);
    let sha_d = sha1_parts(&[msg_key, &auth_key[96..128]]);
    let mut key = [0_u8; 32];
    key[..8].copy_from_slice(&sha_a[..8]);
    key[8..20].copy_from_slice(&sha_b[8..20]);
    key[20..].copy_from_slice(&sha_c[4..16]);
    let mut iv = [0_u8; 32];
    iv[..12].copy_from_slice(&sha_a[8..20]);
    iv[12..20].copy_from_slice(&sha_b[..8]);
    iv[20..24].copy_from_slice(&sha_c[16..20]);
    iv[24..].copy_from_slice(&sha_d[..8]);
    (key, iv)
}

fn sha1_parts(parts: &[&[u8]]) -> [u8; 20] {
    let mut buf = Vec::new();
    for part in parts {
        buf.extend_from_slice(part);
    }
    sha1(&buf)
}

fn encode_inner(inner: &BindAuthKeyInnerConstructor) -> Result<Vec<u8>, MtprotoError> {
    let mut encoder = Encoder::new();
    inner
        .encode_boxed(&mut encoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(encoder.into_bytes())
}

struct SystemClock;
impl Clock for SystemClock {
    fn unix_micros(&self) -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| i64::try_from(d.as_micros()).unwrap_or(i64::MAX))
            .unwrap_or(0)
    }
}

pub(crate) struct BoundTemp {
    pub snapshot: Snapshot,
    pub expires_at: i32,
}

pub(crate) fn temp_key_valid(snapshot: &Snapshot, expires_at: i32) -> bool {
    temp_key_valid_at(snapshot, expires_at, &SystemClock)
}

fn temp_key_valid_at(snapshot: &Snapshot, expires_at: i32, clock: &impl Clock) -> bool {
    let server_now = clock.unix_micros().saturating_add(snapshot.time_offset_micros).div_euclid(1_000_000);
    i64::from(expires_at) > server_now.saturating_add(60)
}

pub(crate) fn ensure_main_temp(
    client: &crate::client::Client,
    io: &mut crate::client::lanes::SessionIo,
    activate: bool,
) -> Result<bool, MtprotoError> {
    ensure_main_temp_with(client, io, activate, &SystemClock, negotiate_bound_temp)
}

pub(crate) fn ensure_main_temp_with(
    client: &crate::client::Client,
    io: &mut crate::client::lanes::SessionIo,
    activate: bool,
    clock: &impl Clock,
    negotiate: impl FnOnce(&[u8], i32, i64) -> Result<BoundTemp, MtprotoError>,
) -> Result<bool, MtprotoError> {
    client.connections.wait(std::time::Duration::ZERO)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    if !activate && io.temp_expires_at == 0 && io.perm_key.is_none() {
        return Ok(false);
    }
    let dc = {
        let data = client.data.lock();
        if data.user_id.is_none() || data.session_dead {
            return Ok(false);
        }
        data.home_dc
    };
    ensure_dc_temp_with(client, io, dc, None, clock, negotiate)
}

pub(crate) fn dc_permanent_key(data: &crate::client::ClientData, dc: i32) -> Option<&[u8]> {
    let key = if dc == data.home_dc {
        data.home_auth_key.as_deref()
    } else {
        data.dc_auth_keys.get(&dc).map(|entry| entry.key.as_slice())
    };
    key.filter(|key| key.len() == 256)
}

pub(crate) fn ensure_dc_temp_with(
    client: &crate::client::Client,
    io: &mut crate::client::lanes::SessionIo,
    dc: i32,
    expected_home: Option<i64>,
    clock: &impl Clock,
    negotiate: impl FnOnce(&[u8], i32, i64) -> Result<BoundTemp, MtprotoError>,
) -> Result<bool, MtprotoError> {
    client.connections.wait(std::time::Duration::ZERO)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let (permanent, owner, user, cached) = {
        let data = client.data.lock();
        if data.user_id.is_none() || data.session_dead
            || expected_home.is_some_and(|session| session != data.home_session_id)
        {
            return Err(crate::client::expired_session_lease());
        }
        let permanent = dc_permanent_key(&data, dc).ok_or_else(||
            MtprotoError::Message("permanent session identity unavailable".into()))?.to_vec();
        let cached = expected_home.and_then(|_| data.dc_temp_auth.get(&dc))
            .filter(|entry| entry.perm_key == permanent && temp_key_valid_at(&entry.snapshot, entry.temp_expires_at, clock))
            .cloned();
        (permanent, data.home_snapshot.clone(), data.user_id, cached)
    };
    if io.perm_key.as_deref() == Some(permanent.as_slice())
        && io.snapshot.dc_id == dc
        && io.snapshot.auth_key.as_ref().is_some_and(|key| key.len() == 256 && key != &permanent)
        && temp_key_valid_at(&io.snapshot, io.temp_expires_at, clock)
    {
        return Ok(false);
    }
    crate::request_control::check().map_err(|e| MtprotoError::Message(e.to_string()))?;
    let bound = if let Some(cached) = cached {
        BoundTemp { snapshot: crate::client::fork_session(&cached.snapshot), expires_at: cached.temp_expires_at }
    } else {
        crate::tcp::with_connection_control(&client.connections, || negotiate(&permanent, dc, owner.time_offset_micros))?
    };
    crate::request_control::check().map_err(|e| MtprotoError::Message(e.to_string()))?;
    bound.snapshot.validate().map_err(|e| MtprotoError::Message(e.to_string()))?;
    if bound.snapshot.dc_id != dc || bound.snapshot.session_id == owner.session_id
        || !bound.snapshot.auth_key.as_ref().is_some_and(|key| key.len() == 256 && key != &permanent)
        || !temp_key_valid_at(&bound.snapshot, bound.expires_at, clock)
    {
        return Err(MtprotoError::Message("invalid bound temporary session".into()));
    }
    let mut data = client.data.lock();
    if !crate::client::session_lease_valid(&data, owner.session_id) || data.user_id != user
        || data.home_dc != owner.dc_id || data.home_auth_key != owner.auth_key
        || dc_permanent_key(&data, dc) != Some(permanent.as_slice())
    {
        return Err(crate::client::expired_session_lease());
    }
    client.connections.while_open(|| {
        client.connections.wait(std::time::Duration::ZERO)?;
        if let Some(mut previous) = io.transport.take() {
            let _ = previous.conn.close();
        }
        crate::rpc::salts::forget_session(io.snapshot.session_id);
        if expected_home.is_some() {
            data.dc_temp_auth.insert(dc, crate::client::DcTempAuth {
                snapshot: bound.snapshot.clone(), temp_expires_at: bound.expires_at, perm_key: permanent.clone(),
            });
        }
        io.snapshot = bound.snapshot;
        io.temp_expires_at = bound.expires_at;
        io.perm_key = Some(permanent);
        io.pending_push = Default::default();
        io.last_difference = None;
        Ok::<_, tellers_mtproto_transport::Error>(())
    }).map_err(|e| MtprotoError::Message(e.to_string()))?
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(true)
}

struct DeadlineConnection<'a> {
    inner: &'a mut crate::tcp::ObfuscatedTcp,
    deadline: std::time::Instant,
}

impl DeadlineConnection<'_> {
    fn prepare(&mut self) -> Result<(), tellers_mtproto_transport::Error> {
        let remaining = self.deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            return Err(tellers_mtproto_transport::Error::Connection("temporary key negotiation timed out".into()));
        }
        self.inner.set_io_timeout_ms(remaining.as_millis().min(u128::from(u64::MAX)) as u64);
        Ok(())
    }
}

impl Connection for DeadlineConnection<'_> {
    fn send(&mut self, packet: &[u8]) -> Result<(), tellers_mtproto_transport::Error> {
        self.prepare()?;
        self.inner.send(packet)
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, tellers_mtproto_transport::Error> {
        self.prepare()?;
        self.inner.receive(output)
    }

    fn close(&mut self) -> Result<(), tellers_mtproto_transport::Error> {
        self.inner.close()
    }
}

/// Handshake a temporary key and bind it. Leaves the permanent key untouched.
pub(crate) fn negotiate_bound_temp(
    perm_key: &[u8],
    dc_id: i32,
    time_offset_micros: i64,
) -> Result<BoundTemp, MtprotoError> {
    if perm_key.len() != 256 {
        return Err(MtprotoError::Message("permanent key required".into()));
    }
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let mut snapshot =
        Snapshot::new(dc_id, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
    snapshot.time_offset_micros = time_offset_micros;
    let mut conn = crate::tcp::connect_obfuscated_dc(crate::rpc::dc_endpoints(dc_id))
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut conn = DeadlineConnection { inner: &mut conn, deadline };
    let mut framing = PaddedIntermediate::default();
    let expires_at = create_temp_auth_key(&mut conn, &mut framing, &mut snapshot, TEMP_EXPIRES_IN)?;
    bind_temporary_key(&mut conn, &mut framing, &mut snapshot, perm_key, expires_at, &SystemClock)?;
    Ok(BoundTemp { snapshot, expires_at })
}

fn bind_temporary_key(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    snapshot: &mut Snapshot,
    perm_key: &[u8],
    expires_at: i32,
    clock: &impl Clock,
) -> Result<(), MtprotoError> {
    let temp_key = zeroize::Zeroizing::new(snapshot
        .auth_key
        .clone()
        .ok_or_else(|| MtprotoError::Message("temp key missing".into()))?);
    if temp_key.as_slice() == perm_key {
        return Err(MtprotoError::Message(
            "temp key matched permanent key".into(),
        ));
    }
    let msg_id = snapshot
        .next_message_id(clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut nonce_bytes = [0_u8; 8];
    fill_random(&mut nonce_bytes).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut prefix = [0_u8; 16];
    fill_random(&mut prefix).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let inner = BindAuthKeyInnerConstructor {
        nonce: i64::from_le_bytes(nonce_bytes),
        temp_auth_key_id: auth_key_id(&temp_key),
        perm_auth_key_id: auth_key_id(perm_key),
        temp_session_id: snapshot.session_id,
        expires_at,
    };
    let mut padding = [0_u8; 8];
    fill_random(&mut padding).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let encrypted = encrypt_bind_message(perm_key, msg_id, &inner, &prefix, &padding)?;
    let request = tellers_mtproto::latest::api::AuthBindTempAuthKeyRequest {
        perm_auth_key_id: inner.perm_auth_key_id,
        nonce: inner.nonce,
        expires_at,
        encrypted_message: encrypted,
    };
    let body = crate::rpc::encode_boxed_bytes(&request)?;
    let seq = snapshot
        .next_sequence(true)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    send_v2(
        conn,
        framing,
        &temp_key,
        snapshot,
        msg_id,
        seq,
        &body,
    )?;
    read_bind_result(
        conn,
        framing,
        &temp_key,
        snapshot,
        msg_id,
        clock,
    )
}

fn send_v2(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    auth_key: &[u8],
    snapshot: &mut Snapshot,
    message_id: i64,
    sequence: i32,
    body: &[u8],
) -> Result<(), MtprotoError> {
    let pad_len = v2_pad_len(body.len());
    let mut padding = vec![0_u8; pad_len];
    fill_random(&mut padding).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let packet = tellers_mtproto_crypto::encrypt_message(
        auth_key,
        tellers_mtproto_crypto::MessageToEncrypt {
            server_salt: snapshot.server_salt,
            session_id: snapshot.session_id,
            message_id,
            sequence,
            body,
            padding: &padding,
        },
        tellers_mtproto_crypto::Direction::ClientToServer,
    )
    .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let framed = framing
        .encode(&packet)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    conn.send(&framed)
        .map_err(|e| MtprotoError::Message(e.to_string()))
}

fn v2_pad_len(body_len: usize) -> usize {
    let mut pad = 12;
    while (32 + body_len + pad) % 16 != 0 {
        pad += 1;
    }
    pad
}

fn read_bind_result(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    temp_key: &[u8],
    snapshot: &mut Snapshot,
    msg_id: i64,
    clock: &impl Clock,
) -> Result<(), MtprotoError> {
    let mut buf = vec![0_u8; 64 * 1024];
    let mut input = Vec::new();
    for _ in 0..8 {
        loop {
            match framing.decode(&input) {
                Ok(packet) => {
                    let consumed = packet.consumed;
                    let payload = crate::rpc::trim_padded_mtproto_packet(&packet.payload);
                    input.drain(..consumed.min(input.len()));
                    if let Some(control) = tellers_mtproto_transport::payload_control(payload) {
                        match control {
                            tellers_mtproto_transport::Control::QuickAck(_) => break,
                            tellers_mtproto_transport::Control::TransportError(code) => {
                                return Err(MtprotoError::Message(format!("temp bind transport {code}")));
                            }
                        }
                    }
                    let message = tellers_mtproto_crypto::decrypt_message(
                        temp_key,
                        payload,
                        tellers_mtproto_crypto::Direction::ServerToClient,
                        snapshot.session_id,
                        1024 * 1024,
                    )
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                    if message.sequence < 0 { return Err(malformed_bind()); }
                    let received = snapshot.register_received_message(message.message_id, message.sequence & 1 != 0, clock, true)
                        .map_err(|e| MtprotoError::Message(e.to_string()))?;
                    if received != tellers_mtproto_session::ReceivedMessageResult::Accepted {
                        break;
                    }
                    let response = bind_response(&message.body, msg_id, snapshot, clock, message.message_id)?;
                    if let Some(salt) = response.server_salt {
                        snapshot.server_salt = salt;
                    }
                    if response.accepted {
                        return Ok(());
                    }
                    break;
                }
                Err(tellers_mtproto_transport::Error::Incomplete { .. }) => {
                    let n = conn
                        .receive(&mut buf)
                        .map_err(|e| MtprotoError::Message(e.to_string()))?;
                    if n == 0 {
                        return Err(MtprotoError::Message("temp bind connection closed".into()));
                    }
                    input.extend_from_slice(&buf[..n]);
                }
                Err(e) => return Err(MtprotoError::Message(e.to_string())),
            }
        }
    }
    Err(MtprotoError::Message("temp bind had no result".into()))
}

#[derive(Default)]
struct BindResponse {
    accepted: bool,
    server_salt: Option<i64>,
}

fn bind_response(body: &[u8], msg_id: i64, snapshot: &mut Snapshot, clock: &impl Clock, outer_id: i64) -> Result<BindResponse, MtprotoError> {
    bind_response_at_depth(body, msg_id, 0, &mut 1024, snapshot, clock, outer_id)
}

fn malformed_bind() -> MtprotoError {
    MtprotoError::Message("malformed temporary key binding response".into())
}

fn bind_response_at_depth(body: &[u8], msg_id: i64, depth: usize, remaining: &mut usize, snapshot: &mut Snapshot, clock: &impl Clock, outer_id: i64) -> Result<BindResponse, MtprotoError> {
    if depth > 8 || *remaining == 0 || body.len() < 4 || body.len() % 4 != 0 {
        return Err(malformed_bind());
    }
    *remaining -= 1;
    let ctor = u32::from_le_bytes(body[..4].try_into().expect("ctor"));
    if ctor == MSG_CONTAINER {
        return container_accepted(body, msg_id, depth, remaining, snapshot, clock, outer_id);
    }
    if ctor == RPC_RESULT {
        return Ok(BindResponse { accepted: rpc_result_accepted(body, msg_id)?, ..Default::default() });
    }
    if ctor == 0x9ec2_0908 {
        if body.len() != 28 {
            return Err(malformed_bind());
        }
        return Ok(BindResponse { server_salt: Some(i64::from_le_bytes(body[20..28].try_into().expect("salt"))), ..Default::default() });
    }
    Ok(BindResponse::default())
}

fn container_accepted(body: &[u8], msg_id: i64, depth: usize, remaining: &mut usize, snapshot: &mut Snapshot, clock: &impl Clock, outer_id: i64) -> Result<BindResponse, MtprotoError> {
    if body.len() < 8 {
        return Err(malformed_bind());
    }
    let count = u32::from_le_bytes(body[4..8].try_into().expect("count")) as usize;
    if count > *remaining || count > (body.len() - 8) / 20 {
        return Err(malformed_bind());
    }
    let mut offset = 8;
    let mut response = BindResponse::default();
    for _ in 0..count {
        if offset + 16 > body.len() {
            return Err(malformed_bind());
        }
        let child_id = i64::from_le_bytes(body[offset..offset + 8].try_into().expect("child message id"));
        let sequence = i32::from_le_bytes(body[offset + 8..offset + 12].try_into().expect("sequence"));
        if child_id >= outer_id || child_id & 1 == 0 || sequence < 0 {
            return Err(malformed_bind());
        }
        offset += 8 + 4;
        let len = u32::from_le_bytes(body[offset..offset + 4].try_into().expect("len")) as usize;
        offset += 4;
        let end = offset.checked_add(len).ok_or_else(malformed_bind)?;
        if end > body.len() || len < 4 || len % 4 != 0 {
            return Err(malformed_bind());
        }
        // Fresh containers can retransmit old children; their IDs still need
        // replay registration and content-related acknowledgements.
        let disposition = snapshot.register_received_message(child_id, sequence & 1 != 0, clock, false)
            .map_err(|_| malformed_bind())?;
        if disposition == tellers_mtproto_session::ReceivedMessageResult::Accepted {
            let child = bind_response_at_depth(&body[offset..end], msg_id, depth + 1, remaining, snapshot, clock, child_id)?;
            response.accepted |= child.accepted;
            response.server_salt = child.server_salt.or(response.server_salt);
        } else {
            *remaining = remaining.checked_sub(1).ok_or_else(malformed_bind)?;
        }
        offset = end;
    }
    if offset != body.len() {
        return Err(malformed_bind());
    }
    Ok(response)
}

fn rpc_result_accepted(body: &[u8], msg_id: i64) -> Result<bool, MtprotoError> {
    if body.len() < 16 {
        return Err(malformed_bind());
    }
    let req = i64::from_le_bytes(body[4..12].try_into().expect("req"));
    if req != msg_id {
        return Ok(false);
    }
    let inner = u32::from_le_bytes(body[12..16].try_into().expect("inner"));
    if inner == BOOL_TRUE || inner == BOOL_FALSE {
        if body.len() != 16 {
            return Err(malformed_bind());
        }
        return if inner == BOOL_TRUE { Ok(true) } else { Err(MtprotoError::Message("temp bind rejected".into())) };
    }
    if inner == RPC_ERROR {
        if body.len() > 148 {
            return Err(malformed_bind());
        }
        let mut decoder = Decoder::new(&body[16..], Limits::default()).map_err(|_| malformed_bind())?;
        let error = tellers_mtproto::transport::RpcErrorConstructor::decode(&mut decoder).map_err(|_| malformed_bind())?;
        decoder.finish().map_err(|_| malformed_bind())?;
        let token = if error.error_message.len() <= 128
            && error.error_message.bytes().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == b'_')
        { error.error_message.as_str() } else { "UNKNOWN" };
        return Err(MtprotoError::Message(format!("temp bind RPC {}: {token}", error.error_code)));
    }
    Err(MtprotoError::Message("temp bind result".into()))
}

fn promote_main(
    client: &crate::client::Client,
    negotiate: &mut impl FnMut(&[u8], i32, i64) -> Result<BoundTemp, MtprotoError>,
) -> Result<bool, MtprotoError> {
    let _gate = client.main_gate.acquire(crate::scheduler::RequestClass::BackgroundRead)?;
    let mut main = crate::client::lanes::lock_request_lane(&client.main, "lane_wait.pfs")?;
    ensure_main_temp_with(client, &mut main, true, &SystemClock, negotiate)
}

fn bind_lane(
    client: &crate::client::Client,
    lane: &crate::client::lanes::Lane,
    negotiate: &mut impl FnMut(&[u8], i32, i64) -> Result<BoundTemp, MtprotoError>,
) -> bool {
    let Some(_gate) = lane.gate.try_acquire(crate::scheduler::RequestClass::BackgroundRead) else {
        return false;
    };
    let Some(mut io) = lane.io.try_lock() else {
        return false;
    };
    ensure_main_temp_with(client, &mut io, true, &SystemClock, negotiate).is_ok()
        && io.perm_key.is_some()
}

/// Additional main sessions are admitted only after their temporary key is bound.
pub(crate) fn prepare_extra_main_sessions(
    handle: u64,
    mut negotiate: impl FnMut(&[u8], i32, i64) -> Result<BoundTemp, MtprotoError>,
) -> Result<(), MtprotoError> {
    let client = crate::client::get_client(handle)?;
    let _policy = crate::transfer_policy::bind(client.policy.clone());
    {
        let data = client.data.lock();
        if data.user_id.is_none() || data.session_dead || data.home_auth_key.is_none() {
            crate::scheduler::set_bound_extra_sessions(0);
            return Ok(());
        }
    }
    promote_main(&client, &mut negotiate)?;
    for lane in &client.upload {
        let _ = bind_lane(&client, lane, &mut negotiate);
    }
    let allowance = crate::scheduler::main_session_allowance();
    if allowance <= 1 {
        crate::scheduler::set_bound_extra_sessions(0);
        return Ok(());
    }
    let want = usize::try_from(allowance.saturating_sub(1)).unwrap_or(0);
    let want = want.min(crate::scheduler::READ_LANES);
    let mut bound = 0_usize;
    for lane in client.rpc.iter().take(want) {
        if bind_lane(&client, lane, &mut negotiate) {
            bound += 1;
        }
    }
    crate::scheduler::set_bound_extra_sessions(bound);
    Ok(())
}

#[cfg(test)]
#[path = "../../tests/unit/auth_bind_temp_tests.rs"]
mod wire_tests;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bind_message_uses_v1_msg_key_without_padding() {
        let perm = vec![3_u8; 256];
        let inner = BindAuthKeyInnerConstructor {
            nonce: 7,
            temp_auth_key_id: 8,
            perm_auth_key_id: auth_key_id(&perm),
            temp_session_id: 9,
            expires_at: 1_700_000_000,
        };
        let prefix = [1_u8; 16];
        let packet = encrypt_bind_message(&perm, 100, &inner, &prefix, &[2_u8; 8]).unwrap();
        assert_eq!(
            i64::from_le_bytes(packet[..8].try_into().unwrap()),
            auth_key_id(&perm)
        );
        let msg_key: [u8; 16] = packet[8..24].try_into().unwrap();
        let (aes_key, aes_iv) = v1_aes(&msg_key, &perm);
        let plain = aes_ige_decrypt(&packet[24..], &aes_key, &aes_iv).unwrap();
        assert_eq!(&plain[..16], &prefix);
        assert_eq!(i64::from_le_bytes(plain[16..24].try_into().unwrap()), 100);
        assert_eq!(i32::from_le_bytes(plain[24..28].try_into().unwrap()), 0);
        assert_eq!(i32::from_le_bytes(plain[28..32].try_into().unwrap()), 40);
        assert_eq!(v1_msg_key(&plain[..plain.len() - 8]), msg_key);
        assert_eq!(&plain[plain.len() - 8..], &[2_u8; 8]);
    }
}
