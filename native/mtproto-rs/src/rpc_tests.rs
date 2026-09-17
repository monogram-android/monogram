use super::*;

#[test]
fn detailed_info_is_a_service_notification() {
    use tellers_mtproto::transport::{MsgDetailedInfoConstructor, MsgNewDetailedInfoConstructor};
    let messages = [
        encode_boxed_bytes(&MsgDetailedInfoConstructor {
            msg_id: 4,
            answer_msg_id: 9,
            bytes: 128,
            status: 0,
        })
        .unwrap(),
        encode_boxed_bytes(&MsgNewDetailedInfoConstructor {
            answer_msg_id: 9,
            bytes: 128,
            status: 0,
        })
        .unwrap(),
    ];
    for body in messages {
        assert!(parse_service_or_result(&body).is_ok());
        for length in 4..body.len() {
            assert!(parse_service_or_result(&body[..length]).is_err());
        }
        let mut trailing = body.clone();
        trailing.extend_from_slice(&[0; 4]);
        assert!(parse_service_or_result(&trailing).is_err());
    }
}

#[test]
fn detailed_info_resends_missing_answer_and_acknowledges_received_answer() {
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    let body = detailed_answer_request(&mut snapshot, 9).unwrap().unwrap();
    let mut decoder = Decoder::new(&body, Limits::default()).unwrap();
    assert_eq!(decoder.read_u32().unwrap(), MsgResendReqConstructor::ID);
    let request = MsgResendReqConstructor::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    let Vector::Vector(ids) = *request.msg_ids;
    assert_eq!(ids.field_1, vec![9]);
    assert!(snapshot.pending_acknowledgements.is_empty());
    assert!(!snapshot.received_message_ids.contains_key(&9));

    snapshot.received_message_ids.insert(9, true);
    assert!(detailed_answer_request(&mut snapshot, 9).unwrap().is_none());
    assert_eq!(snapshot.take_acknowledgements(64), vec![9]);
}

#[test]
fn push_reader_authenticates_buffered_frame_and_parks_socket() {
    use tellers_mtproto::latest::api::UpdatesTooLongConstructor;
    use tellers_mtproto_crypto::{encrypt_message, Direction, MessageToEncrypt};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let mut transport =
        open_live_addr(2, &listener.local_addr().unwrap().to_string(), None, 1).unwrap();
    let (_peer, _) = listener.accept().unwrap();
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    snapshot.auth_key = Some(vec![7; 256]);
    let body = UpdatesTooLongConstructor::ID.to_le_bytes().to_vec();
    let message_id = ((SystemClock.unix_micros() / 1_000_000) << 32) | 1;
    let packet = encrypt_message(
        snapshot.auth_key.as_ref().unwrap(),
        MessageToEncrypt {
            server_salt: 0,
            session_id: snapshot.session_id,
            message_id,
            sequence: 1,
            body: &body,
            padding: &[3; 12],
        },
        Direction::ServerToClient,
    )
    .unwrap();
    transport
        .input
        .extend_from_slice(&(packet.len() as u32).to_le_bytes());
    transport.input.extend_from_slice(&packet);
    let mut slot = Some(transport);
    let updates = with_live_transport(&mut slot, || receive_updates(&mut snapshot)).unwrap();
    assert_eq!(updates, vec![body]);
    assert!(slot.is_some());
    assert!(snapshot.received_message_ids.contains_key(&message_id));
}

#[test]
fn queued_push_is_bounded_and_unknown_constructor_requires_recovery() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let mut transport =
        open_live_addr(2, &listener.local_addr().unwrap().to_string(), None, 1).unwrap();
    let (_peer, _) = listener.accept().unwrap();
    for _ in 0..256 {
        queue_update(&mut transport, vec![1; 4]).unwrap();
    }
    assert!(queue_update(&mut transport, vec![1; 4]).is_err());
    assert_eq!(transport.updates_bytes, 1024);
    transport.updates.clear();
    transport.updates_bytes = MAX_UNPACKED_BYTES;
    assert!(queue_update(&mut transport, vec![1; 4]).is_err());
    match parse_service_or_result(&0x01020304u32.to_le_bytes()) {
        Err(error) => assert!(error.to_string().contains("0x01020304")),
        Ok(_) => panic!("unknown constructor must require recovery"),
    }
}

