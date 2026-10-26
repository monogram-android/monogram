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
