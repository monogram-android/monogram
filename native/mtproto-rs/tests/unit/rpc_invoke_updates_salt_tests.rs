use super::updates_session_should_abort;

#[test]
fn streaming_refill_is_sent_before_waiting_for_another_packet() {
    use tellers_mtproto_crypto::{Direction, MessageToEncrypt, encrypt_message};
    use tellers_mtproto_engine::{Engine, ExponentialBackoff};
    use tellers_mtproto_session::{Clock, OsRandom, Snapshot};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let mut transport = super::super::framing::open_live_addr(
        2,
        &listener.local_addr().unwrap().to_string(),
        None,
        1,
    )
    .unwrap();
    let (_peer, _) = listener.accept().unwrap();
    let clock = super::SystemClock;
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    snapshot.auth_key = Some(vec![7; 256]);
    let server_id = ((clock.unix_micros() / 1_000_000) << 32) | 1;
    snapshot.last_message_id = server_id + (1_i64 << 32) - 1;
    let request_id = snapshot.last_message_id + 4;
    let mut body = super::super::inbound::RPC_RESULT.to_le_bytes().to_vec();
    body.extend_from_slice(&request_id.to_le_bytes());
    body.extend_from_slice(&[1; 4]);
    let packet = encrypt_message(
        snapshot.auth_key.as_ref().unwrap(),
        MessageToEncrypt {
            server_salt: 0,
            session_id: snapshot.session_id,
            message_id: server_id,
            sequence: 1,
            body: &body,
            padding: &[3; 16],
        },
        Direction::ServerToClient,
    )
    .unwrap();
    transport
        .input
        .extend_from_slice(&(packet.len() as u32).to_le_bytes());
    transport.input.extend_from_slice(&packet);
    let mut engine = Engine::new(
        snapshot,
        ExponentialBackoff {
            timeout_micros: 5_000_000,
            initial_delay_micros: 0,
            max_attempts: 1,
        },
    )
    .unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(150);
    let mut callbacks = 0;
    let result = super::invoke_batch_until_results_streaming(
        &mut engine,
        &mut transport,
        &[vec![1; 4]],
        &clock,
        deadline,
        deadline,
        deadline,
        false,
        &mut false,
        |index, response| {
            assert_eq!(index, 0);
            assert_eq!(response.unwrap(), &[1; 4]);
            callbacks += 1;
            Some(vec![2; 4])
        },
    );
    assert!(
        result.is_err(),
        "peer deliberately withholds the refill response"
    );
    assert_eq!(callbacks, 1);
    assert!(
        engine.next_outbound().is_none(),
        "refill must be sent before receive blocks"
    );
}

#[test]
fn salt_retry_does_not_abort_updates_session() {
    assert!(!updates_session_should_abort(true));
    assert!(updates_session_should_abort(false));
}

#[test]
fn bad_server_salt_requeues_the_pending_request() {
    use super::SystemClock;
    use tellers_mtproto_engine::{Engine, ExponentialBackoff};
    use tellers_mtproto_session::{OsRandom, Snapshot};
    let mut rng = OsRandom;
    let snapshot = Snapshot::new(2, &mut rng).expect("snapshot");
    let mut engine = Engine::new(
        snapshot,
        ExponentialBackoff {
            timeout_micros: 5_000_000,
            initial_delay_micros: 0,
            max_attempts: 4,
        },
    )
    .expect("engine");
    let handle = engine
        .invoke(
            &super::RawMethod {
                body: vec![1, 2, 3, 4],
            },
            &SystemClock,
        )
        .expect("invoke");
    let original = handle.message_id();
    let _ = engine.next_outbound();
    let resent = super::take_salt_resend(&mut engine, original, &SystemClock).expect("resend");
    assert_eq!(resent.len(), 1);
    assert_ne!(resent[0].message_id, original);
}

#[test]
fn updates_reader_resends_the_future_salt_query() {
    use super::super::framing::send_future_salts;
    use super::SystemClock;
    use tellers_mtproto::transport::GetFutureSaltsRequest;
    use tellers_mtproto_engine::{Engine, ExponentialBackoff};
    use tellers_mtproto_session::{OsRandom, Snapshot};
    use tellers_mtproto_transport::{Connection, Error as TransportError, PaddedIntermediate};
    struct Mem {
        writes: usize,
    }
    impl Connection for Mem {
        fn send(&mut self, _packet: &[u8]) -> Result<(), TransportError> {
            self.writes += 1;
            Ok(())
        }
        fn receive(&mut self, _output: &mut [u8]) -> Result<usize, TransportError> {
            Err(TransportError::Closed)
        }
        fn close(&mut self) -> Result<(), TransportError> {
            Ok(())
        }
    }
    let mut rng = OsRandom;
    let mut snapshot = Snapshot::new(2, &mut rng).expect("snapshot");
    snapshot.auth_key = Some(vec![7; 256]);
    let mut engine = Engine::new(
        snapshot,
        ExponentialBackoff {
            timeout_micros: 5_000_000,
            initial_delay_micros: 0,
            max_attempts: 1,
        },
    )
    .expect("engine");
    let mut conn = Mem { writes: 0 };
    let mut framing = PaddedIntermediate::default();
    let message_id =
        send_future_salts(&mut engine, &mut conn, &mut framing, &SystemClock).expect("send");
    assert!(
        super::replay_updates_rejection(
            &mut engine,
            &mut conn,
            &mut framing,
            &SystemClock,
            message_id,
            None,
        )
        .expect("resend")
    );
    assert_eq!(conn.writes, 2);
    assert_eq!(engine.session.content_sequence, 2);
    let (_, body) = super::super::framing::replay_plaintext(engine.session.session_id, message_id)
        .expect("original query");
    assert_eq!(
        u32::from_le_bytes(body[0..4].try_into().unwrap()),
        GetFutureSaltsRequest::ID
    );
}

