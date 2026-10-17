use super::*;
use tellers_mtproto::latest::api::{Bool, BoolTrueConstructor, HelpGetConfigRequest};

#[test]
fn reconnect_replays_reads_but_not_mutations_or_unknown_methods() {
    use tellers_mtproto::latest::api::*;
    assert!(replay_safe_method(MessagesGetHistoryRequest::ID));
    assert!(replay_safe_method(UploadGetFileRequest::ID));
    assert!(replay_safe_method(UploadSaveFilePartRequest::ID));
    assert!(replay_safe_method(UploadSaveBigFilePartRequest::ID));
    assert!(!replay_safe_method(AuthSendCodeRequest::ID));
    assert!(!replay_safe_method(MessagesSendMessageRequest::ID));
    assert!(!replay_safe_method(MessagesSendMediaRequest::ID));
    assert!(!replay_safe_method(MessagesForwardMessagesRequest::ID));
    assert!(!replay_safe_method(0));
}

fn contains_ctor(bytes: &[u8], ctor: u32) -> bool {
    let marker = ctor.to_le_bytes();
    bytes.windows(4).any(|w| w == marker)
}

#[test]
fn updates_lane_gzip_packs_a_large_query() {
    let plain = vec![b'q'; 20_000];
    let encoded = super::encode_updates_lane_body(&plain);
    assert_eq!(
        u32::from_le_bytes(encoded[0..4].try_into().unwrap()),
        0x3072_cfa1
    );
    assert!(encoded.len() < plain.len());
    assert_eq!(rpc::ungzip_if_needed(&encoded).expect("ungzip"), plain);
}

#[test]
fn file_wrap_includes_invoke_without_updates() {
    let query = rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
        1,
        HelpGetConfigRequest {},
    ))
    .expect("query wrap");
    let updates = rpc::encode_boxed_bytes(&wrap_init_connection(1, HelpGetConfigRequest {}))
        .expect("updates wrap");
    let file = rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
        1,
        HelpGetConfigRequest {},
    ))
    .expect("file wrap");
    assert!(
        contains_ctor(&query, InvokeWithoutUpdatesRequest::<()>::ID),
        "query RPCs must wrap invokeWithoutUpdates so updates do not starve rpc_result",
    );
    assert!(
        !contains_ctor(&updates, InvokeWithoutUpdatesRequest::<()>::ID),
        "getDifference/getState must subscribe the updates lane",
    );
    assert!(
        contains_ctor(&file, InvokeWithoutUpdatesRequest::<()>::ID),
        "file RPCs must wrap invokeWithoutUpdates (0xbf9459b7)",
    );
    assert!(contains_ctor(&file, InitConnectionRequest::<()>::ID));
    assert!(contains_ctor(&file, InvokeWithLayerRequest::<()>::ID));
    assert_ne!(query, updates);
}

#[test]
fn established_query_uses_only_invoke_without_updates() {
    let first = rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
        1,
        HelpGetConfigRequest {},
    ))
    .expect("first query wrap");
    let established = rpc::encode_boxed_bytes(&wrap_without_updates(HelpGetConfigRequest {}))
        .expect("established query wrap");
    assert!(contains_ctor(&first, InitConnectionRequest::<()>::ID));
    assert!(contains_ctor(&first, InvokeWithLayerRequest::<()>::ID));
    assert!(contains_ctor(
        &established,
        InvokeWithoutUpdatesRequest::<()>::ID
    ));
    assert!(!contains_ctor(
        &established,
        InitConnectionRequest::<()>::ID
    ));
    assert!(!contains_ctor(
        &established,
        InvokeWithLayerRequest::<()>::ID
    ));
}

#[test]
fn established_updates_lane_uses_the_method_without_wrappers() {
    let first = rpc::encode_boxed_bytes(&wrap_init_connection(1, HelpGetConfigRequest {}))
        .expect("first update-lane wrap");
    let established =
        rpc::encode_boxed_bytes(&HelpGetConfigRequest {}).expect("established update-lane request");
    assert!(contains_ctor(&first, InitConnectionRequest::<()>::ID));
    assert!(contains_ctor(&first, InvokeWithLayerRequest::<()>::ID));
    assert!(!contains_ctor(
        &established,
        InitConnectionRequest::<()>::ID
    ));
    assert!(!contains_ctor(
        &established,
        InvokeWithLayerRequest::<()>::ID
    ));
    assert!(!contains_ctor(
        &established,
        InvokeWithoutUpdatesRequest::<()>::ID
    ));
}

#[test]
fn file_migrate_still_media_dc_only() {
    assert_eq!(
        migrate_dc(&MtprotoError::Message("FILE_MIGRATE_4".into())),
        Some(4)
    );
    assert_eq!(
        migrate_dc(&MtprotoError::Message("RPC 303: FILE_MIGRATE_2".into())),
        Some(2)
    );
    assert_eq!(
        migrate_dc(&MtprotoError::Message("USER_MIGRATE_5".into())),
        Some(5)
    );
    assert_eq!(
        migrate_dc(&MtprotoError::Message("RPC timeout recv=0".into())),
        None
    );
}

#[test]
fn extra_read_flag_selects_without_updates_wrap() {
    assert!(!invoking_without_updates());
    with_invoke_without_updates(|| {
        assert!(invoking_without_updates());
        let established = rpc::encode_boxed_bytes(&wrap_without_updates(HelpGetConfigRequest {}))
            .expect("extra-lane wrap");
        assert!(contains_ctor(
            &established,
            InvokeWithoutUpdatesRequest::<()>::ID,
        ));
        assert!(!contains_ctor(
            &established,
            InitConnectionRequest::<()>::ID
        ));
    });
    assert!(!invoking_without_updates());
}

#[test]
fn response_decode_rejects_trailing_tl_bytes() {
    let mut encoded = rpc::encode_boxed_bytes(&BoolTrueConstructor {}).expect("encode bool");
    encoded.extend_from_slice(&0_u32.to_le_bytes());

    assert!(decode_response::<Bool>(&encoded).is_err());
}
