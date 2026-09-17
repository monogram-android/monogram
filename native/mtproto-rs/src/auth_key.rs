//! Telegram authorization-key creation using telers-mtproto-impl + tellers-mtproto types.

use bnum::types::{I128, I256};
use tellers_mtproto::codec::{Boxed, BoxedDecode, Decoder, Encoder, Limits};
use tellers_mtproto::transport::{
    PQInnerDataDcConstructor, ReqDhParamsRequest, ReqPqMultiRequest, ResPq, ServerDhInnerData,
    ServerDhParams, SetClientDhParamsAnswer, SetClientDhParamsRequest,
};
use tellers_mtproto_crypto::{
    aes_ige_decrypt, aes_ige_encrypt, derive_auth_key, factor_pq, fill_random, initial_server_salt,
    new_nonce_hash, parse_rsa_public_key, rsa_pad, rsa_public_key_fingerprint,
    temporary_aes_key_iv, validate_dh_parameters, validate_dh_public_value,
};
use tellers_mtproto_engine::{
    Authorization, AuthorizationAdapter, AuthorizationPhase, PlainMessage, decode_plain_message,
    encode_plain_message,
};
use tellers_mtproto_session::{Clock, Snapshot};
use tellers_mtproto_transport::{Connection, Framing, PaddedIntermediate};

use crate::MtprotoError;

const PROD_RSA_PEM: &str = "-----BEGIN RSA PUBLIC KEY-----\n\
MIIBCgKCAQEA6LszBcC1LGzyr992NzE0ieY+BSaOW622Aa9Bd4ZHLl+TuFQ4lo4g\n\
5nKaMBwK/BIb9xUfg0Q29/2mgIR6Zr9krM7HjuIcCzFvDtr+L0GQjae9H0pRB2OO\n\
62cECs5HKhT5DZ98K33vmWiLowc621dQuwKWSQKjWf50XYFw42h21P2KXUGyp2y/\n\
+aEyZ+uVgLLQbRA1dEjSDZ2iGRy12Mk5gpYc397aYp438fsJoHIgJ2lgMv5h7WY9\n\
t6N/byY9Nw9p21Og3AoXSL2q/2IJ1WRUhebgAdGVMlV1fkuOQoEzR7EdpqtQD9Cs\n\
5+bfo3Nhmcyvk5ftB0WkJ9z6bNZ7yxrP8wIDAQAB\n\
-----END RSA PUBLIC KEY-----";

/// Test-DC RSA from Telegram Desktop `kTestPublicRSAKeys`.
const TEST_RSA_PEM: &str = "-----BEGIN RSA PUBLIC KEY-----\n\
MIIBCgKCAQEAyMEdY1aR+sCR3ZSJrtztKTKqigvO/vBfqACJLZtS7QMgCGXJ6XIR\n\
yy7mx66W0/sOFa7/1mAZtEoIokDP3ShoqF4fVNb6XeqgQfaUHd8wJpDWHcR2OFwv\n\
plUUI1PLTktZ9uW2WE23b+ixNwJjJGwBDJPQEQFBE+vfmH0JP503wr5INS1poWg/\n\
j25sIWeYPHYeOrFp/eXaqhISP6G+q2IeTaWTXpwZj4LzXq5YOpk4bYEQ6mvRq7D1\n\
aHWfYmlEGepfaYR8Q0YqvvhYtMte3ITnuSJs171+GDqpdKcSwHnd6FudwGO4pcCO\n\
j4WcDuXc2CTHgH8gFTNhp/Y8/SpDOhvn9QIDAQAB\n\
-----END RSA PUBLIC KEY-----";

fn rsa_pem() -> &'static str {
    if crate::rpc::use_test_dc() {
        TEST_RSA_PEM
    } else {
        PROD_RSA_PEM
    }
}

/// `p_q_inner_data_dc.dc`: add 10000 on test DCs.
/// https://core.telegram.org/mtproto/auth_key
fn inner_data_dc(dc_id: i32) -> i32 {
    if crate::rpc::use_test_dc() {
        dc_id + 10_000
    } else {
        dc_id
    }
}

