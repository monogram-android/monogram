//! Bind a temporary auth key so an extra home-DC session does not reuse the
//! permanent key. https://core.telegram.org/api/pfs
//! https://core.telegram.org/method/auth.bindTempAuthKey
//! https://core.telegram.org/mtproto/description_v1

use tellers_mtproto::codec::{Boxed, Encoder};
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

/// Handshake a temporary key and bind it. Leaves the permanent key untouched.
pub(crate) fn negotiate_bound_temp(
    perm_key: &[u8],
    dc_id: i32,
    time_offset_micros: i64,
) -> Result<BoundTemp, MtprotoError> {
    if perm_key.len() != 256 {
        return Err(MtprotoError::Message("permanent key required".into()));
    }
    let expires_at = unix_now().saturating_add(TEMP_EXPIRES_IN);
    let mut snapshot =
        Snapshot::new(dc_id, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
    snapshot.time_offset_micros = time_offset_micros;
    let mut conn = crate::tcp::connect_obfuscated_dc(crate::rpc::dc_endpoints(dc_id))
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut framing = PaddedIntermediate::default();
    create_temp_auth_key(&mut conn, &mut framing, &mut snapshot, TEMP_EXPIRES_IN)?;
    let temp_key = snapshot
        .auth_key
        .clone()
        .ok_or_else(|| MtprotoError::Message("temp key missing".into()))?;
    if temp_key == perm_key {
        return Err(MtprotoError::Message(
            "temp key matched permanent key".into(),
        ));
    }
    let clock = SystemClock;
    let msg_id = snapshot
        .next_message_id(&clock)
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
    let encrypted = encrypt_bind_message(perm_key, msg_id, &inner, &prefix, &[0_u8; 8])?;
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
        &mut conn,
        &mut framing,
        &temp_key,
        &mut snapshot,
        msg_id,
        seq,
        &body,
    )?;
    read_bind_result(
        &mut conn,
        &mut framing,
        &temp_key,
        snapshot.session_id,
        msg_id,
    )?;
    drop(conn);
    Ok(BoundTemp {
        snapshot,
        expires_at,
    })
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
    session_id: i64,
    msg_id: i64,
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
                        let _ = control;
                        break;
                    }
                    let message = tellers_mtproto_crypto::decrypt_message(
                        temp_key,
                        payload,
                        tellers_mtproto_crypto::Direction::ServerToClient,
                        session_id,
                        1024 * 1024,
                    )
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                    if bind_accepted(&message.body, msg_id)? {
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

fn bind_accepted(body: &[u8], msg_id: i64) -> Result<bool, MtprotoError> {
    if body.len() < 4 {
        return Ok(false);
    }
    let ctor = u32::from_le_bytes(body[..4].try_into().expect("ctor"));
    if ctor == MSG_CONTAINER {
        return container_accepted(body, msg_id);
    }
    if ctor == RPC_RESULT {
        return rpc_result_accepted(body, msg_id);
    }
    Ok(false)
}

fn container_accepted(body: &[u8], msg_id: i64) -> Result<bool, MtprotoError> {
    if body.len() < 8 {
        return Ok(false);
    }
    let count = u32::from_le_bytes(body[4..8].try_into().expect("count")) as usize;
    let mut offset = 8;
    for _ in 0..count {
        if offset + 16 > body.len() {
            return Ok(false);
        }
        offset += 8 + 4;
        let len = u32::from_le_bytes(body[offset..offset + 4].try_into().expect("len")) as usize;
        offset += 4;
        let end = offset.saturating_add(len);
        if end > body.len() {
            return Ok(false);
        }
        if bind_accepted(&body[offset..end], msg_id)? {
            return Ok(true);
        }
        offset = end;
    }
    Ok(false)
}

fn rpc_result_accepted(body: &[u8], msg_id: i64) -> Result<bool, MtprotoError> {
    if body.len() < 16 {
        return Ok(false);
    }
    let req = i64::from_le_bytes(body[4..12].try_into().expect("req"));
    if req != msg_id {
        return Ok(false);
    }
    let inner = u32::from_le_bytes(body[12..16].try_into().expect("inner"));
    if inner == BOOL_TRUE {
        return Ok(true);
    }
    if inner == BOOL_FALSE {
        return Err(MtprotoError::Message("temp bind rejected".into()));
    }
    if inner == RPC_ERROR {
        let code = if body.len() >= 20 {
            i32::from_le_bytes(body[16..20].try_into().expect("code"))
        } else {
            0
        };
        return Err(MtprotoError::Message(format!("temp bind {code}")));
    }
    Err(MtprotoError::Message("temp bind result".into()))
}

fn promote_main(client: &crate::client::Client, perm: &[u8], dc: i32, offset: i64) -> bool {
    let now = unix_now();
    {
        let main = client.main.lock();
        if main.temp_expires_at > now && main.snapshot.auth_key.as_deref() != Some(perm) {
            return true;
        }
    }
    let Ok(session) = negotiate_bound_temp(perm, dc, offset) else {
        eprintln!("monogram.api main temp bind failed");
        return false;
    };
    let mut main = client.main.lock();
    main.snapshot = session.snapshot;
    main.temp_expires_at = session.expires_at;
    main.transport = None;
    main.pending_push = Default::default();
    main.last_difference = None;
    true
}

fn bind_lane(
    lane: &crate::client::lanes::Lane,
    perm: &[u8],
    dc: i32,
    offset: i64,
    now: i32,
) -> bool {
    let Some(mut io) = lane.io.try_lock() else {
        return false;
    };
    if io.temp_expires_at > now
        && io.snapshot.auth_key.as_deref() != Some(perm)
        && io.snapshot.auth_key.as_ref().map(Vec::len) == Some(256)
    {
        return true;
    }
    match negotiate_bound_temp(perm, dc, offset) {
        Ok(session) => {
            io.snapshot = session.snapshot;
            io.temp_expires_at = session.expires_at;
            io.transport = None;
            io.pending_push = Default::default();
            io.last_difference = None;
            true
        }
        Err(_) => {
            eprintln!("monogram.api temp session bind failed");
            false
        }
    }
}

/// After `help.getConfig`, bind telegram extra sockets.
/// Read lanes stay within `tmp_sessions`. Upload lanes 1..4 are file sockets.
/// Failure leaves the single main connection and upload lane 0 in place.
pub(crate) fn prepare_extra_main_sessions(handle: u64) {
    let Ok(client) = crate::client::get_client(handle) else {
        return;
    };
    let (perm, dc, offset) = {
        let data = client.data.lock();
        if data.user_id.is_none() || data.session_dead {
            crate::scheduler::set_bound_extra_sessions(0);
            return;
        }
        let Some(key) = data.home_auth_key.clone() else {
            crate::scheduler::set_bound_extra_sessions(0);
            return;
        };
        (key, data.home_dc, data.home_time_offset)
    };
    let now = unix_now();
    let _ = promote_main(&client, &perm, dc, offset);
    for lane in &client.upload {
        let _ = bind_lane(lane, &perm, dc, offset, now);
    }
    let allowance = crate::scheduler::main_session_allowance();
    if allowance <= 1 {
        crate::scheduler::set_bound_extra_sessions(0);
        return;
    }
    let want = usize::try_from(allowance.saturating_sub(1)).unwrap_or(0);
    let want = want.min(crate::scheduler::READ_LANES);
    let mut bound = 0_usize;
    for lane in client.rpc.iter().take(want) {
        if bind_lane(lane, &perm, dc, offset, now) {
            bound += 1;
        }
    }
    crate::scheduler::set_bound_extra_sessions(bound);
}

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
