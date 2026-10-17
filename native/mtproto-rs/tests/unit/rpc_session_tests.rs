use super::*;

#[test]
fn new_session_metadata_is_captured_with_salt() {
    clear_new_session_metadata();
    let mut body = NEW_SESSION_CREATED.to_le_bytes().to_vec();
    body.extend_from_slice(&11_i64.to_le_bytes());
    body.extend_from_slice(&22_i64.to_le_bytes());
    body.extend_from_slice(&33_i64.to_le_bytes());
    let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
    apply_new_session_salt(&mut snapshot, &body).expect("new session");
    assert_eq!(snapshot.server_salt, 33);
    assert_eq!(
        take_new_session_metadata(),
        Some(NewSessionMetadata {
            first_msg_id: 11,
            unique_id: 22,
            server_salt: 33,
        })
    );
}

#[test]
fn service_constructor_ids_match_generated_schema() {
    assert_eq!(NEW_SESSION_CREATED, 0x9ec2_0908);
    assert_eq!(PONG, 0x3477_73c5);
    assert_eq!(RPC_RESULT, 0xf35c_6d01);
    assert_eq!(MSG_CONTAINER, 0x73f1_f8dc);
    assert_eq!(BAD_MSG_NOTIFICATION, 0xa7ef_f811);
    assert_eq!(BAD_SERVER_SALT, 0xedab_447b);
    assert_eq!(NEW_SESSION_CREATED, NewSessionCreatedConstructor::ID);
    assert_eq!(PONG, PongConstructor::ID);
    assert!(is_updates_type(0x74ae_4240));
    assert!(is_updates_type(0x78d4_dec1));
    assert!(is_updates_type(0x725b_04c3));
    assert!(is_updates_type(0x313b_c7f8));
    assert!(is_updates_type(0x4d6d_eea5));
    assert!(is_updates_type(0x9015_e101));
    assert!(is_updates_type(0xe317_af7e));
    assert!(!is_updates_type(RPC_RESULT));
    assert!(!is_updates_type(NEW_SESSION_CREATED));
    assert_eq!(
        parse_service_or_result(&0x74ae_4240u32.to_le_bytes())
            .expect("updates")
            .len(),
        1
    );
}

#[test]
fn bad_msg_notification_is_retryable() {
    let mut body = BAD_MSG_NOTIFICATION.to_le_bytes().to_vec();
    body.extend_from_slice(&42_i64.to_le_bytes());
    body.extend_from_slice(&1_i32.to_le_bytes());
    body.extend_from_slice(&16_i32.to_le_bytes());
    let events = parse_service_or_result(&body).expect("bad_msg");
    assert!(events.iter().any(|e| matches!(
        e,
        InboundEvent::BadMessage {
            bad_msg_id: 42,
            error_code: 16
        }
    )));
    assert!(!bad_msg_should_reconnect(16));
    assert!(!bad_msg_should_reconnect(20));
    assert!(bad_msg_should_reconnect(17));
    assert!(bad_msg_should_reconnect(32));
    assert_eq!(BAD_MSG_NOTIFICATION, 0xa7ef_f811);
    assert!(idle_needs_liveness_probe(
        175030,
        4,
        0,
        BAD_MSG_NOTIFICATION
    ));
    assert!(is_transport_error(&MtprotoError::Message(
        "bad_msg_notification 17 recv=175030 last_ctor=0xa7eff811".into(),
    )));
    let user = MtprotoError::Message(
            "RPC timeout recv=175030 needed=4 available=0 prefix=0 last_ctor=0xa7eff811 via 149.154.167.51:443 reconnect".into(),
        );
    assert!(timeout_idle_needs_probe(&user));
    assert!(is_transport_error(&user));
}

#[test]
fn replay_window_rejects_non_accepted_bodies() {
    assert!(should_process_inbound(ReceivedMessageResult::Accepted));
    assert!(!should_process_inbound(ReceivedMessageResult::Duplicate));
    assert!(!should_process_inbound(ReceivedMessageResult::TooOld));
    assert!(!should_process_inbound(ReceivedMessageResult::InvalidTime));
    assert!(is_transport_error(&MtprotoError::Message(
        "invalid inbound message time; reconnect".into(),
    )));
}