struct SystemClock;
impl Clock for SystemClock {
    fn unix_micros(&self) -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_micros() as i64)
            .unwrap_or(0)
    }
}

fn i128_from_bytes(bytes: [u8; 16]) -> I128 {
    I128::from_le_slice(&bytes).expect("16-byte I128")
}

fn i256_from_bytes(bytes: [u8; 32]) -> I256 {
    I256::from_le_slice(&bytes).expect("32-byte I256")
}

fn i128_to_bytes(value: &I128) -> [u8; 16] {
    value.to_le_bytes()
}

fn i256_to_bytes(value: &I256) -> [u8; 32] {
    value.to_le_bytes()
}

fn encode_boxed<T: Boxed>(value: &T) -> Result<Vec<u8>, MtprotoError> {
    let mut encoder = Encoder::new();
    value
        .encode_boxed(&mut encoder)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(encoder.into_bytes())
}

fn decode_boxed<T: BoxedDecode>(bytes: &[u8]) -> Result<T, MtprotoError> {
    let mut decoder =
        Decoder::new(bytes, Limits::default()).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let value = T::decode_boxed(&mut decoder).map_err(|e| MtprotoError::Message(e.to_string()))?;
    decoder
        .finish()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(value)
}

/// Decode a boxed value that may be followed by random AES padding.
fn decode_boxed_prefix<T: BoxedDecode>(bytes: &[u8]) -> Result<(T, usize), MtprotoError> {
    let mut decoder =
        Decoder::new(bytes, Limits::default()).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let value = T::decode_boxed(&mut decoder).map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok((value, decoder.offset()))
}

fn pad_be(bytes: &[u8], size: usize) -> Vec<u8> {
    if bytes.len() >= size {
        return bytes[bytes.len() - size..].to_vec();
    }
    let mut out = vec![0_u8; size];
    out[size - bytes.len()..].copy_from_slice(bytes);
    out
}

struct AuthAdapter {
    dc_id: i32,
    rsa_pem: String,
    nonce: I128,
    server_nonce: Option<I128>,
    new_nonce: Option<I256>,
    p: Option<Vec<u8>>,
    q: Option<Vec<u8>>,
    g_b: Option<Vec<u8>>,
    auth_key: Option<Vec<u8>>,
}

impl AuthAdapter {
    fn new(dc_id: i32) -> Result<Self, MtprotoError> {
        Self::with_pem(dc_id, rsa_pem().to_string())
    }

    fn with_pem(dc_id: i32, rsa_pem: String) -> Result<Self, MtprotoError> {
        let mut nonce_bytes = [0_u8; 16];
        fill_random(&mut nonce_bytes).map_err(|e| MtprotoError::Message(e.to_string()))?;
        Ok(Self {
            dc_id,
            rsa_pem,
            nonce: i128_from_bytes(nonce_bytes),
            server_nonce: None,
            new_nonce: None,
            p: None,
            q: None,
            g_b: None,
            auth_key: None,
        })
    }
}

