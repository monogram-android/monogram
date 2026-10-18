use super::*;
use tellers_mtproto::transport::{ServerDhInnerDataConstructor, ServerDhParamsOkConstructor, DhGenOkConstructor};
use num_bigint::BigUint;

// Public group-14 prime, RFC 3526 section 3. Synthetic peer/key material only.
const DH_PRIME: &str = concat!(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1",
    "29024E088A67CC74020BBEA63B139B22514A08798E3404DD",
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245",
    "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED",
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D",
    "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F",
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D",
    "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B",
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9",
    "DE2BCBF6955817183995497CEA956AE515D2261898FA0510",
    "15728E5A8AACAA68FFFFFFFFFFFFFFFF"
);

fn dh_fixture() -> (AuthAdapter, ServerDhInnerDataConstructor, BigUint) {
    let mut adapter = AuthAdapter::new(2).unwrap();
    adapter.nonce = i128_from_bytes([1; 16]);
    adapter.server_nonce = Some(i128_from_bytes([2; 16]));
    adapter.new_nonce = Some(i256_from_bytes([3; 32]));
    adapter.expires_in = Some(120);
    adapter.clock = || 1_700_000_000_000_000;
    let prime = BigUint::parse_bytes(DH_PRIME.as_bytes(), 16).unwrap();
    let secret = BigUint::from_bytes_be(&[5; 256]);
    let g_a = BigUint::from(2_u32).modpow(&secret, &prime).to_bytes_be();
    let inner = ServerDhInnerDataConstructor {
        nonce: adapter.nonce,
        server_nonce: adapter.server_nonce.unwrap(),
        g: 2,
        dh_prime: prime.to_bytes_be(),
        g_a,
        server_time: 1_700_000_500,
    };
    (adapter, inner, secret)
}

fn dh_response(adapter: &AuthAdapter, inner: &ServerDhInnerDataConstructor) -> ServerDhParamsOkConstructor {
    let bytes = encode_boxed(inner).unwrap();
    let mut plain = tellers_mtproto_crypto::sha1(&bytes).to_vec();
    plain.extend_from_slice(&bytes);
    plain.resize(plain.len().next_multiple_of(16), 0);
    let (key, iv) = temporary_aes_key_iv(&i256_to_bytes(&adapter.new_nonce.unwrap()),
        &i128_to_bytes(&adapter.server_nonce.unwrap()));
    ServerDhParamsOkConstructor {
        nonce: adapter.nonce,
        server_nonce: adapter.server_nonce.unwrap(),
        encrypted_answer: aes_ige_encrypt(&plain, &key, &iv).unwrap(),
    }
}

#[test]
fn verified_dh_uses_authenticated_server_clock_expiry_and_independent_shared_key() {
    let (mut adapter, inner, secret) = dh_fixture();
    let response = encode_boxed(&dh_response(&adapter, &inner)).unwrap();
    let request_bytes = adapter.accept_dh(&response).unwrap();
    assert_eq!(&request_bytes[..4], &tellers_mtproto::transport::SetClientDhParamsRequest::ID.to_le_bytes());
    let mut decoder = Decoder::new(&request_bytes[4..], Limits::default()).unwrap();
    let request = <tellers_mtproto::transport::SetClientDhParamsRequest as tellers_mtproto::codec::TlDecode>::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    let (key, iv) = temporary_aes_key_iv(&[3; 32], &[2; 16]);
    let plain = aes_ige_decrypt(&request.encrypted_data, &key, &iv).unwrap();
    let (tellers_mtproto::transport::ClientDhInnerData::ClientDhInnerData(client), used) =
        decode_boxed_prefix::<tellers_mtproto::transport::ClientDhInnerData>(&plain[20..]).unwrap();
    assert_eq!(&plain[..20], tellers_mtproto_crypto::sha1(&plain[20..20 + used]));
    assert_eq!(client.nonce, inner.nonce);
    assert_eq!(client.server_nonce, inner.server_nonce);
    let prime = BigUint::from_bytes_be(&inner.dh_prime);
    let shared = BigUint::from_bytes_be(&client.g_b).modpow(&secret, &prime).to_bytes_be();
    let expected = pad_be(&shared, 256);
    assert_eq!(adapter.auth_key.as_deref(), Some(expected.as_slice()));
    assert_eq!(adapter.time_offset_micros(), Some(500_000_000));
    assert_eq!(adapter.expires_at, Some(1_700_000_620));
    let mut hashed = vec![3; 32];
    hashed.push(1);
    hashed.extend_from_slice(&tellers_mtproto_crypto::sha1(&expected)[..8]);
    let confirmation = DhGenOkConstructor {
        nonce: inner.nonce,
        server_nonce: inner.server_nonce,
        new_nonce_hash1: I128::from_le_slice(&tellers_mtproto_crypto::sha1(&hashed)[4..20]).unwrap(),
    };
    for mutate in [0, 1] {
        let mut bad = confirmation.clone();
        if mutate == 0 { bad.nonce = i128_from_bytes([0; 16]); } else { bad.server_nonce = i128_from_bytes([0; 16]); }
        assert!(adapter.accept_authorization(&encode_boxed(&bad).unwrap()).unwrap_err()
            .to_string().contains("completion nonce"));
    }
    let (key, salt) = adapter.accept_authorization(&encode_boxed(&confirmation).unwrap()).unwrap();
    assert_eq!(key, expected);
    assert_eq!(salt, initial_server_salt(&[3; 32], &[2; 16]));
    assert!(adapter.auth_key.is_none());
}

#[test]
fn dh_nonce_mismatch_is_rejected_before_any_key_or_clock_publication() {
    let (mut adapter, inner, _) = dh_fixture();
    for field in 0..4 {
        let mut bad_inner = inner.clone();
        if field == 2 { bad_inner.nonce = i128_from_bytes([0; 16]); }
        if field == 3 { bad_inner.server_nonce = i128_from_bytes([0; 16]); }
        let mut response = dh_response(&adapter, &bad_inner);
        if field == 0 { response.nonce = i128_from_bytes([0; 16]); }
        if field == 1 { response.server_nonce = i128_from_bytes([0; 16]); }
        let error = adapter.accept_dh(&encode_boxed(&response).unwrap()).unwrap_err();
        assert!(error.to_string().contains("nonce mismatch"));
        assert!(adapter.auth_key.is_none());
        assert!(adapter.time_offset_micros().is_none());
        assert!(adapter.expires_at.is_none());
    }
}

#[test]
fn authorization_rejects_an_oversized_incomplete_frame_before_buffering_it_all() {
    struct Peer { reads: usize }
    impl Connection for Peer {
        fn send(&mut self, _: &[u8]) -> Result<(), tellers_mtproto_transport::Error> { Ok(()) }
        fn receive(&mut self, output: &mut [u8]) -> Result<usize, tellers_mtproto_transport::Error> {
            output.fill(0);
            if self.reads == 0 { output[..4].copy_from_slice(&(2_u32 * 1024 * 1024).to_le_bytes()); }
            self.reads += 1;
            Ok(output.len())
        }
        fn close(&mut self) -> Result<(), tellers_mtproto_transport::Error> { Ok(()) }
    }
    let mut peer = Peer { reads: 0 };
    let error = recv_plain(&mut peer, &mut PaddedIntermediate::default()).unwrap_err();
    assert!(error.to_string().contains("size limit"));
    assert!(peer.reads <= 18);
}

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