#[test]
fn silent_push_socket_is_closed_after_unanswered_ping() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let mut transport =
        open_live_addr(2, &listener.local_addr().unwrap().to_string(), None, 1).unwrap();
    let (mut peer, _) = listener.accept().unwrap();
    peer.set_read_timeout(Some(std::time::Duration::from_secs(1)))
        .unwrap();
    peer.read_exact(&mut [0; 64]).unwrap();
    transport.ping_sent = Some(std::time::Instant::now() - std::time::Duration::from_secs(21));
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    snapshot.auth_key = Some(vec![7; 256]);
    let mut slot = Some(transport);
    assert!(with_live_transport(&mut slot, || receive_updates(&mut snapshot)).is_err());
    assert!(slot.is_none());
    assert_eq!(peer.read(&mut [0; 1]).unwrap(), 0);
}

#[test]
fn supervisor_rejects_reentry_but_allows_independent_media_lane() {
    let mut home = None;
    with_live_transport(&mut home, || {
        let owner = ConnectionSupervisor::acquire(2).unwrap();
        assert!(ConnectionSupervisor::acquire(2).is_err());
        let mut media = None;
        with_live_transport(&mut media, || {
            let _media_owner = ConnectionSupervisor::acquire(4).unwrap();
            assert!(ConnectionSupervisor::acquire(4).is_err());
        });
        assert!(ConnectionSupervisor::acquire(2).is_err());
        drop(owner);
        assert!(ConnectionSupervisor::acquire(2).is_ok());
    });
}

#[test]
fn supervisor_cancellation_does_not_open_or_retry_socket() {
    let control = std::sync::Arc::new(tcp::ConnectionControl::default());
    control.close();
    tcp::with_connection_control(&control, || {
        let mut supervisor = ConnectionSupervisor::acquire(2).unwrap();
        assert!(supervisor
            .backoff(std::time::Duration::from_secs(60))
            .is_err());
        assert_eq!(supervisor.state, ConnectionState::Disconnected);
        assert!(supervisor
            .connect(|| panic!("cancelled connection opened"))
            .is_err());
    });
}

#[test]
fn supervisor_closes_failed_socket_before_replacement_and_parks_ready_socket() {
    use std::net::TcpListener;
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    let mut slot = None;
    with_live_transport(&mut slot, || {
        let mut supervisor = ConnectionSupervisor::acquire(2).unwrap();
        assert!(!supervisor
            .connect(|| open_live_addr(2, &addr, None, 1))
            .unwrap());
        assert_eq!(supervisor.state, ConnectionState::Handshaking);
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(std::time::Duration::from_secs(1)))
            .unwrap();
        peer.read_exact(&mut [0; 64]).unwrap();
        supervisor.backoff(std::time::Duration::ZERO).unwrap();
        assert_eq!(peer.read(&mut [0; 1]).unwrap(), 0);
        assert!(!supervisor
            .connect(|| open_live_addr(2, &addr, None, 1))
            .unwrap());
        supervisor.park_ready();
        assert_eq!(supervisor.state, ConnectionState::Ready);
    });
    assert!(slot.is_some());
    with_live_transport(&mut slot, || {
        let mut supervisor = ConnectionSupervisor::acquire(2).unwrap();
        assert!(supervisor
            .connect(|| panic!("healthy connection replaced"))
            .unwrap());
        // Error/unwind drops the unparked transport and releases the lane.
    });
    assert!(slot.is_none());
}

#[test]
fn supervisor_unwind_releases_retry_owner() {
    let mut slot = None;
    with_live_transport(&mut slot, || {
        assert!(std::panic::catch_unwind(|| {
            let _owner = ConnectionSupervisor::acquire(2).unwrap();
            panic!("test unwind");
        })
        .is_err());
        assert!(ConnectionSupervisor::acquire(2).is_ok());
    });
}

