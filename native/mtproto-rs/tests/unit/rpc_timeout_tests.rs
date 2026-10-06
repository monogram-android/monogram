use super::*;

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
    assert!(
        events
            .iter()
            .any(|e| matches!(e, InboundEvent::Pong { ping_id: 99 }))
    );
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