impl AuthorizationAdapter for AuthAdapter {
    fn request_parameters(&mut self) -> Result<Vec<u8>, tellers_mtproto_engine::Error> {
        let req = ReqPqMultiRequest {
            nonce: self.nonce.clone(),
        };
        encode_boxed(&req).map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))
    }

    fn accept_parameters(
        &mut self,
        response: &[u8],
    ) -> Result<Vec<u8>, tellers_mtproto_engine::Error> {
        let res_pq = decode_boxed::<ResPq>(response)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let ResPq::ResPq(res) = res_pq;
        if res.nonce != self.nonce {
            return Err(tellers_mtproto_engine::Error::Authorization(
                "resPQ nonce mismatch".into(),
            ));
        }
        self.server_nonce = Some(res.server_nonce.clone());

        let pq = {
            // pq is big-endian bytes representing u64
            let mut buf = [0_u8; 8];
            let src = &res.pq;
            if src.len() > 8 {
                return Err(tellers_mtproto_engine::Error::Authorization(
                    "pq too large".into(),
                ));
            }
            buf[8 - src.len()..].copy_from_slice(src);
            u64::from_be_bytes(buf)
        };
        let (p, q) = factor_pq(pq)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let (p, q) = if p < q { (p, q) } else { (q, p) };
        let p_bytes = p.to_be_bytes().to_vec();
        let q_bytes = q.to_be_bytes().to_vec();
        // trim leading zeros for TL bytes
        let trim = |v: Vec<u8>| {
            let i = v
                .iter()
                .position(|b| *b != 0)
                .unwrap_or(v.len().saturating_sub(1));
            v[i..].to_vec()
        };
        let p_bytes = trim(p_bytes);
        let q_bytes = trim(q_bytes);
        self.p = Some(p_bytes.clone());
        self.q = Some(q_bytes.clone());

        let mut new_nonce_bytes = [0_u8; 32];
        fill_random(&mut new_nonce_bytes)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let new_nonce = i256_from_bytes(new_nonce_bytes);
        self.new_nonce = Some(new_nonce.clone());

        let inner = PQInnerDataDcConstructor {
            pq: res.pq.clone(),
            p: p_bytes,
            q: q_bytes,
            nonce: self.nonce.clone(),
            server_nonce: res.server_nonce.clone(),
            new_nonce,
            dc: inner_data_dc(self.dc_id),
        };
        let inner_bytes = encode_boxed(&inner)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;

        let key = parse_rsa_public_key(&self.rsa_pem)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let fingerprint = rsa_public_key_fingerprint(&key);
        let fingerprints = match &*res.server_public_key_fingerprints {
            tellers_mtproto::transport::Vector::Vector(v) => &v.field_1,
        };
        if !fingerprints.iter().any(|fp| *fp == fingerprint) {
            return Err(tellers_mtproto_engine::Error::Authorization(
                "RSA fingerprint not accepted by server".into(),
            ));
        }
        let encrypted = rsa_pad(&key, &inner_bytes)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;

        let req = ReqDhParamsRequest {
            nonce: self.nonce.clone(),
            server_nonce: res.server_nonce,
            p: self.p.clone().unwrap(),
            q: self.q.clone().unwrap(),
            public_key_fingerprint: fingerprint,
            encrypted_data: encrypted,
        };
        encode_boxed(&req).map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))
    }

    fn accept_dh(&mut self, response: &[u8]) -> Result<Vec<u8>, tellers_mtproto_engine::Error> {
        let params = decode_boxed::<ServerDhParams>(response)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let ServerDhParams::ServerDhParamsOk(ok) = params else {
            return Err(tellers_mtproto_engine::Error::Authorization(
                "server_DH_params_fail".into(),
            ));
        };
        let server_nonce = self.server_nonce.clone().ok_or_else(|| {
            tellers_mtproto_engine::Error::Authorization("missing server_nonce".into())
        })?;
        let new_nonce = self.new_nonce.clone().ok_or_else(|| {
            tellers_mtproto_engine::Error::Authorization("missing new_nonce".into())
        })?;
        let (aes_key, aes_iv) =
            temporary_aes_key_iv(&i256_to_bytes(&new_nonce), &i128_to_bytes(&server_nonce));
        let answer = aes_ige_decrypt(&ok.encrypted_answer, &aes_key, &aes_iv)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        // answer = sha1(inner) + inner + random padding (pad is NOT covered by sha1)
        if answer.len() < 24 {
            return Err(tellers_mtproto_engine::Error::Authorization(
                "DH answer too short".into(),
            ));
        }
        let (inner, inner_len) = decode_boxed_prefix::<ServerDhInnerData>(&answer[20..])
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let expected_hash = tellers_mtproto_crypto::sha1(&answer[20..20 + inner_len]);
        if answer[..20] != expected_hash {
            return Err(tellers_mtproto_engine::Error::Authorization(
                "DH answer SHA1 mismatch (bad AES key/nonce?)".into(),
            ));
        }
        let ServerDhInnerData::ServerDhInnerData(inner) = inner else {
            return Err(tellers_mtproto_engine::Error::Authorization(
                "unexpected server_DH_inner_data".into(),
            ));
        };

        use num_bigint::BigUint;
        let prime = BigUint::from_bytes_be(&inner.dh_prime);
        let g_a = BigUint::from_bytes_be(&inner.g_a);
        validate_dh_parameters(&prime, inner.g as u32)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        validate_dh_public_value(&g_a, &prime)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;

        let prime_bytes = usize::try_from(prime.bits().div_ceil(8))
            .unwrap_or(256)
            .max(256);
        let mut b_bytes = vec![0_u8; prime_bytes];
        fill_random(&mut b_bytes)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let b = BigUint::from_bytes_be(&b_bytes);
        let g = BigUint::from(inner.g as u32);
        let g_b = g.modpow(&b, &prime);
        let g_b_bytes = pad_be(&g_b.to_bytes_be(), prime_bytes);
        self.g_b = Some(g_b_bytes.clone());

        let auth_key = derive_auth_key(&g_a, &b, &prime)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        self.auth_key = Some(auth_key);

        let client_inner = tellers_mtproto::transport::ClientDhInnerDataConstructor {
            nonce: self.nonce.clone(),
            server_nonce: server_nonce.clone(),
            retry_id: 0,
            g_b: g_b_bytes,
        };
        let client_bytes = encode_boxed(&client_inner)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        // sha1 covers the exact TL object only; AES padding follows.
        let mut payload = tellers_mtproto_crypto::sha1(&client_bytes).to_vec();
        payload.extend_from_slice(&client_bytes);
        let mut pad = vec![0_u8; (16 - (payload.len() % 16)) % 16];
        if !pad.is_empty() {
            fill_random(&mut pad)
                .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
            payload.extend_from_slice(&pad);
        }
        let encrypted = aes_ige_encrypt(&payload, &aes_key, &aes_iv)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;

        let req = SetClientDhParamsRequest {
            nonce: self.nonce.clone(),
            server_nonce,
            encrypted_data: encrypted,
        };
        encode_boxed(&req).map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))
    }

    fn accept_authorization(
        &mut self,
        response: &[u8],
    ) -> Result<(Vec<u8>, i64), tellers_mtproto_engine::Error> {
        let answer = decode_boxed::<SetClientDhParamsAnswer>(response)
            .map_err(|e| tellers_mtproto_engine::Error::Authorization(e.to_string()))?;
        let auth_key = self.auth_key.clone().ok_or_else(|| {
            tellers_mtproto_engine::Error::Authorization("missing auth_key".into())
        })?;
        let new_nonce = self.new_nonce.clone().ok_or_else(|| {
            tellers_mtproto_engine::Error::Authorization("missing new_nonce".into())
        })?;
        let server_nonce = self.server_nonce.clone().ok_or_else(|| {
            tellers_mtproto_engine::Error::Authorization("missing server_nonce".into())
        })?;
        match answer {
            SetClientDhParamsAnswer::DhGenOk(ok) => {
                let expected = new_nonce_hash(&i256_to_bytes(&new_nonce), &auth_key, 1);
                let got = i128_to_bytes(&ok.new_nonce_hash1);
                if got != expected {
                    return Err(tellers_mtproto_engine::Error::Authorization(
                        "new_nonce_hash1 mismatch".into(),
                    ));
                }
                let salt =
                    initial_server_salt(&i256_to_bytes(&new_nonce), &i128_to_bytes(&server_nonce));
                Ok((auth_key, salt))
            }
            _ => Err(tellers_mtproto_engine::Error::Authorization(
                "dh_gen not ok".into(),
            )),
        }
    }
}

