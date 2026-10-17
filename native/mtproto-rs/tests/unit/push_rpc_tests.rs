use super::*;

#[test]
fn token_types_match_telegram_push_docs() {
    assert_eq!(TOKEN_TYPE_FCM, 2);
    assert_eq!(TOKEN_TYPE_SIMPLE, 4);
    assert_eq!(TOKEN_TYPE_WEB_PUSH, 10);
}

#[test]
fn empty_token_is_rejected_without_network() {
    let err = register_device(
        &mut Snapshot::new(2, &mut tellers_mtproto_session::OsRandom).expect("snap"),
        1,
        TOKEN_TYPE_FCM,
        "  ".into(),
        vec![0; 256],
        true,
        false,
        Vec::new(),
    )
    .unwrap_err();
    assert!(format!("{err}").contains("TOKEN_EMPTY"));
}

#[test]
fn fcm_secret_must_be_256_bytes() {
    let err = register_device(
        &mut Snapshot::new(2, &mut tellers_mtproto_session::OsRandom).expect("snap"),
        1,
        TOKEN_TYPE_FCM,
        "token".into(),
        vec![0; 16],
        true,
        false,
        vec![1],
    )
    .unwrap_err();
    assert!(format!("{err}").contains("256"));
}

#[test]
fn invalid_token_type_is_rejected_without_network() {
    let err = register_device(
        &mut Snapshot::new(2, &mut tellers_mtproto_session::OsRandom).expect("snap"),
        1,
        99,
        "token".into(),
        Vec::new(),
        true,
        false,
        Vec::new(),
    )
    .unwrap_err();
    assert!(format!("{err}").contains("TOKEN_TYPE_INVALID"));
}

#[test]
fn decrypt_round_trip_encrypted_fcm_p() {
    let secret = vec![0x42_u8; 256];
    let json =
        r#"{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"msg_id":9}}"#;
    let payload = encrypt_push_payload_for_test(&secret, json).expect("encrypt");
    let out = decrypt_push_payload(secret, payload).expect("decrypt");
    assert_eq!(out, json);
}

#[test]
fn decrypt_accepts_plain_json_and_simple_push() {
    let json = r#"{"loc_key":"MESSAGE_MUTED"}"#;
    assert_eq!(decrypt_push_payload(Vec::new(), json.into()).unwrap(), json);
    assert_eq!(
        decrypt_push_payload(Vec::new(), "version=12".into()).unwrap(),
        r#"{"loc_key":"WAKE"}"#
    );
}

#[test]
fn decrypt_rejects_wrong_secret() {
    let secret = vec![0x11_u8; 256];
    let json = r#"{"loc_key":"MESSAGE_TEXT"}"#;
    let payload = encrypt_push_payload_for_test(&secret, json).expect("encrypt");
    let err = decrypt_push_payload(vec![0x22; 256], payload).unwrap_err();
    assert!(format!("{err}").contains("auth_key_id") || format!("{err}").contains("msg_key"));
}
