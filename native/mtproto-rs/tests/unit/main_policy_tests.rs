use super::*;

#[test]
fn silent_requests_probe_once_before_retry_and_correlate_status() {
    let now = Instant::now();
    let mut main = SilentRequests::new(false);
    main.observe(&[12], now);
    assert!(main.due(now + Duration::from_secs(7), false).is_empty());
    assert_eq!(main.due(now + Duration::from_secs(8), false), vec![12]);
    main.probed(24, vec![12]);
    assert!(main.due(now + Duration::from_secs(30), true).is_empty());
    assert!(main.status(99, &[2]).is_ok());
    assert!(main.status(24, &[4 | 32]).is_ok());
    let mut media = SilentRequests::new(true);
    media.observe(&[16], now);
    assert!(media.due(now + Duration::from_secs(29), false).is_empty());
    assert_eq!(media.due(now + Duration::from_secs(30), false), vec![16]);
    media.probed(28, vec![16]);
    assert!(media.status(28, &[2]).is_err());
}

#[test]
fn completed_requests_are_excluded_and_status_lengths_must_match() {
    let now = Instant::now();
    let mut requests = SilentRequests::new(false);
    requests.observe(&[12, 16], now);
    requests.observe(&[16], now);
    assert_eq!(requests.due(now, true), vec![16]);
    requests.probed(24, vec![16]);
    assert!(requests.status(24, &[]).is_err());
}

#[test]
fn probe_is_a_non_content_service_message_with_the_original_request_ids() {
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
            max_attempts: 1,
        },
    )
    .unwrap();
    let mut memory = Memory(Vec::new());
    let mut framing = PaddedIntermediate::default();
    let id = super::super::framing::send_state_probe(
        &mut engine,
        &mut memory,
        &mut framing,
        &[12, 16],
        &super::super::SystemClock,
    )
    .unwrap();
    let packet = framing.decode(&memory.0).unwrap().payload;
    let message = tellers_mtproto_crypto::decrypt_message(
        snapshot.auth_key.as_deref().unwrap(),
        super::super::timeout::trim_padded_mtproto_packet(&packet),
        tellers_mtproto_crypto::Direction::ClientToServer,
        snapshot.session_id,
        1024,
    )
    .unwrap();
    assert_eq!(message.message_id, id);
    assert_eq!(message.sequence & 1, 0);
    let mut decoder = Decoder::new(&message.body, Limits::default()).unwrap();
    assert_eq!(decoder.read_u32().unwrap(), MsgsStateReqConstructor::ID);
    let request = MsgsStateReqConstructor::decode(&mut decoder).unwrap();
    let Vector::Vector(ids) = *request.msg_ids;
    assert_eq!(ids.field_1, vec![12, 16]);
    assert_eq!(engine.pending_count(), 0);
}
