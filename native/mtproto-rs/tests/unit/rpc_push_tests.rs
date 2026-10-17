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
    use tellers_mtproto_crypto::{Direction, MessageToEncrypt, encrypt_message};
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
fn push_reader_drains_already_complete_buffered_frames() {
    use tellers_mtproto::latest::api::UpdatesTooLongConstructor;
    use tellers_mtproto_crypto::{Direction, MessageToEncrypt, encrypt_message};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let mut transport =
        open_live_addr(2, &listener.local_addr().unwrap().to_string(), None, 1).unwrap();
    let (_peer, _) = listener.accept().unwrap();
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    snapshot.auth_key = Some(vec![7; 256]);
    let body = UpdatesTooLongConstructor::ID.to_le_bytes().to_vec();
    let base_id = ((SystemClock.unix_micros() / 1_000_000) << 32) | 1;
    for (offset, message_id) in [base_id, base_id + 4].into_iter().enumerate() {
        let packet = encrypt_message(
            snapshot.auth_key.as_ref().unwrap(),
            MessageToEncrypt {
                server_salt: 0,
                session_id: snapshot.session_id,
                message_id,
                sequence: 1 + offset as i32,
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
    }
    let mut slot = Some(transport);
    let updates = with_live_transport(&mut slot, || receive_updates(&mut snapshot)).unwrap();
    assert_eq!(updates, vec![body.clone(), body]);
    assert!(slot.is_some());
    assert!(snapshot.received_message_ids.contains_key(&base_id));
    assert!(snapshot.received_message_ids.contains_key(&(base_id + 4)));
    assert!(slot.as_ref().unwrap().input.is_empty());
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
        assert!(
            supervisor
                .backoff(std::time::Duration::from_secs(60))
                .is_err()
        );
        assert_eq!(supervisor.state, ConnectionState::Disconnected);
        assert!(
            supervisor
                .connect(|| panic!("cancelled connection opened"))
                .is_err()
        );
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
        assert!(
            !supervisor
                .connect(|| open_live_addr(2, &addr, None, 1))
                .unwrap()
        );
        assert_eq!(supervisor.state, ConnectionState::Handshaking);
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(std::time::Duration::from_secs(1)))
            .unwrap();
        peer.read_exact(&mut [0; 64]).unwrap();
        supervisor.backoff(std::time::Duration::ZERO).unwrap();
        assert_eq!(peer.read(&mut [0; 1]).unwrap(), 0);
        assert!(
            !supervisor
                .connect(|| open_live_addr(2, &addr, None, 1))
                .unwrap()
        );
        supervisor.park_ready();
        assert_eq!(supervisor.state, ConnectionState::Ready);
    });
    assert!(slot.is_some());
    with_live_transport(&mut slot, || {
        let mut supervisor = ConnectionSupervisor::acquire(2).unwrap();
        assert!(
            supervisor
                .connect(|| panic!("healthy connection replaced"))
                .unwrap()
        );
        // Error/unwind drops the unparked transport and releases the lane.
    });
    assert!(slot.is_none());
}

#[test]
fn supervisor_unwind_releases_retry_owner() {
    let mut slot = None;
    with_live_transport(&mut slot, || {
        assert!(
            std::panic::catch_unwind(|| {
                let _owner = ConnectionSupervisor::acquire(2).unwrap();
                panic!("test unwind");
            })
            .is_err()
        );
        assert!(ConnectionSupervisor::acquire(2).is_ok());
    });
}