fn send_plain(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    clock: &SystemClock,
    snapshot: &mut Snapshot,
    body: Vec<u8>,
) -> Result<(), MtprotoError> {
    let message_id = snapshot
        .next_message_id(clock)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let plain = encode_plain_message(&PlainMessage { message_id, body })
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let packet = framing
        .encode(&plain)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    conn.send(&packet)
        .map_err(|e| MtprotoError::Message(e.to_string()))
}

fn recv_plain(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
) -> Result<Vec<u8>, MtprotoError> {
    let mut buf = vec![0_u8; 64 * 1024];
    let mut input = Vec::new();
    loop {
        let n = conn
            .receive(&mut buf)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        if n == 0 {
            return Err(MtprotoError::Message("connection closed".into()));
        }
        input.extend_from_slice(&buf[..n]);
        match framing.decode(&input) {
            Ok(packet) => {
                let payload = trim_padded_plain_packet(&packet.payload);
                let plain = decode_plain_message(payload, 1024 * 1024)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                return Ok(plain.body);
            }
            Err(tellers_mtproto_transport::Error::Incomplete { .. }) => continue,
            Err(e) => return Err(MtprotoError::Message(e.to_string())),
        }
    }
}

/// Negotiate an authorization key over an already-framed padded-intermediate connection.
pub fn create_auth_key(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    snapshot: &mut Snapshot,
) -> Result<(), MtprotoError> {
    create_auth_key_with_pem(conn, framing, snapshot, rsa_pem())
}