#[test]
fn bad_sequence_recovery_recreates_session_and_keeps_authorization() {
    let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
    snapshot.auth_key = Some(vec![7; 256]);
    snapshot.server_salt = 42;
    snapshot.time_offset_micros = -1_500_000;
    snapshot.last_message_id = 100;
    snapshot.content_sequence = 19;
    snapshot.pending_acknowledgements.insert(5);
    snapshot.received_message_ids.insert(7, true);
    let session_id = snapshot.session_id;

    recreate_session_after_bad_message(&mut snapshot).expect("recreate session");

    assert_ne!(snapshot.session_id, session_id);
    assert_eq!(snapshot.auth_key.as_deref(), Some(vec![7; 256].as_slice()));
    assert_eq!(snapshot.server_salt, 42);
    assert_eq!(snapshot.time_offset_micros, -1_500_000);
    assert_eq!(snapshot.last_message_id, 0);
    assert_eq!(snapshot.content_sequence, 0);
    assert!(snapshot.pending_acknowledgements.is_empty());
    assert!(snapshot.received_message_ids.is_empty());
    assert_eq!(snapshot.next_sequence(true).expect("seqno"), 1);
}

#[test]
fn uncertain_mutations_are_not_replayed() {
    assert!(!may_reconnect_request(false, true));
    assert!(may_reconnect_request(false, false));
    assert!(may_reconnect_request(true, true));
    let policy = RpcRetryPolicy {
        replay_safe: false,
        backoff: ExponentialBackoff {
            timeout_micros: 1,
            initial_delay_micros: 0,
            max_attempts: 3,
        },
    };
    let mut engine = Engine::new(Snapshot::new(2, &mut OsRandom).unwrap(), policy).unwrap();
    let handle = engine
        .invoke(&RawMethod { body: vec![1; 4] }, &SystemClock)
        .unwrap();
    let outbound = engine.next_outbound().unwrap();
    let timeout = tellers_mtproto_engine::Error::Timeout {
        message_id: outbound.message_id,
        attempts: 1,
    };
    assert!(
        engine
            .fail_request(outbound.message_id, 0, &timeout)
            .is_err()
    );
    assert_eq!(engine.pending_count(), 0);
    assert!(engine.next_outbound().is_none());
    assert!(
        engine
            .take_response::<RawMethod>(&handle)
            .unwrap()
            .is_none()
    );
}

#[test]
fn correlated_rpc_errors_never_enter_transport_retry() {
    for (message, expected) in [
        ("RPC 400: CONNECTION_NOT_INITED", FailureClass::RpcRejected),
        ("RPC 400: TIMEOUT", FailureClass::RpcRejected),
        ("RPC 420: FLOOD_WAIT_60", FailureClass::RpcRejected),
        ("RPC 303: FILE_MIGRATE_4", FailureClass::Migration),
        ("RPC 303: USER_MIGRATE_2", FailureClass::Migration),
        ("RPC 401: SESSION_REVOKED", FailureClass::Authorization),
        ("RPC 406: AUTH_KEY_DUPLICATED", FailureClass::Authorization),
        ("RPC 500: CONNECTION_ERROR", FailureClass::Internal),
    ] {
        let error = MtprotoError::Message(message.into());
        assert_eq!(failure_class(&error), expected, "{message}");
        assert!(!is_transport_error(&error), "{message}");
    }
}

#[test]
fn cancellation_and_protocol_failures_have_distinct_recovery() {
    for (message, expected) in [
        ("connection error: client closed", FailureClass::Cancelled),
        ("RPC cancelled", FailureClass::Cancelled),
        ("connection reset by peer", FailureClass::Transport),
        (
            "invalid MTProto container child metadata",
            FailureClass::Protocol,
        ),
        ("invalid msg_key", FailureClass::Protocol),
    ] {
        assert_eq!(
            failure_class(&MtprotoError::Message(message.into())),
            expected
        );
    }
}

