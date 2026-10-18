use super::*;
use std::collections::VecDeque;
use tellers_mtproto_crypto::{Direction, MessageToEncrypt, decrypt_message, encrypt_message};

struct FixedClock;
impl Clock for FixedClock {
    fn unix_micros(&self) -> i64 { 1_700_000_000_000_000 }
}

struct BindPeer {
    session: i64,
    mode: &'static str,
    input: VecDeque<u8>,
    requests: usize,
}

impl Connection for BindPeer {
    fn send(&mut self, packet: &[u8]) -> Result<(), tellers_mtproto_transport::Error> {
        self.requests += 1;
        let frame = PaddedIntermediate::default().decode(packet).unwrap();
        let request = decrypt_message(&[4; 256], &crate::rpc::trim_padded_mtproto_packet(&frame.payload), Direction::ClientToServer, self.session, 4096).unwrap();
        let mut decoder = Decoder::new(&request.body, Limits::default()).unwrap();
        assert_eq!(decoder.read_u32().unwrap(), tellers_mtproto::latest::api::AuthBindTempAuthKeyRequest::ID);
        let permanent_id = i64::decode(&mut decoder).unwrap();
        let nonce = i64::decode(&mut decoder).unwrap();
        let expires = i32::decode(&mut decoder).unwrap();
        let encrypted = decoder.read_bytes().unwrap();
        decoder.finish().unwrap();
        let permanent = [3; 256];
        assert_eq!(permanent_id, auth_key_id(&permanent));
        assert_eq!(i64::from_le_bytes(encrypted[..8].try_into().unwrap()), permanent_id);
        let message_key = &encrypted[8..24];
        // Derive v1 keys from the documented byte ranges, independently of v1_aes.
        let a = sha1(&[message_key, &permanent[..32]].concat());
        let b = sha1(&[&permanent[32..48], message_key, &permanent[48..64]].concat());
        let c = sha1(&[&permanent[64..96], message_key].concat());
        let d = sha1(&[message_key, &permanent[96..128]].concat());
        let key: [u8; 32] = [&a[..8], &b[8..20], &c[4..16]].concat().try_into().unwrap();
        let iv: [u8; 32] = [&a[8..20], &b[..8], &c[16..20], &d[..8]].concat().try_into().unwrap();
        let plain = aes_ige_decrypt(&encrypted[24..], &key, &iv).unwrap();
        assert_eq!(&sha1(&plain[..72])[4..20], message_key);
        assert_eq!(i64::from_le_bytes(plain[16..24].try_into().unwrap()), request.message_id);
        assert_eq!(&plain[24..28], &0_i32.to_le_bytes());
        assert_eq!(&plain[28..32], &40_i32.to_le_bytes());
        assert_eq!(&plain[32..36], &BindAuthKeyInnerConstructor::ID.to_le_bytes());
        assert_eq!(&plain[36..44], &nonce.to_le_bytes());
        assert_eq!(&plain[44..52], &auth_key_id(&[4; 256]).to_le_bytes());
        assert_eq!(&plain[52..60], &permanent_id.to_le_bytes());
        assert_eq!(&plain[60..68], &self.session.to_le_bytes());
        assert_eq!(&plain[68..72], &expires.to_le_bytes());
        let id = request.message_id + if self.mode == "wrong_request" { 4 } else { 0 };
        let mut body = result_body(id, if self.mode == "false" { BOOL_FALSE } else { BOOL_TRUE });
        if self.mode == "error" {
            body.truncate(12);
            body.extend_from_slice(&crate::rpc::encode_boxed_bytes(&tellers_mtproto::transport::RpcErrorConstructor {
                error_code: 400, error_message: "ENCRYPTED_MESSAGE_INVALID".into(),
            }).unwrap());
        }
        if matches!(self.mode, "container" | "duplicate_service" | "duplicate_in_container") {
            let mut service = 0x9ec2_0908_u32.to_le_bytes().to_vec();
            service.extend_from_slice(&request.message_id.to_le_bytes());
            service.extend_from_slice(&9_i64.to_le_bytes());
            service.extend_from_slice(&123_i64.to_le_bytes());
            if self.mode == "duplicate_in_container" {
                let mut replay = service.clone();
                replay[20..28].copy_from_slice(&999_i64.to_le_bytes());
                body = container(&[service, replay, body]);
                let first_id = body[8..16].to_vec();
                body[52..60].copy_from_slice(&first_id);
            } else {
                body = container(&[service, body]);
            }
        } else if matches!(self.mode, "duplicate_result" | "retired_result" | "future_child" | "negative_child") {
            body = container(&[body]);
            if self.mode == "future_child" {
                body[8..16].copy_from_slice(&(request.message_id + 37).to_le_bytes());
            }
            if self.mode == "negative_child" {
                body[16..20].copy_from_slice(&(-1_i32).to_le_bytes());
            }
        }
        let padding = vec![7; v2_pad_len(body.len())];
        let mut reply = encrypt_message(&[4; 256], MessageToEncrypt {
            server_salt: 456,
            session_id: self.session + i64::from(self.mode == "wrong_session"),
            message_id: request.message_id + 33 + if self.mode == "stale" { 40_i64 << 32 } else { 0 },
            sequence: 1,
            body: &body, padding: &padding,
        }, Direction::ServerToClient).unwrap();
        if self.mode == "corrupt" { reply[24] ^= 1; }
        self.input.extend(PaddedIntermediate::default().encode(&reply).unwrap());
        Ok(())
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, tellers_mtproto_transport::Error> {
        let n = 7.min(output.len()).min(self.input.len());
        for byte in &mut output[..n] { *byte = self.input.pop_front().unwrap(); }
        Ok(n)
    }

    fn close(&mut self) -> Result<(), tellers_mtproto_transport::Error> { Ok(()) }
}

fn result_body(request: i64, result: u32) -> Vec<u8> {
    [RPC_RESULT.to_le_bytes().as_slice(), &request.to_le_bytes(), &result.to_le_bytes()].concat()
}

fn container(children: &[Vec<u8>]) -> Vec<u8> {
    let mut out = MSG_CONTAINER.to_le_bytes().to_vec();
    out.extend_from_slice(&(children.len() as u32).to_le_bytes());
    for (index, child) in children.iter().enumerate() {
        let child_id = ((FixedClock.unix_micros() / 1_000_000) << 32) + 1 + 4 * index as i64;
        out.extend_from_slice(&child_id.to_le_bytes());
        out.extend_from_slice(&1_i32.to_le_bytes());
        out.extend_from_slice(&(child.len() as u32).to_le_bytes());
        out.extend_from_slice(child);
    }
    out
}

fn bind_response(body: &[u8], request: i64) -> Result<BindResponse, MtprotoError> {
    let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
    super::bind_response(body, request, &mut snapshot, &FixedClock, i64::MAX)
}

#[test]
fn authenticated_binding_children_preserve_replay_and_acknowledgement_state() {
    let base = (FixedClock.unix_micros() / 1_000_000) << 32;
    for mode in ["container", "duplicate_service", "duplicate_in_container", "duplicate_result", "retired_result"] {
        let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
        snapshot.auth_key = Some(vec![4; 256]);
        if matches!(mode, "duplicate_service" | "duplicate_result") {
            snapshot.register_received_message(base + 1, true, &FixedClock, false).unwrap();
            snapshot.take_acknowledgements(usize::MAX);
        }
        if mode == "retired_result" {
            snapshot.register_received_message(base + 1, true, &FixedClock, false).unwrap();
            snapshot.take_acknowledgements(usize::MAX);
            for index in 0..tellers_mtproto_session::RECEIVED_MESSAGE_WINDOW {
                snapshot.register_received_message(base + 7 + 4 * index as i64, false, &FixedClock, false).unwrap();
            }
        }
        let mut peer = BindPeer { session: snapshot.session_id, mode, input: VecDeque::new(), requests: 0 };
        let result = bind_temporary_key(&mut peer, &mut PaddedIntermediate::default(), &mut snapshot, &[3; 256], 1_700_086_400, &FixedClock);
        assert_eq!(result.is_ok(), !matches!(mode, "duplicate_result" | "retired_result"), "{mode}");
        assert!(snapshot.received_message_ids.contains_key(&(base + 33)), "outer must be fresh: {mode}");
        let acknowledgements = snapshot.take_acknowledgements(usize::MAX);
        assert!(acknowledgements.contains(&(base + 33)));
        if mode == "retired_result" {
            assert!(!snapshot.received_message_ids.contains_key(&(base + 1)));
            assert!(!acknowledgements.contains(&(base + 1)));
        } else {
            assert!(acknowledgements.contains(&(base + 1)), "child ACK/re-ACK: {mode}");
        }
        if mode == "duplicate_service" {
            assert_eq!(snapshot.server_salt, 0);
            assert!(acknowledgements.contains(&(base + 5)));
        }
        if matches!(mode, "container" | "duplicate_in_container") {
            assert_eq!(snapshot.server_salt, 123);
            assert!(acknowledgements.contains(&(base + if mode == "container" { 5 } else { 9 })));
        }
    }
}

#[test]
fn binding_authenticates_both_envelopes_and_rejects_invalid_replies() {
    for mode in ["true", "false", "error", "wrong_request", "wrong_session", "corrupt", "stale", "container", "future_child", "negative_child"] {
        let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
        snapshot.auth_key = Some(vec![4; 256]);
        let mut peer = BindPeer { session: snapshot.session_id, mode, input: VecDeque::new(), requests: 0 };
        let result = bind_temporary_key(&mut peer, &mut PaddedIntermediate::default(), &mut snapshot, &[3; 256], 1_700_086_400, &FixedClock);
        assert_eq!(result.is_ok(), matches!(mode, "true" | "container"), "case {mode}");
        assert_eq!(peer.requests, 1);
        if mode == "container" { assert_eq!(snapshot.server_salt, 123); }
        if mode == "error" { assert!(result.unwrap_err().to_string().contains("ENCRYPTED_MESSAGE_INVALID")); }
    }
}

#[test]
fn binding_deadline_and_client_close_stop_a_stalled_peer() {
    use std::io::Read;
    for cancel in [false, true] {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
            socket.read_exact(&mut [0; 64]).unwrap();
            socket.read_exact(&mut [0; 1]).unwrap();
            let _ = started_tx.send(());
            let mut buffer = [0; 4096];
            while socket.read(&mut buffer).unwrap_or(0) > 0 {}
        });
        let control = std::sync::Arc::new(crate::tcp::ConnectionControl {
            config: monogram_mtproto_transport::TransportConfig::default(),
            ..Default::default()
        });
        let closing = control.clone();
        let closer = std::thread::spawn(move || {
            started_rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap();
            if cancel { closing.close(); }
        });
        let started = std::time::Instant::now();
        let result = crate::tcp::with_connection_control(&control, || {
            let mut socket = crate::tcp::connect_obfuscated_timeout(&address, 1).unwrap();
            let mut connection = DeadlineConnection {
                inner: &mut socket,
                deadline: std::time::Instant::now() + std::time::Duration::from_millis(100),
            };
            let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
            snapshot.auth_key = Some(vec![4; 256]);
            bind_temporary_key(&mut connection, &mut PaddedIntermediate::default(), &mut snapshot, &[3; 256], 1_700_086_400, &FixedClock)
        });
        assert!(result.is_err());
        assert!(started.elapsed() < std::time::Duration::from_secs(2));
        closer.join().unwrap();
        server.join().unwrap();
    }
}

#[test]
fn bind_containers_validate_all_siblings_and_bound_depth_and_count() {
    let valid = result_body(100, BOOL_TRUE);
    assert!(bind_response(&valid, 100).unwrap().accepted);
    let mut trailing = valid.clone();
    trailing.extend_from_slice(&0_u32.to_le_bytes());
    assert!(bind_response(&trailing, 100).is_err());
    let mut malformed_sibling = container(&[valid.clone()]);
    malformed_sibling[4..8].copy_from_slice(&2_u32.to_le_bytes());
    assert!(bind_response(&malformed_sibling, 100).is_err());
    let mut too_deep = valid.clone();
    for depth in 0..9 {
        too_deep = container(&[too_deep]);
        let id = ((FixedClock.unix_micros() / 1_000_000) << 32) + 1 + 4 * depth;
        too_deep[8..16].copy_from_slice(&id.to_le_bytes());
    }
    assert!(bind_response(&too_deep, 100).is_err());
    assert!(bind_response(&container(&vec![valid; 1024]), 100).is_err());
}
