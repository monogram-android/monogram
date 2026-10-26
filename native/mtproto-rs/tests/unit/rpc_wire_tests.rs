use super::*;
use std::io::Write;
use tellers_mtproto_crypto::{
    AesCtr, Direction, MessageToEncrypt, decrypt_message, encrypt_message,
};

#[test]
fn manual_time_bounds_retries_and_delivers_each_logical_result_once() {
    struct ManualClock(i64);
    impl Clock for ManualClock {
        fn unix_micros(&self) -> i64 {
            self.0
        }
    }
    let initial = 1_700_000_000_000_000;
    let policy = ExponentialBackoff {
        timeout_micros: 10,
        initial_delay_micros: 1,
        max_attempts: 2,
    };
    let mut engine = Engine::new(Snapshot::new(2, &mut OsRandom).unwrap(), policy).unwrap();
    let handle = engine
        .invoke(&RawMethod { body: vec![1; 4] }, &ManualClock(initial))
        .unwrap();
    let first = engine.next_outbound().unwrap();
    let error = tellers_mtproto_engine::Error::Transport(tellers_mtproto_transport::Error::Closed);
    engine
        .fail_request(first.message_id, initial, &error)
        .unwrap();
    assert!(engine.poll(&ManualClock(initial + 1)).unwrap().is_empty());
    let retry = engine.next_outbound().unwrap();
    assert_ne!(first.message_id, retry.message_id);
    assert!(engine.receive_result(first.message_id, vec![0], 5).is_err());
    engine.receive_result(retry.message_id, vec![9], 9).unwrap();
    assert!(engine.receive_result(retry.message_id, vec![9], 9).is_err());
    assert_eq!(
        engine.take_response::<RawMethod>(&handle).unwrap(),
        Some(vec![9])
    );
    assert_eq!(engine.take_response::<RawMethod>(&handle).unwrap(), None);
    assert_eq!(engine.pending_count(), 0);
    assert!(engine.next_outbound().is_none());

    let mut engine = Engine::new(Snapshot::new(2, &mut OsRandom).unwrap(), policy).unwrap();
    engine
        .invoke(&RawMethod { body: vec![1; 4] }, &ManualClock(initial))
        .unwrap();
    engine.next_outbound().unwrap();
    assert!(engine.poll(&ManualClock(initial + 10)).unwrap().is_empty());
    assert!(engine.poll(&ManualClock(initial + 11)).unwrap().is_empty());
    assert!(engine.next_outbound().is_some());
    let failures = engine.poll(&ManualClock(initial + 21)).unwrap();
    assert_eq!(failures.len(), 1);
    assert!(matches!(
        failures[0],
        tellers_mtproto_engine::Error::Timeout { attempts: 2, .. }
    ));
    assert_eq!(engine.pending_count(), 0);
    assert!(engine.poll(&ManualClock(initial + 22)).unwrap().is_empty());
    assert!(engine.next_outbound().is_none());
}

