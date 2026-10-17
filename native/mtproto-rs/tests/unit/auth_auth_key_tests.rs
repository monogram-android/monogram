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