#[test]
fn salt_retry_keeps_the_original_request_handle_correlated() {
    use tellers_mtproto_engine::{Engine, ExponentialBackoff};
    use tellers_mtproto_session::{OsRandom, Snapshot};
    let mut engine = Engine::new(
        Snapshot::new(2, &mut OsRandom).unwrap(),
        ExponentialBackoff {
            timeout_micros: 8_000_000,
            initial_delay_micros: 0,
            max_attempts: 3,
        },
    )
    .unwrap();
    let handle = engine
        .invoke(&super::RawMethod { body: vec![1; 4] }, &super::SystemClock)
        .unwrap();
    let _ = engine.next_outbound();
    let resent =
        super::take_salt_resend(&mut engine, handle.message_id(), &super::SystemClock).unwrap();
    assert_ne!(resent[0].message_id, handle.message_id());
    engine
        .receive_result(resent[0].message_id, vec![2; 4], 1)
        .unwrap();
    assert_eq!(
        engine.take_response::<super::RawMethod>(&handle).unwrap(),
        Some(vec![2; 4])
    );
}

#[test]
fn salt_resend_status_probe_uses_the_new_wire_id() {
    use tellers_mtproto::codec::{Decoder, Limits, TlDecode};
    use tellers_mtproto::transport::{MsgsStateReqConstructor, Vector};
    use tellers_mtproto_engine::{Engine, ExponentialBackoff};
    use tellers_mtproto_session::{OsRandom, Snapshot};
    use tellers_mtproto_transport::{Connection, Error, Framing, PaddedIntermediate};
    struct Memory(Vec<u8>);
    impl Connection for Memory {
        fn send(&mut self, bytes: &[u8]) -> Result<(), Error> {
            self.0.extend_from_slice(bytes);
            Ok(())
        }
        fn receive(&mut self, _: &mut [u8]) -> Result<usize, Error> {
            Err(Error::Closed)
        }
        fn close(&mut self) -> Result<(), Error> {
            Ok(())
        }
    }
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    snapshot.auth_key = Some(vec![7; 256]);
    let mut engine = Engine::new(
        snapshot.clone(),
        ExponentialBackoff {
            timeout_micros: 8_000_000,
            initial_delay_micros: 0,
            max_attempts: 3,
        },
    )
    .unwrap();
    let handle = engine
        .invoke(&super::RawMethod { body: vec![1; 4] }, &super::SystemClock)
        .unwrap();
    let _ = engine.next_outbound();
    let now = std::time::Instant::now();
    let mut silent = super::super::main_policy::SilentRequests::new(false);
    silent.observe(&[handle.message_id()], now);
    let mut conn = Memory(Vec::new());
    let mut framing = PaddedIntermediate::default();
    super::replay_updates_rejection(
        &mut engine,
        &mut conn,
        &mut framing,
        &super::SystemClock,
        handle.message_id(),
        Some(&mut silent),
    )
    .unwrap();
    let resent_packet = framing.decode(&conn.0).unwrap().payload;
    let resent = tellers_mtproto_crypto::decrypt_message(
        snapshot.auth_key.as_deref().unwrap(),
        super::super::timeout::trim_padded_mtproto_packet(&resent_packet),
        tellers_mtproto_crypto::Direction::ClientToServer,
        snapshot.session_id,
        1024,
    )
    .unwrap();
    assert_ne!(resent.message_id, handle.message_id());
    silent.observe(&[handle.message_id()], now);
    let due = silent.due(now + std::time::Duration::from_secs(9), false);
    assert_eq!(due, vec![resent.message_id]);
    conn.0.clear();
    super::super::framing::send_state_probe(
        &mut engine,
        &mut conn,
        &mut framing,
        &due,
        &super::SystemClock,
    )
    .unwrap();
    let packet = framing.decode(&conn.0).unwrap().payload;
    let message = tellers_mtproto_crypto::decrypt_message(
        snapshot.auth_key.as_deref().unwrap(),
        super::super::timeout::trim_padded_mtproto_packet(&packet),
        tellers_mtproto_crypto::Direction::ClientToServer,
        snapshot.session_id,
        1024,
    )
    .unwrap();
    let mut decoder = Decoder::new(&message.body, Limits::default()).unwrap();
    assert_eq!(decoder.read_u32().unwrap(), MsgsStateReqConstructor::ID);
    let request = MsgsStateReqConstructor::decode(&mut decoder).unwrap();
    let Vector::Vector(ids) = *request.msg_ids;
    assert_eq!(ids.field_1, vec![resent.message_id]);
    engine
        .receive_result(resent.message_id, vec![2; 4], 1)
        .unwrap();
    assert_eq!(
        engine.take_response::<super::RawMethod>(&handle).unwrap(),
        Some(vec![2; 4])
    );
    silent.observe(&[], now);
    assert!(silent.due(now, true).is_empty());
}
