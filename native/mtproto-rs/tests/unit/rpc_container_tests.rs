use super::*;

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
    assert!(
        events
            .iter()
            .any(|e| matches!(e, InboundEvent::RpcResult { req_msg_id: 7, .. }))
    );
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
fn msg_copy_extracts_inner_rpc_result() {
    let rpc = pack_rpc_result(5, &[0x22, 0x00, 0x00, 0x00]);
    let mut body = MSG_COPY.to_le_bytes().to_vec();
    body.extend(pack_container_message(8, 1, &rpc));
    let events = parse_service_or_result(&body).expect("msg_copy");
    assert!(
        events
            .iter()
            .any(|e| matches!(e, InboundEvent::RpcResult { req_msg_id: 5, .. }))
    );
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
    assert!(is_waitable_io(
        "connection error: connection timed out (os error 10060)"
    ));
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