#[test]
fn late_rpc_error_does_not_complete_current_request() {
    let mut engine = Engine::new(
        Snapshot::new(2, &mut OsRandom).unwrap(),
        ExponentialBackoff {
            timeout_micros: 1_000_000,
            initial_delay_micros: 0,
            max_attempts: 3,
        },
    )
    .unwrap();
    let handle = engine
        .invoke(&RawMethod { body: vec![1; 4] }, &SystemClock)
        .unwrap();
    let outbound = engine.next_outbound().unwrap();
    let error = encode_boxed_bytes(&RpcErrorConstructor {
        error_code: 401,
        error_message: "SESSION_EXPIRED".into(),
    })
    .unwrap();
    assert!(
        engine
            .receive_result(outbound.message_id - 4, error, 3)
            .is_err()
    );
    assert!(
        engine
            .take_response::<RawMethod>(&handle)
            .unwrap()
            .is_none()
    );
    engine
        .receive_result(outbound.message_id, vec![7; 4], 5)
        .unwrap();
    assert_eq!(
        engine.take_response::<RawMethod>(&handle).unwrap(),
        Some(vec![7; 4])
    );
}

#[test]
fn inbound_envelopes_enforce_authentication_session_replay_and_time() {
    use tellers_mtproto_crypto::{Direction, MessageToEncrypt, encrypt_message};
    struct FixedClock;
    impl Clock for FixedClock {
        fn unix_micros(&self) -> i64 {
            1_700_000_000_000_000
        }
    }
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    snapshot.auth_key = Some(vec![7; 256]);
    let mut engine = Engine::new(
        snapshot.clone(),
        ExponentialBackoff {
            timeout_micros: 1_000_000,
            initial_delay_micros: 0,
            max_attempts: 1,
        },
    )
    .unwrap();
    let server_id = (1_700_000_000_i64 << 32) | 1;
    let seal = |session_id, message_id, padding: &[u8]| {
        encrypt_message(
            snapshot.auth_key.as_ref().unwrap(),
            MessageToEncrypt {
                server_salt: 0,
                session_id,
                message_id,
                sequence: 1,
                body: &[1; 4],
                padding,
            },
            Direction::ServerToClient,
        )
    };
    let packet = seal(snapshot.session_id, server_id, &[3; 12]).unwrap();
    assert!(should_process_inbound(
        engine
            .open_inbound(&packet, &FixedClock, true, 1024)
            .unwrap()
            .disposition
    ));
    assert!(!should_process_inbound(
        engine
            .open_inbound(&packet, &FixedClock, true, 1024)
            .unwrap()
            .disposition
    ));
    let mut tampered = packet;
    tampered[8] ^= 1;
    assert!(
        engine
            .open_inbound(&tampered, &FixedClock, true, 1024)
            .is_err()
    );
    let wrong_session = seal(snapshot.session_id ^ 1, server_id + 4, &[3; 12]).unwrap();
    assert!(
        engine
            .open_inbound(&wrong_session, &FixedClock, true, 1024)
            .is_err()
    );
    let even_id = seal(snapshot.session_id, server_id + 1, &[3; 12]).unwrap();
    assert!(
        engine
            .open_inbound(&even_id, &FixedClock, true, 1024)
            .is_err()
    );
    for seconds in [-301_i64, 31] {
        let packet = seal(
            snapshot.session_id,
            ((1_700_000_000 + seconds) << 32) | 1,
            &[3; 12],
        )
        .unwrap();
        assert!(!should_process_inbound(
            engine
                .open_inbound(&packet, &FixedClock, true, 1024)
                .unwrap()
                .disposition
        ));
    }
    assert!(seal(snapshot.session_id, server_id, &[3; 11]).is_err());
    assert!(seal(snapshot.session_id, server_id, &[3; 1036]).is_err());
}