struct ContainerClock;
impl Clock for ContainerClock {
    fn unix_micros(&self) -> i64 {
        1_700_000_000_000_000
    }
}

fn container(children: &[(i64, i32, Vec<u8>)]) -> Vec<u8> {
    let mut bytes = MSG_CONTAINER.to_le_bytes().to_vec();
    bytes.extend_from_slice(&(children.len() as i32).to_le_bytes());
    for (id, seq, body) in children {
        bytes.extend(pack_container_message(*id, *seq, body));
    }
    bytes
}

#[test]
fn authenticated_children_acknowledge_and_deduplicate_their_own_ids() {
    let id = (1_700_000_000_i64 << 32) | 1;
    let child = pack_rpc_result(99, &[1; 4]);
    let body = container(&[(id, 1, child)]);
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    let events = parse_authenticated(&body, id + 4, &mut snapshot, &ContainerClock).unwrap();
    assert!(matches!(
        events.as_slice(),
        [InboundEvent::RpcResult { req_msg_id: 99, .. }]
    ));
    assert!(snapshot.pending_acknowledgements.contains(&id));
    let duplicate = parse_authenticated(&body, id + 8, &mut snapshot, &ContainerClock).unwrap();
    assert!(duplicate.is_empty());
}

#[test]
fn authenticated_boxed_vector_preserves_legacy_server_framing() {
    let id = (1_700_000_000_i64 << 32) | 1;
    let mut body = MSG_CONTAINER.to_le_bytes().to_vec();
    body.extend_from_slice(&0x1cb5_c415_u32.to_le_bytes());
    body.extend_from_slice(&1_i32.to_le_bytes());
    body.extend_from_slice(&0x5bb8_e511_u32.to_le_bytes());
    body.extend(pack_container_message(id, 1, &pack_rpc_result(99, &[1; 4])));
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    assert_eq!(
        parse_authenticated(&body, id + 4, &mut snapshot, &ContainerClock)
            .unwrap()
            .len(),
        1
    );
}

#[test]
fn invalid_child_rolls_back_siblings_and_never_correlates() {
    let id = (1_700_000_000_i64 << 32) | 1;
    for (bad_id, bad_seq) in [(id + 20, 1), (id + 1, 1), (id + 4, -1)] {
        let body = container(&[
            (id, 1, pack_rpc_result(99, &[1; 4])),
            (bad_id, bad_seq, pack_rpc_result(100, &[2; 4])),
        ]);
        let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
        assert!(parse_authenticated(&body, id + 12, &mut snapshot, &ContainerClock).is_err());
        assert!(snapshot.received_message_ids.is_empty());
        assert!(snapshot.pending_acknowledgements.is_empty());
    }
}

#[test]
fn nested_container_and_unaligned_child_are_rejected() {
    let id = (1_700_000_000_i64 << 32) | 1;
    for payload in [container(&[]), vec![0; 5], vec![]] {
        let body = container(&[(id, 1, payload)]);
        let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
        assert!(parse_authenticated(&body, id + 4, &mut snapshot, &ContainerClock).is_err());
    }
}

#[test]
fn copied_old_message_is_accepted_once_and_acknowledged() {
    let id = (1_600_000_000_i64 << 32) | 1;
    let mut body = MSG_COPY.to_le_bytes().to_vec();
    body.extend(pack_container_message(id, 1, &pack_rpc_result(99, &[1; 4])));
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    let outer = (1_700_000_000_i64 << 32) | 1;
    assert_eq!(
        parse_authenticated(&body, outer, &mut snapshot, &ContainerClock)
            .unwrap()
            .len(),
        1
    );
    assert!(
        parse_authenticated(&body, outer + 4, &mut snapshot, &ContainerClock)
            .unwrap()
            .is_empty()
    );
    assert!(snapshot.pending_acknowledgements.contains(&id));
}

fn pack_container_message(msg_id: i64, seqno: i32, inner: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&msg_id.to_le_bytes());
    out.extend_from_slice(&seqno.to_le_bytes());
    out.extend_from_slice(&(inner.len() as i32).to_le_bytes());
    out.extend_from_slice(inner);
    out
}