#[test]
fn encrypted_rpc_socket_correlates_results_errors_and_uncertain_disconnects() {
    for case in [
        "success",
        "flood",
        "migrate",
        "auth",
        "pin_permission",
        "leave_creator",
        "disconnect",
        "scheduled_disconnect",
        "tamper",
    ] {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let mut snapshot = Snapshot::new(2, &mut OsRandom).unwrap();
        snapshot.auth_key = Some(vec![7; 256]);
        let session = snapshot.session_id;
        let address = listener.local_addr().unwrap().to_string();
        let mut slot = Some(open_live_addr(2, &address, None, 2).unwrap());
        use tellers_mtproto::latest::api::*;
        let query = match case {
            "pin_permission" => encode_boxed_bytes(&MessagesUpdatePinnedMessageRequest {
                flags: 0,
                silent: None,
                unpin: None,
                pm_oneside: None,
                peer: Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
                id: 7,
            })
            .unwrap(),
            "leave_creator" => encode_boxed_bytes(&ChannelsLeaveChannelRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id: 12,
                    access_hash: 34,
                })),
            })
            .unwrap(),
            "scheduled_disconnect" => {
                let mut request = MessagesSendMessageRequest {
                    flags: 0,
                    no_webpage: None,
                    silent: None,
                    background: None,
                    clear_draft: None,
                    noforwards: None,
                    update_stickersets_order: None,
                    invert_media: None,
                    allow_paid_floodskip: None,
                    peer: Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
                    reply_to: None,
                    message: "synthetic scheduled fixture".into(),
                    random_id: 123,
                    reply_markup: None,
                    entities: None,
                    schedule_date: None,
                    schedule_repeat_period: None,
                    send_as: None,
                    quick_reply_shortcut: None,
                    effect: None,
                    allow_paid_stars: None,
                    suggested_post: None,
                    rich_message: None,
                };
                crate::SendOptions {
                    silent: true,
                    schedule_date: Some(2_000_000_000),
                    ..Default::default()
                }
                .apply_text(&mut request);
                encode_boxed_bytes(&request).unwrap()
            }
            _ => vec![0x12; 4],
        };
        let reconnect_query = encode_boxed_bytes(&MessagesGetScheduledHistoryRequest {
            peer: Box::new(InputPeer::InputPeerSelf(InputPeerSelfConstructor {})),
            hash: 0,
        })
        .unwrap();
        let expected_queries = if case == "scheduled_disconnect" {
            vec![query.clone(), reconnect_query.clone()]
        } else {
            vec![query.clone()]
        };
        let server = std::thread::spawn(move || {
            let mut last_request_id = 0;
            for (round, expected_query) in expected_queries.into_iter().enumerate() {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                    .unwrap();
                socket
                    .set_write_timeout(Some(std::time::Duration::from_secs(2)))
                    .unwrap();
                let mut header = [0; 64];
                socket.read_exact(&mut header).unwrap();
                let mut incoming = AesCtr::new(&header[8..40], &header[40..56]).unwrap();
                let mut decoded_header = header;
                incoming.apply(&mut decoded_header);
                assert_eq!(&decoded_header[56..60], &0xdddddddd_u32.to_le_bytes());
                let mut decoded_request = None;
                for _ in 0..8 {
                    let mut length = [0; 4];
                    socket.read_exact(&mut length).unwrap();
                    incoming.apply(&mut length);
                    let length = u32::from_le_bytes(length) as usize;
                    assert!(length < 4096);
                    let mut packet = vec![0; length];
                    socket.read_exact(&mut packet).unwrap();
                    incoming.apply(&mut packet);
                    let sealed_len = 24 + ((packet.len() - 24) / 16) * 16;
                    let request = decrypt_message(
                        &[7; 256],
                        &packet[..sealed_len],
                        Direction::ClientToServer,
                        session,
                        4096,
                    )
                    .unwrap();
                    if request.body == expected_query {
                        decoded_request = Some(request);
                        break;
                    }
                }
                let request =
                    decoded_request.expect("application request after bounded service traffic");
                last_request_id = request.message_id;
                if case == "disconnect" {
                    return request.message_id;
                }
                if case == "scheduled_disconnect" && round == 0 {
                    drop(socket);
                    continue;
                }
                let result = match case {
                    "flood" => encode_boxed_bytes(&RpcErrorConstructor {
                        error_code: 420,
                        error_message: "FLOOD_WAIT_17".into(),
                    })
                    .unwrap(),
                    "migrate" => encode_boxed_bytes(&RpcErrorConstructor {
                        error_code: 303,
                        error_message: "FILE_MIGRATE_4".into(),
                    })
                    .unwrap(),
                    "auth" => encode_boxed_bytes(&RpcErrorConstructor {
                        error_code: 401,
                        error_message: "SESSION_REVOKED".into(),
                    })
                    .unwrap(),
                    "pin_permission" | "leave_creator" => {
                        encode_boxed_bytes(&RpcErrorConstructor {
                            error_code: if case == "pin_permission" { 403 } else { 400 },
                            error_message: if case == "pin_permission" {
                                "CHAT_ADMIN_REQUIRED"
                            } else {
                                "USER_CREATOR"
                            }
                            .into(),
                        })
                        .unwrap()
                    }
                    _ => vec![0x34; 4],
                };
                let mut body = RPC_RESULT.to_le_bytes().to_vec();
                body.extend_from_slice(&request.message_id.to_le_bytes());
                body.extend_from_slice(&result);
                let padding = vec![3; 12 + (16 - ((32 + body.len() + 12) % 16)) % 16];
                let mut response = encrypt_message(
                    &[7; 256],
                    MessageToEncrypt {
                        server_salt: 0,
                        session_id: session,
                        message_id: ((SystemClock.unix_micros() / 1_000_000) << 32) | 1,
                        sequence: 1,
                        body: &body,
                        padding: &padding,
                    },
                    Direction::ServerToClient,
                )
                .unwrap();
                if case == "tamper" {
                    response[24] ^= 1;
                }
                let mut framed = PaddedIntermediate::default().encode(&response).unwrap();
                let mut reversed = header;
                reversed.reverse();
                let mut outgoing = AesCtr::new(&reversed[8..40], &reversed[40..56]).unwrap();
                outgoing.apply(&mut framed);
                for bytes in framed.chunks(7) {
                    socket.write_all(bytes).unwrap();
                }
            }
            last_request_id
        });
        let result = with_live_transport(&mut slot, || {
            with_rpc_timeout_secs(2, || invoke_raw(&mut snapshot, query))
        });
        if case == "scheduled_disconnect" {
            assert!(
                result.is_err(),
                "lost mutation response must remain uncertain"
            );
            assert!(slot.is_none());
            slot = Some(open_live_addr(2, &address, None, 2).unwrap());
            let history = with_live_transport(&mut slot, || {
                with_rpc_timeout_secs(2, || invoke_raw(&mut snapshot, reconnect_query))
            });
            assert_eq!(history.unwrap(), vec![0x34; 4]);
            assert!(slot.is_some());
            assert!(server.join().unwrap() > 0);
            continue;
        }
        assert!(server.join().unwrap() > 0);
        match case {
            "success" => {
                assert_eq!(result.unwrap(), vec![0x34; 4]);
                assert!(slot.is_some());
            }
            "flood" => {
                assert!(result.unwrap_err().to_string().contains("FLOOD_WAIT_17"));
                assert!(
                    slot.is_some(),
                    "correlated flood invalidated the authenticated socket"
                );
            }
            "pin_permission" | "leave_creator" => {
                let error = result.unwrap_err().to_string();
                assert!(error.contains(if case == "pin_permission" {
                    "CHAT_ADMIN_REQUIRED"
                } else {
                    "USER_CREATOR"
                }));
                assert!(
                    slot.is_some(),
                    "permission rejection must retain the authenticated socket"
                );
            }
            "migrate" => {
                assert!(result.unwrap_err().to_string().contains("FILE_MIGRATE_4"));
                assert!(slot.is_none());
            }
            "auth" => {
                assert!(result.unwrap_err().to_string().contains("SESSION_REVOKED"));
                assert!(slot.is_none());
            }
            _ => {
                assert!(
                    result.is_err(),
                    "uncertain or unauthenticated response accepted"
                );
                assert!(slot.is_none());
            }
        }
    }
}