pub fn create_auth_key_with_pem(
    conn: &mut dyn Connection,
    framing: &mut PaddedIntermediate,
    snapshot: &mut Snapshot,
    pem: &str,
) -> Result<(), MtprotoError> {
    let clock = SystemClock;
    let adapter = AuthAdapter::with_pem(snapshot.dc_id, pem.to_string())?;
    let mut auth = Authorization::new(adapter);

    let body = auth
        .start()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    send_plain(conn, framing, &clock, snapshot, body)?;
    let response = recv_plain(conn, framing)?;
    let next = auth
        .advance(&response, snapshot)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let Some(body) = next else {
        return Err(MtprotoError::Message("auth ended early".into()));
    };
    send_plain(conn, framing, &clock, snapshot, body)?;
    let response = recv_plain(conn, framing)?;
    let next = auth
        .advance(&response, snapshot)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let Some(body) = next else {
        return Err(MtprotoError::Message("auth ended early after DH".into()));
    };
    send_plain(conn, framing, &clock, snapshot, body)?;
    let response = recv_plain(conn, framing)?;
    let next = auth
        .advance(&response, snapshot)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    if next.is_some() || auth.phase() != AuthorizationPhase::Authorized {
        return Err(MtprotoError::Message("auth key not installed".into()));
    }
    Ok(())
}

/// Padded-intermediate returns payload + 0..=15 extra bytes. Plain envelopes
/// encode their length at offset 16; strip the transport pad before decode.
fn trim_padded_plain_packet(packet: &[u8]) -> &[u8] {
    if packet.len() < 20 || packet[..8] != [0; 8] {
        return packet;
    }
    let length = u32::from_le_bytes(packet[16..20].try_into().expect("plain length")) as usize;
    let total = 20usize.saturating_add(length);
    if length > 0 && length % 4 == 0 && total <= packet.len() {
        &packet[..total]
    } else {
        packet
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trim_plain_envelope_drops_transport_pad() {
        let mut packet = vec![0_u8; 20];
        packet[16..20].copy_from_slice(&4u32.to_le_bytes());
        packet.extend_from_slice(&[1, 2, 3, 4]);
        packet.extend_from_slice(&[9; 7]);
        let trimmed = trim_padded_plain_packet(&packet);
        assert_eq!(trimmed.len(), 24);
        assert_eq!(&trimmed[20..], &[1, 2, 3, 4]);
        decode_plain_message(trimmed, 1024).expect("plain envelope");
    }

    #[test]
    fn test_dc_inner_id_adds_10000() {
        crate::rpc::set_use_test_dc(true);
        assert_eq!(inner_data_dc(2), 10_002);
        crate::rpc::set_use_test_dc(false);
        assert_eq!(inner_data_dc(2), 2);
    }

    #[test]
    fn test_and_prod_rsa_keys_parse_and_differ() {
        let prod = parse_rsa_public_key(PROD_RSA_PEM).expect("prod rsa");
        let test = parse_rsa_public_key(TEST_RSA_PEM).expect("test rsa");
        assert_ne!(
            rsa_public_key_fingerprint(&prod),
            rsa_public_key_fingerprint(&test),
        );
    }
}
