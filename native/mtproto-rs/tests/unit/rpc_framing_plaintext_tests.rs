use super::{SystemClock, replay_plaintext, resend_plaintext, send_future_salts};
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

#[test]
fn future_salt_query_is_resent_after_bad_server_salt() {
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
    let clock = SystemClock;
    let message_id = send_future_salts(&mut engine, &mut conn, &mut framing, &clock).expect("send");
    assert_eq!(conn.writes, 1);
    let session = engine.session.session_id;
    let (content_related, body) = replay_plaintext(session, message_id).expect("stored query");
    assert!(content_related);
    assert_eq!(
        u32::from_le_bytes(body[0..4].try_into().unwrap()),
        GetFutureSaltsRequest::ID
    );
    for id in 0..80 {
        super::remember_plaintext(session.wrapping_add(1), id, false, vec![1]);
    }
    assert_eq!(engine.session.content_sequence, 1);
    assert!(
        resend_plaintext(&mut engine, &mut conn, &mut framing, &clock, message_id).expect("resend")
    );
    assert_eq!(conn.writes, 2);
    assert_eq!(engine.session.content_sequence, 2);
    assert!(!resend_plaintext(&mut engine, &mut conn, &mut framing, &clock, 1).expect("miss"));
    assert_eq!(conn.writes, 2);
}