fn pack_rpc_result(req_msg_id: i64, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&RPC_RESULT.to_le_bytes());
    out.extend_from_slice(&req_msg_id.to_le_bytes());
    out.extend_from_slice(payload);
    out
}

#[test]
fn test_dc_uses_test_ips() {
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            set_use_test_dc(false);
        }
    }
    let _reset = Reset;
    set_use_test_dc(true);
    assert!(dc_endpoints(2)[0].starts_with("149.154.167.40"));
    set_use_test_dc(false);
    assert!(dc_endpoints(2)[0].starts_with("149.154.167.51"));
}

#[test]
fn container_extracts_rpc_result_after_ack() {
    let ack = MSGS_ACK.to_le_bytes().to_vec();
    let rpc = pack_rpc_result(42, &[0x11, 0x22, 0x33, 0x44]);
    let mut body = Vec::new();
    body.extend_from_slice(&MSG_CONTAINER.to_le_bytes());
    body.extend_from_slice(&2_i32.to_le_bytes());
    body.extend(pack_container_message(1, 1, &ack));
    body.extend(pack_container_message(2, 3, &rpc));
    let events = parse_service_or_result(&body).expect("container");
    let found = events.into_iter().find_map(|e| match e {
        InboundEvent::RpcResult { req_msg_id, body } => Some((req_msg_id, body)),
        _ => None,
    });
    assert_eq!(found, Some((42, vec![0x11, 0x22, 0x33, 0x44])));
}

#[test]
fn container_with_vector_ctor_still_finds_rpc_result() {
    let rpc = pack_rpc_result(7, &[0x01, 0x00, 0x00, 0x00]);
    let mut body = Vec::new();
    body.extend_from_slice(&MSG_CONTAINER.to_le_bytes());
    body.extend_from_slice(&0x1cb5_c415u32.to_le_bytes());
    body.extend_from_slice(&1_i32.to_le_bytes());
    body.extend(pack_container_message(9, 1, &rpc));
    let events = parse_service_or_result(&body).expect("vector ctor container");
    assert!(events
        .iter()
        .any(|e| matches!(e, InboundEvent::RpcResult { req_msg_id: 7, .. })));
}

