use super::updates_session_should_abort;

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
fn rpc_read_loops_resend_untracked_salt_queries() {
    let loops = include_str!("../../src/rpc/invoke.rs")
        .matches("replay_updates_rejection(")
        .count();
    assert_eq!(loops, 3);
}