#[test]
fn container_inner_new_session_updates_salt_without_retrying_rpc() {
    let mut ns = NEW_SESSION_CREATED.to_le_bytes().to_vec();
    ns.extend_from_slice(&1_i64.to_le_bytes()); // first_msg_id
    ns.extend_from_slice(&2_i64.to_le_bytes()); // unique_id
    ns.extend_from_slice(&3_i64.to_le_bytes()); // server_salt
    let mut body = Vec::new();
    body.extend_from_slice(&MSG_CONTAINER.to_le_bytes());
    body.extend_from_slice(&1_i32.to_le_bytes());
    body.extend(pack_container_message(10, 1, &ns));
    let events = parse_service_or_result(&body).expect("new session container");
    assert_eq!(
        events
            .iter()
            .filter(|event| matches!(event, InboundEvent::SaltUpdated { .. }))
            .count(),
        1
    );
    assert!(!events.iter().any(|event| {
        matches!(
            event,
            InboundEvent::RetryableFailure { .. } | InboundEvent::BadMessage { .. }
        )
    }));
}

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
fn container_does_not_treat_embedded_rpc_marker_as_a_response() {
    let mut inner = MSGS_ACK.to_le_bytes().to_vec();
    inner.extend_from_slice(&[0, 0, 0, 0]);
    inner.extend(pack_rpc_result(99, &[0x01, 0x00, 0x00, 0x00]));
    let mut body = Vec::new();
    body.extend_from_slice(&MSG_CONTAINER.to_le_bytes());
    body.extend_from_slice(&1_i32.to_le_bytes());
    body.extend(pack_container_message(3, 1, &inner));
    let events = parse_service_or_result(&body).expect("scan container");
    let found = events.into_iter().find_map(|e| match e {
        InboundEvent::RpcResult { req_msg_id, body } => Some((req_msg_id, body)),
        _ => None,
    });
    assert_eq!(found, None);
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
fn msg_copy_extracts_inner_rpc_result() {
    let rpc = pack_rpc_result(5, &[0x22, 0x00, 0x00, 0x00]);
    let mut body = MSG_COPY.to_le_bytes().to_vec();
    body.extend(pack_container_message(8, 1, &rpc));
    let events = parse_service_or_result(&body).expect("msg_copy");
    assert!(events
        .iter()
        .any(|e| matches!(e, InboundEvent::RpcResult { req_msg_id: 5, .. })));
}

#[test]
fn padded_intermediate_decodes_leftover_length_prefix() {
    let mut framing = PaddedIntermediate::default();
    let payload = vec![0x11; 488];
    let encoded = framing.encode(&payload).expect("encode");
    let prefix = u32::from_le_bytes(encoded[..4].try_into().unwrap());
    assert!(prefix >= 488 && prefix <= 488 + 15);
    let packet = framing.decode(&encoded).expect("decode complete frame");
    assert_eq!(packet.payload.len(), prefix as usize);
    assert_eq!(packet.consumed, encoded.len());
}

#[test]
fn nested_live_lane_does_not_drop_outer_frame() {
    let mut outer = None;
    let mut inner = None;
    with_live_transport(&mut outer, || {
        assert_eq!(live_stack_depth(), 1);
        with_live_transport(&mut inner, || {
            assert_eq!(live_stack_depth(), 2);
            drop_live_transport();
            assert_eq!(live_stack_depth(), 2);
            assert!(!live_top_occupied());
        });
        assert_eq!(live_stack_depth(), 1);
        assert!(!live_top_occupied());
    });
    assert_eq!(live_stack_depth(), 0);
    assert!(outer.is_none());
    assert!(inner.is_none());
}

#[test]
fn rpc_attempt_budget_reserves_reconnect_window() {
    let twenty = std::time::Duration::from_secs(20);
    let eight = std::time::Duration::from_secs(8);
    let four = std::time::Duration::from_secs(4);
    assert_eq!(
        rpc_attempt_budget(0, false, twenty),
        std::time::Duration::from_secs(18)
    );
    assert_eq!(
        rpc_attempt_budget(0, false, eight),
        std::time::Duration::from_secs(6)
    );
    assert_eq!(
        rpc_attempt_budget(0, false, four),
        std::time::Duration::from_secs(3)
    );
    assert_eq!(rpc_attempt_budget(0, true, eight), eight);
    assert_eq!(rpc_attempt_budget(2, true, eight), eight);
}

#[test]
fn reconnect_backoff_is_bounded() {
    assert_eq!(reconnect_backoff(0), std::time::Duration::ZERO);
    for attempt in [1, 2, 4, 99] {
        let ceiling = 100_u64 << (attempt - 1).min(3);
        for random in [0, 1, 50, 400, u64::MAX] {
            let delay = reconnect_delay(attempt, random).as_millis();
            assert!(delay >= (ceiling / 2) as u128 && delay <= ceiling as u128);
        }
    }
    assert_ne!(reconnect_delay(2, 0), reconnect_delay(2, 99));
}

#[test]
fn timeout_and_live_scopes_unwind_without_leaking_thread_state() {
    let timeout = rpc_timeout_secs();
    let depth = live_stack_depth();
    let result = std::panic::catch_unwind(|| {
        with_rpc_timeout_secs(1, || {
            with_live_transport(&mut None, || panic!("test unwind"));
        });
    });
    assert!(result.is_err());
    assert_eq!(rpc_timeout_secs(), timeout);
    assert_eq!(live_stack_depth(), depth);
}

fn gzip_body(bytes: &[u8]) -> Vec<u8> {
    use std::io::Write;
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
    encoder.write_all(bytes).unwrap();
    encode_boxed_bytes(&GzipPackedConstructor {
        packed_data: encoder.finish().unwrap(),
    })
    .unwrap()
}

#[test]
fn compressed_payloads_are_bounded_and_validate_checksum() {
    let normal = gzip_body(b"bounded payload");
    assert_eq!(ungzip_if_needed(&normal).unwrap(), b"bounded payload");
    let bomb = gzip_body(&vec![0; MAX_UNPACKED_BYTES + 1]);
    assert!(ungzip_if_needed(&bomb).is_err());
    let mut broken = normal;
    broken[8] ^= 0xff;
    assert!(ungzip_if_needed(&broken).is_err());
}

#[test]
fn nested_wrappers_and_short_container_entries_cannot_panic() {
    let mut body = MSGS_ACK.to_le_bytes().to_vec();
    for _ in 0..=MAX_WRAPPER_DEPTH {
        body = gzip_body(&body);
    }
    assert!(parse_service_or_result(&body).is_err());
    let mut container = MSG_CONTAINER.to_le_bytes().to_vec();
    container.extend_from_slice(&1_i32.to_le_bytes());
    container.extend(pack_container_message(3, 1, &[0; 4]));
    assert!(parse_service_or_result(&container).is_err());
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
    assert!(engine
        .fail_request(outbound.message_id, 0, &timeout)
        .is_err());
    assert_eq!(engine.pending_count(), 0);
    assert!(engine.next_outbound().is_none());
    assert!(engine
        .take_response::<RawMethod>(&handle)
        .unwrap()
        .is_none());
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
    assert!(engine
        .receive_result(outbound.message_id - 4, error, 3)
        .is_err());
    assert!(engine
        .take_response::<RawMethod>(&handle)
        .unwrap()
        .is_none());
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
    use tellers_mtproto_crypto::{encrypt_message, Direction, MessageToEncrypt};
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
    assert!(engine
        .open_inbound(&tampered, &FixedClock, true, 1024)
        .is_err());
    let wrong_session = seal(snapshot.session_id ^ 1, server_id + 4, &[3; 12]).unwrap();
    assert!(engine
        .open_inbound(&wrong_session, &FixedClock, true, 1024)
        .is_err());
    let even_id = seal(snapshot.session_id, server_id + 1, &[3; 12]).unwrap();
    assert!(engine
        .open_inbound(&even_id, &FixedClock, true, 1024)
        .is_err());
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

#[test]
fn idle_frame_uses_attempt_deadline_mid_frame_uses_overall() {
    let attempt = std::time::Instant::now();
    let overall = attempt + std::time::Duration::from_secs(8);
    assert_eq!(recv_wait_deadline(attempt, overall, 4, 0), attempt);
    assert_eq!(
        recv_wait_deadline(attempt, overall, 140_004, 139_455),
        overall
    );
    assert!(!is_mid_frame(4, 0));
    assert!(is_mid_frame(140_004, 139_455));
    assert!(is_mid_frame(0, 314));
    assert!(leftover_frame_grace(false, 314));
    assert!(!leftover_frame_grace(true, 314));
    assert!(!leftover_frame_grace(false, 0));
    assert!(idle_after_complete_frames(26364, 4, 0, 0));
    assert!(!idle_after_complete_frames(26364, 4, 0, 0x78d4_dec1));
    assert!(!idle_after_complete_frames(0, 4, 0, 0));
    assert!(!idle_after_complete_frames(100, 140_004, 139_455, 0));
    // history/media: container then rpc_result in the next frame — keep waiting.
    assert!(!idle_after_complete_frames(1448, 4, 0, MSG_CONTAINER));
    assert!(!idle_after_complete_frames(61008, 4, 0, MSG_CONTAINER));
    assert!(idle_empty_first_byte(0, 4, 0));
    assert!(!idle_empty_first_byte(1448, 4, 0));
    // loadMoreChats: recv=0 needed=4 available=0 last_ctor=0x0 on a parked socket.
    assert!(idle_reused_socket(true, 0, 4, 0));
    assert!(!idle_reused_socket(false, 0, 4, 0));
    assert!(fail_fast_idle(true, 0, 4, 0, 0));
    assert!(!fail_fast_idle(false, 0, 4, 0, 0));
    assert!(!fail_fast_idle(false, 61008, 4, 0, MSG_CONTAINER));
    // download media: updates#74ae4240 is not a fail-fast idle; isolation is invokeWithoutUpdates.
    assert!(!idle_after_complete_frames(19687, 4, 0, 0x74ae_4240));
    assert!(!fail_fast_idle(false, 19687, 4, 0, 0x74ae_4240));
    // User log: recv≈60KiB needed=4 last_ctor=msg_container. Probe, do not fail-fast.
    assert!(idle_needs_liveness_probe(59428, 4, 0, MSG_CONTAINER));
    assert!(idle_needs_liveness_probe(62008, 4, 0, MSG_CONTAINER));
    assert!(idle_needs_liveness_probe(61008, 4, 0, MSG_CONTAINER));
    assert!(!idle_needs_liveness_probe(0, 4, 0, MSG_CONTAINER));
    assert!(!idle_needs_liveness_probe(61008, 4, 0, 0));
    assert!(!idle_needs_liveness_probe(
        100,
        140_004,
        139_455,
        MSG_CONTAINER
    ));
    assert!(!fail_fast_idle(false, 59428, 4, 0, MSG_CONTAINER));
}

#[test]
fn user_log_idle_after_container_needs_ping_probe() {
    let media = MtprotoError::Message(
            "RPC timeout recv=59428 needed=4 available=0 prefix=0 last_ctor=0x73f1f8dc via 149.154.167.51:443 reconnect".into(),
        );
    let chats = MtprotoError::Message(
            "RPC timeout recv=62008 needed=4 available=0 prefix=0 last_ctor=0x73f1f8dc via 149.154.167.51:443 reconnect".into(),
        );
    assert!(timeout_idle_needs_probe(&media));
    assert!(timeout_idle_needs_probe(&chats));
    assert!(!timeout_idle_needs_probe(&MtprotoError::Message(
        "RPC timeout recv=0 needed=4 available=0 prefix=0 last_ctor=0x0".into(),
    )));
    assert!(!timeout_idle_needs_probe(&MtprotoError::Message(
        "RPC timeout recv=290".into(),
    )));
    assert_eq!(PingRequest::ID, 0x7abe_77ec);
    let ping = encode_boxed_bytes(&PingRequest { ping_id: 7 }).expect("ping");
    assert_eq!(&ping[..4], &PingRequest::ID.to_le_bytes());
    let now = std::time::Instant::now();
    assert!(!live_transport_stale(now, now));
    assert!(!live_transport_stale(
        now,
        now + std::time::Duration::from_secs(18)
    ));
    assert!(live_transport_stale(
        now,
        now + std::time::Duration::from_secs(KEEPALIVE_PING_SECS)
    ));
    assert_eq!(
        subscribed_read_deadline(now, None).duration_since(now),
        std::time::Duration::from_secs(KEEPALIVE_PING_SECS),
    );
    let ping_sent = now + std::time::Duration::from_secs(2);
    assert_eq!(
        subscribed_read_deadline(now, Some(ping_sent)).duration_since(ping_sent),
        std::time::Duration::from_secs(20),
    );
    let mut inflight = Some(99_i64);
    note_inbound_liveness(&mut inflight);
    assert!(inflight.is_none());
    assert!(!keepalive_probe_failed(None));
    assert!(keepalive_probe_failed(Some(1)));
}

#[test]
fn pong_is_liveness_not_ignored() {
    let mut body = PONG.to_le_bytes().to_vec();
    body.extend_from_slice(&11_i64.to_le_bytes());
    body.extend_from_slice(&99_i64.to_le_bytes());
    let events = parse_service_or_result(&body).expect("pong");
    assert!(events
        .iter()
        .any(|e| matches!(e, InboundEvent::Pong { ping_id: 99 })));
}

#[test]
fn rpc_timeout_message_includes_frame_progress() {
    let mut input = vec![0_u8; 8];
    input[..4].copy_from_slice(&140_000u32.to_le_bytes());
    let message = rpc_timeout_message(139455, &input, 140004, 139455);
    assert!(message.contains("recv=139455"));
    assert!(message.contains("needed=140004"));
    assert!(message.contains("available=139455"));
    assert!(message.contains("prefix=140000"));
}

#[test]
fn rpc_timeout_scoping_and_per_thread() {
    let previous = rpc_timeout_secs();
    let seen = with_rpc_timeout_secs(8, || rpc_timeout_secs());
    assert_eq!(seen, 8);
    assert_eq!(rpc_timeout_secs(), previous.max(1));

    with_rpc_timeout_secs(45, || {
        let other = std::thread::spawn(|| rpc_timeout_secs())
            .join()
            .expect("timeout thread");
        assert_eq!(rpc_timeout_secs(), 45);
        assert_eq!(other, 20);
    });
}

#[test]
fn rotated_endpoints_moves_5222_off_first() {
    let first = rotated_endpoints(2, 0);
    let second = rotated_endpoints(2, 1);
    assert_eq!(first[0], "149.154.167.51:443");
    assert_eq!(second[0], "149.154.167.50:443");
    assert_eq!(first.len(), second.len());
    assert_eq!(first[1], second[0]);
}

#[test]
fn authenticated_retries_stay_on_one_dc2_ip() {
    let addrs = same_ip_endpoints(2);
    assert_eq!(addrs[0], "149.154.167.51:443");
    assert!(addrs.iter().all(|addr| addr.starts_with("149.154.167.51:")));
    assert!(!addrs.iter().any(|addr| addr.contains("149.154.167.50")));
    assert_eq!(rotated_same_ip(2, 1)[0], "149.154.167.51:5222");
    assert_eq!(rotated_same_ip(2, 0)[0], "149.154.167.51:443");
    assert_ne!(rotated_same_ip(2, 1)[0], rotated_same_ip(2, 0)[0]);
}

#[test]
fn extras_stay_on_pinned_dc2_host() {
    let pinned = ["149.154.167.51:443"];
    assert!(!extra_reconnect_same_host(&pinned, "149.154.167.41:443"));
    assert!(!extra_reconnect_same_host(&pinned, "149.154.167.51:443"));
    assert!(!extra_reconnect_same_host(&pinned, "149.154.167.51:5222"));
}

#[test]
fn clock_delta_repairs_300s_skew() {
    let local = 1_700_000_000i64 * 1_000_000;
    let server_msg_id = (1_700_000_400i64) << 32 | 1;
    assert_eq!(clock_delta_micros(server_msg_id, local, 0), 400_000_000);
    let behind = (1_699_999_600i64) << 32 | 1;
    assert_eq!(clock_delta_micros(behind, local, 0), -400_000_000);
}

#[test]
fn eagain_is_waitable_not_dead_transport() {
    assert!(is_waitable_io("connection error: Try again (os error 11)"));
    assert!(!is_transport_error(&MtprotoError::Message(
        "connection error: Try again (os error 11)".into(),
    )));
    assert!(is_transport_error(&MtprotoError::Message(
        "connection reset by peer".into(),
    )));
    assert!(is_transport_error(&MtprotoError::Message(
        "RPC timeout recv=290".into(),
    )));
    assert!(is_transport_error(&MtprotoError::Message(
        "RPC timeout recv=19687 needed=4 available=0 prefix=0 last_ctor=0x74ae4240".into(),
    )));
    assert!(is_transport_error(&MtprotoError::Message(
        "RPC timeout recv=0 needed=4 available=0 prefix=0 last_ctor=0x0".into(),
    )));
    assert!(is_transport_error(&MtprotoError::Message(
        "MALFORMED MTPROTO MESSAGE: PLAIN ENVELOPE LENGTH MISMATCH".into(),
    )));
}

#[test]
fn trim_padded_mtproto_packet_drops_transport_pad() {
    let mut packet = vec![0_u8; 24 + 32 + 7];
    packet[0] = 1;
    let trimmed = trim_padded_mtproto_packet(&packet);
    assert_eq!(trimmed.len(), 56);
    assert_eq!(trimmed.len() % 16, 8);
}
