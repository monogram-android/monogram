use super::*;

#[test]
fn channel_recovery_keeps_every_server_hint_and_only_the_open_channel() {
    let first = peers::chat_id_for_channel(1);
    let second = peers::chat_id_for_channel(2);
    let third = peers::chat_id_for_channel(3);
    let mut queue = VecDeque::new();
    prepare_channel_recovery(&mut queue, vec![first, second, first], third, 100);
    let targets: Vec<_> = queue.iter().map(|entry| entry.chat_id).collect();
    assert_eq!(targets.len(), 3);
    for id in [first, second, third] {
        assert!(targets.contains(&id));
    }
    let mut empty = VecDeque::new();
    prepare_channel_recovery(&mut empty, Vec::new(), 42, 100);
    assert!(empty.is_empty());
}

#[test]
fn channel_recovery_rotates_pages_and_respects_final_timeout() {
    let first = peers::chat_id_for_channel(1);
    let second = peers::chat_id_for_channel(2);
    let mut queue = VecDeque::new();
    prepare_channel_recovery(&mut queue, vec![first, second], first, 100);
    let entry = queue.pop_front().unwrap();
    finish_channel_recovery(&mut queue, entry, false, Some(60), first, 100);
    assert_eq!(queue.front().unwrap().chat_id, second);
    assert_eq!(queue.back().unwrap().due_at, 100);
    let entry = queue.pop_front().unwrap();
    finish_channel_recovery(&mut queue, entry, true, Some(60), first, 100);
    assert_eq!(queue.len(), 1);
    let entry = queue.pop_front().unwrap();
    finish_channel_recovery(&mut queue, entry, true, Some(60), first, 100);
    prepare_channel_recovery(&mut queue, Vec::new(), first, 101);
    assert_eq!(queue.front().unwrap().due_at, 160);
    prepare_channel_recovery(&mut queue, vec![first], first, 102);
    assert_eq!(queue.front().unwrap().due_at, 102);
    assert!(!queue.front().unwrap().watching);
}

#[test]
fn channel_recovery_survives_restart_and_closed_view() {
    let first = peers::chat_id_for_channel(1);
    let second = peers::chat_id_for_channel(2);
    let mut queue = VecDeque::new();
    prepare_channel_recovery(&mut queue, vec![first], second, 100);
    let encoded = serde_json::to_vec(&queue).unwrap();
    let mut restored = serde_json::from_slice(&encoded).unwrap();
    prepare_channel_recovery(&mut restored, Vec::new(), 0, 101);
    assert_eq!(restored.len(), 1);
    assert_eq!(restored.front().unwrap().chat_id, first);
}

#[test]
fn begin_updates_preserves_the_unapplied_cursor_in_persistence() {
    let path = std::env::temp_dir().join(format!(
        "monogram-start-updates-{}-{}.json",
        std::process::id(),
        recovery_now()
    ));
    let snapshot = Snapshot::new(DEFAULT_DC_ID, &mut OsRandom).expect("snapshot");
    let mut state = ClientState {
        api_id: 1,
        api_hash: "hash".into(),
        session_path: path.clone(),
        snapshot,
        user_id: None,
        peers: HashMap::new(),
        updates: None,
        media: MediaIndex::new(),
        updates_started: false,
        channel_pts: HashMap::new(),
        channel_recovery: VecDeque::new(),
        seen_messages: HashSet::new(),
        logout_tokens: Vec::new(),
        new_session: None,
        session_dead: false,
        session_dead_reason: None,
        test_dc: false,
        last_inline: None,
    };

    begin_updates(&mut state);
    persist(&state).expect("persist start marker");
    let restored = FileSessionStore::new(&path)
        .load()
        .expect("load start marker")
        .expect("session");

    assert!(state.updates_started);
    assert!(restored.updates.is_none());
    let _ = std::fs::remove_file(path);
}

#[test]
fn clearing_active_dialog_keeps_server_recovery_hints() {
    let path = std::env::temp_dir().join(format!(
        "monogram-clear-dialog-{}-{}.json",
        std::process::id(),
        recovery_now()
    ));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let watched = peers::chat_id_for_channel(1);
    let server_hint = peers::chat_id_for_channel(2);
    let client = get_client(handle).expect("client");
    {
        let mut data = client.data.lock();
        data.last_history_chat_id = watched;
        data.channel_recovery = VecDeque::from([
            ChannelRecovery {
                chat_id: watched,
                due_at: 1,
                watching: true,
            },
            ChannelRecovery {
                chat_id: server_hint,
                due_at: 2,
                watching: false,
            },
        ]);
    }

    clear_active_dialog(handle).expect("clear active dialog");
    let restored = FileSessionStore::new(&path)
        .load()
        .expect("load cleared dialog")
        .expect("session");

    assert_eq!(client.data.lock().last_history_chat_id, 0);
    assert_eq!(restored.channel_recovery.len(), 1);
    assert_eq!(restored.channel_recovery[0].chat_id, server_hint);
    assert!(!restored.channel_recovery[0].watching);
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}

#[test]
fn channel_recovery_failure_retains_hint_without_blocking_other_channels() {
    let first = peers::chat_id_for_channel(1);
    let second = peers::chat_id_for_channel(2);
    let mut queue = VecDeque::new();
    prepare_channel_recovery(&mut queue, vec![first, second], 0, 100);
    let entry = queue.pop_front().unwrap();
    defer_channel_recovery(
        &mut queue,
        entry,
        &MtprotoError::Message("RPC 420: FLOOD_WAIT_60".into()),
        100,
    );
    assert_eq!(queue.front().unwrap().chat_id, second);
    assert_eq!(queue.back().unwrap().due_at, 160);
    prepare_channel_recovery(&mut queue, vec![first], 0, 101);
    assert_eq!(queue.back().unwrap().due_at, 160);
}

#[test]
fn drop_updates_transport_unknown_handle_is_ok() {
    drop_updates_transport(0);
}

#[test]
fn duplicated_auth_key_is_unrecoverable() {
    assert!(is_unrecoverable_session(&MtprotoError::Message(
        "RPC 406: AUTH_KEY_DUPLICATED".into(),
    )));
    assert!(!is_unrecoverable_session(&MtprotoError::Message(
        "RPC timeout recv=139455".into(),
    )));
}

#[test]
fn password_required_normalizes_tl_and_text_errors() {
    assert!(is_password_required(&MtprotoError::PasswordRequired));
    assert!(is_password_required(&MtprotoError::Message(
        "RPC 401: SESSION_PASSWORD_NEEDED".into(),
    )));
    assert!(!is_password_required(&MtprotoError::Message(
        "RPC 400: PASSWORD_HASH_INVALID".into(),
    )));
}

#[test]
fn file_reference_retry_requires_new_bytes() {
    let old = media_rpc::MediaLocation::Photo {
        id: 1,
        access_hash: 2,
        file_reference: vec![1, 2, 3],
        thumb_size: "m".into(),
        dc_id: 2,
    };
    let same = old.clone();
    let mut newer = old.clone();
    if let media_rpc::MediaLocation::Photo { file_reference, .. } = &mut newer {
        *file_reference = vec![9, 9, 9];
    }
    assert_eq!(
        media_rpc::location_token(&old),
        media_rpc::location_token(&same),
    );
    assert_ne!(
        media_rpc::location_token(&old),
        media_rpc::location_token(&newer),
    );
    assert!(media_rpc::is_file_reference_error(&MtprotoError::Message(
        "FILE_REFERENCE_EXPIRED".into(),
    )));
    // Retry is one getFile after source refresh, not gated on token equality.
}

#[test]
fn peer_refreshable_errors_are_detected() {
    assert!(is_peer_refreshable(&MtprotoError::Message(
        "unknown peer -100123; refresh chats first".into(),
    )));
    assert!(is_peer_refreshable(&MtprotoError::Message(
        "RPC 400: PEER_ID_INVALID".into(),
    )));
    assert!(!is_peer_refreshable(&MtprotoError::Message(
        "RPC timeout".into()
    )));
}

#[test]
fn updates_yield_to_main_rpc_and_media_keep_distinct_sessions() {
    let path = std::env::temp_dir().join(format!("monogram-lane-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    let main = client.main.lock();
    let main_id = main.snapshot.session_id;
    assert!(drain_updates(handle).unwrap().is_empty());
    let media_id = client.media[0].io.lock().snapshot.session_id;
    // Distinct session per lane, whatever the configured lane count.
    let media_ids: Vec<i64> = client
        .media
        .iter()
        .map(|lane| lane.io.lock().snapshot.session_id)
        .collect();
    drop(main);
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
    assert_ne!(main_id, media_id);
    let mut unique = media_ids.clone();
    unique.sort_unstable();
    unique.dedup();
    assert_eq!(
        unique.len(),
        media_ids.len(),
        "media lanes share a session: {media_ids:?}"
    );
}

#[test]
fn updates_drain_uses_main_gate_between_home_media_batches() {
    let path =
        std::env::temp_dir().join(format!("monogram-updates-gate-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    let media_batch = client
        .main_gate
        .acquire(crate::scheduler::RequestClass::InteractiveMedia)
        .expect("media batch gate");
    let (started_tx, started_rx) = std::sync::mpsc::channel();
    let (admitted_tx, admitted_rx) = std::sync::mpsc::channel();
    let (release_tx, release_rx) = std::sync::mpsc::channel();
    let updates_client = client.clone();
    let updates = std::thread::spawn(move || {
        started_tx.send(()).expect("updates thread start");
        let _updates_lane = lock_updates_lane(&updates_client)
            .expect("updates lane")
            .expect("idle transport");
        admitted_tx.send(()).expect("updates lane admitted");
        release_rx.recv().expect("release updates lane");
    });
    started_rx.recv().expect("updates thread started");
    assert!(
        admitted_rx
            .recv_timeout(std::time::Duration::from_millis(50))
            .is_err(),
        "update drain must wait for the home-media batch"
    );
    drop(media_batch);
    admitted_rx
        .recv_timeout(std::time::Duration::from_secs(1))
        .expect("updates lane admitted after media batch");
    assert!(
        client
            .main_gate
            .try_acquire(crate::scheduler::RequestClass::InteractiveMedia)
            .is_none(),
        "a later media batch must wait for the admitted updates drain"
    );
    release_tx.send(()).expect("release updates lane");
    updates.join().expect("updates thread");
    assert!(
        client
            .main_gate
            .try_acquire(crate::scheduler::RequestClass::InteractiveMedia)
            .is_some(),
        "media must resume after updates releases the main gate"
    );
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}

#[test]
fn logout_clears_unauthorized_local_session_without_network() {
    let path = std::env::temp_dir().join(format!("monogram-logout-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    {
        let mut io = client.main.lock();
        io.snapshot.auth_key = Some(vec![7; 256]);
    }
    let state = ClientState {
        api_id: 1,
        api_hash: "hash".into(),
        session_path: path.clone(),
        snapshot: client.main.lock().snapshot.clone(),
        user_id: None,
        peers: HashMap::new(),
        updates: None,
        media: MediaIndex::new(),
        updates_started: false,
        channel_pts: HashMap::new(),
        channel_recovery: VecDeque::new(),
        seen_messages: HashSet::new(),
        logout_tokens: Vec::new(),
        new_session: None,
        session_dead: false,
        session_dead_reason: None,
        test_dc: false,
        last_inline: None,
    };
    persist(&state).expect("seed session");
    assert!(path.exists());

    logout(handle).expect("local logout");
    assert!(!path.exists());
    assert!(!is_authorized(handle).expect("authorization state"));
    destroy_client(handle);
}

#[test]
fn fork_session_keeps_auth_and_new_session_id() {
    let mut home = Snapshot::new(2, &mut OsRandom).expect("home");
    home.auth_key = Some(vec![7_u8; 256]);
    home.server_salt = 99;
    home.time_offset_micros = 1_000;
    let forked = fork_session(&home);
    assert_eq!(forked.dc_id, 2);
    assert_eq!(forked.auth_key.as_ref().map(|k| k.len()), Some(256));
    assert_eq!(forked.server_salt, 99);
    assert_eq!(forked.time_offset_micros, 1_000);
    assert_ne!(forked.session_id, home.session_id);
}

#[test]
fn prefer_newer_cursor_keeps_higher_pts() {
    let older = UpdatesStateDto {
        pts: 10,
        qts: 0,
        date: 1,
        seq: 1,
    };
    let newer = UpdatesStateDto {
        pts: 20,
        qts: 0,
        date: 2,
        seq: 2,
    };
    let kept = prefer_newer_cursor(Some(newer.clone()), Some(older));
    assert_eq!(kept.unwrap().pts, 20);
}

#[test]
fn cursor_sequences_progress_independently_of_pts() {
    let old = UpdatesStateDto {
        pts: 20,
        qts: 3,
        date: 4,
        seq: 5,
    };
    let next = UpdatesStateDto {
        pts: 20,
        qts: 4,
        date: 6,
        seq: 7,
    };
    let kept = prefer_newer_cursor(Some(old), Some(next)).unwrap();
    assert_eq!((kept.pts, kept.qts, kept.date, kept.seq), (20, 4, 6, 7));
}

#[test]
fn lane_merge_preserves_concurrent_insert_update_and_delete() {
    let before = HashMap::from_iter([(1, 10), (2, 20), (3, 30), (4, 40)]);
    let mut current = HashMap::from_iter([(1, 11), (2, 20), (4, 40), (5, 50)]);
    let incoming = HashMap::from_iter([(1, 10), (2, 22), (3, 30), (6, 60)]);
    merge_changed_entries(&mut current, &before, incoming);
    assert_eq!(
        current,
        HashMap::from_iter([(1, 11), (2, 22), (5, 50), (6, 60)])
    );
}

#[test]
fn main_lane_does_not_restore_stale_updates_metadata() {
    let path = std::env::temp_dir().join(format!("mtproto-merge-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    with_client_mut(handle, |_state| {
        let mut data = client.data.lock();
        data.channel_pts.insert(42, 13);
        data.seen_messages.insert((42, 11));
        data.updates = Some(UpdatesStateDto {
            pts: 9,
            qts: 8,
            date: 7,
            seq: 6,
        });
        Ok(())
    })
    .unwrap();
    let data = client.data.lock();
    assert_eq!(data.channel_pts.get(&42), Some(&13));
    assert!(data.seen_messages.contains(&(42, 11)));
    assert_eq!(data.updates.as_ref().unwrap().qts, 8);
    drop(data);
    destroy_client(handle);
}

#[test]
fn existing_auth_key_does_not_rewrite_snapshot_on_every_rpc() {
    let path =
        std::env::temp_dir().join(format!("mtproto-auth-no-write-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    with_client_mut(handle, |state| {
        state.snapshot.auth_key = Some(vec![7; 256]);
        ensure_auth_key(state)
    })
    .unwrap();
    assert!(!path.exists());
    destroy_client(handle);
}

#[test]
fn update_persistence_uses_current_home_snapshot() {
    let path = std::env::temp_dir().join(format!(
        "mtproto-persist-current-{}.json",
        std::process::id()
    ));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let session_id = client.main.lock().snapshot.session_id;
    persist_updates_data(&client, session_id).unwrap();
    client.main.lock().snapshot.server_salt = 73;
    persist_updates_data(&client, session_id).unwrap();
    let loaded = FileSessionStore::new(&path).load().unwrap().unwrap();
    assert_eq!(loaded.snapshot.server_salt, 73);
    destroy_client(handle);
    std::fs::remove_file(path).unwrap();
}

#[test]
fn logout_fences_completed_update_lane_and_snapshot_save() {
    let path =
        std::env::temp_dir().join(format!("mtproto-logout-fence-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let old_home = client.main.lock().snapshot.clone();
    let (ready_tx, ready_rx) = std::sync::mpsc::channel();
    let (resume_tx, resume_rx) = std::sync::mpsc::channel();
    let worker_client = client.clone();
    let worker = std::thread::spawn(move || {
        ready_tx.send(()).unwrap();
        resume_rx.recv().unwrap();
        assert!(persist_updates_data(&worker_client, old_home.session_id).is_err());
    });
    ready_rx.recv().unwrap();
    logout(handle).unwrap();
    resume_tx.send(()).unwrap();
    worker.join().unwrap();
    assert!(!path.exists());
    assert_eq!(client.main.lock().snapshot.server_salt, 0);
    assert!(client.data.lock().updates.is_none());
    destroy_client(handle);
}

#[test]
fn dead_media_response_cannot_invalidate_replacement_session() {
    let path =
        std::env::temp_dir().join(format!("mtproto-media-fence-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let old_session_id = client.main.lock().snapshot.session_id;
    logout(handle).unwrap();
    let result = with_client_mut(handle, |state| {
        if state.snapshot.session_id != old_session_id {
            return Err(expired_session_lease());
        }
        mark_session_dead(
            state,
            &MtprotoError::Message("RPC 401: AUTH_KEY_UNREGISTERED".into()),
        );
        Ok(())
    });
    assert!(result.is_err());
    assert!(!client.data.lock().session_dead);
    assert!(!path.exists());
    destroy_client(handle);
}

#[test]
fn logout_prevents_completed_media_publication() {
    let root = std::env::temp_dir().join(format!("mtproto-media-publish-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let handle = create_client(
        1,
        "hash".into(),
        root.join("session").to_string_lossy().into(),
    );
    let client = get_client(handle).unwrap();
    let session_id = client.main.lock().snapshot.session_id;
    let dest = root.join("media");
    std::fs::write(&dest, b"previous").unwrap();
    let staged = media_rpc::StagedDownload::new(&dest).unwrap();
    let staging_path = staged.path().to_owned();
    std::fs::write(&staging_path, b"old session result").unwrap();
    logout(handle).unwrap();
    assert!(publish_media(&client, session_id, staged, &dest).is_err());
    assert!(!staging_path.exists());
    assert_eq!(std::fs::read(&dest).unwrap(), b"previous");
    destroy_client(handle);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn cancelled_request_exits_while_lane_is_still_owned_by_another_call() {
    let lane = Arc::new(Mutex::new(SessionIo {
        pending_push: Default::default(),
        last_difference: None,
        snapshot: Snapshot::new(2, &mut OsRandom).unwrap(),
        transport: None,
    }));
    let held = lane.lock();
    let worker_lane = lane.clone();
    let id = crate::request_control::create();
    let (started_tx, started_rx) = std::sync::mpsc::channel();
    let (done_tx, done_rx) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let previous = crate::request_control::bind(id);
        started_tx.send(()).unwrap();
        done_tx
            .send(lock_request_lane(&worker_lane, "lane_wait.test").is_err())
            .unwrap();
        crate::request_control::bind(previous);
    });
    started_rx.recv().unwrap();
    crate::request_control::cancel(id);
    let result = done_rx.recv_timeout(std::time::Duration::from_secs(2));
    drop(held);
    worker.join().unwrap();
    crate::request_control::release(id);
    assert_eq!(result.unwrap(), true);
}

fn sample_home() -> Snapshot {
    let mut home = Snapshot::new(2, &mut OsRandom).expect("home");
    home.auth_key = Some(vec![7_u8; 256]);
    home.server_salt = 99;
    home.time_offset_micros = 1_000;
    home
}

#[test]
fn home_dc_media_reuses_forked_lane_without_export() {
    let home = sample_home();
    let mut lane = fork_session(&home);
    let session_id = lane.session_id;
    assert_ne!(session_id, home.session_id);
    assert_eq!(
        prepare_media_lane_snapshot(&mut lane, &home, 2),
        MediaLanePrep::Reuse
    );
    assert_eq!(lane.session_id, session_id);
    assert_eq!(lane.auth_key, home.auth_key);
    assert_eq!(lane.server_salt, 99);
}

#[test]
fn home_dc_media_reforks_after_foreign_dc_auth() {
    let home = sample_home();
    let mut lane = fork_session(&home);
    lane.dc_id = 4;
    lane.auth_key = Some(vec![3_u8; 256]);
    let old_session = lane.session_id;
    assert_eq!(
        prepare_media_lane_snapshot(&mut lane, &home, 2),
        MediaLanePrep::Replaced
    );
    assert_eq!(lane.dc_id, 2);
    assert_eq!(lane.auth_key, home.auth_key);
    assert_ne!(lane.session_id, home.session_id);
    assert_ne!(lane.session_id, old_session);
}

#[test]
fn foreign_dc_media_exports_when_lane_still_has_home_auth() {
    let home = sample_home();
    let mut lane = fork_session(&home);
    assert_eq!(
        prepare_media_lane_snapshot(&mut lane, &home, 4),
        MediaLanePrep::NeedExport
    );
    assert_eq!(lane.auth_key, home.auth_key);
}

#[test]
fn foreign_dc_media_reuses_exported_auth() {
    let home = sample_home();
    let mut lane = fork_session(&home);
    lane.dc_id = 4;
    lane.auth_key = Some(vec![9_u8; 256]);
    let session_id = lane.session_id;
    assert_eq!(
        prepare_media_lane_snapshot(&mut lane, &home, 4),
        MediaLanePrep::Reuse
    );
    assert_eq!(lane.session_id, session_id);
    assert_eq!(lane.dc_id, 4);
}

/// Read lanes are extra *home-DC main sessions*: they only exist when the server
/// grants parallel main sessions, and learning that needs a live `help.getConfig`.
fn allow_read_lanes() -> std::sync::MutexGuard<'static, ()> {
    let guard = crate::scheduler::ALLOWANCE_TEST_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    crate::scheduler::set_main_session_allowance(Some(3));
    guard
}

#[test]
fn read_uses_a_sibling_lane_when_one_is_busy() {
    let _allowance = allow_read_lanes();
    let path =
        std::env::temp_dir().join(format!("monogram-read-lane-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).expect("client");
    // Hold one read lane only: the read must use the sibling, not the updates lane.
    let _held = client.rpc[0]
        .gate
        .acquire(crate::scheduler::RequestClass::InteractiveRead)
        .expect("hold lane");
    let started = std::time::Instant::now();
    let mut used_read_lane = false;
    with_read_lane(handle, |_state| {
        used_read_lane = crate::api_invoke::invoking_without_updates();
        Ok(())
    })
    .expect("read lane call");
    let elapsed = started.elapsed();
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
    assert!(used_read_lane, "read fell back to the updates lane");
    assert!(
        elapsed < std::time::Duration::from_millis(150),
        "read waited for the busy lane instead of using the sibling ({elapsed:?})"
    );
}

#[test]
fn read_waits_for_its_own_lane_and_never_collapses_onto_updates() {
    let _allowance = allow_read_lanes();
    let path =
        std::env::temp_dir().join(format!("monogram-read-wait-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).expect("client");
    // Hold both lanes until told; the caller must block, not use the updates lane.
    let (release_tx, release_rx) = std::sync::mpsc::channel::<()>();
    let (held_tx, held_rx) = std::sync::mpsc::channel::<()>();
    let holder = {
        let client = client.clone();
        std::thread::spawn(move || {
            let _first = client.rpc[0]
                .gate
                .acquire(crate::scheduler::RequestClass::InteractiveRead)
                .expect("hold lane 0");
            let _second = client.rpc[1]
                .gate
                .acquire(crate::scheduler::RequestClass::InteractiveRead)
                .expect("hold lane 1");
            let _ = held_tx.send(());
            let _ = release_rx.recv_timeout(std::time::Duration::from_secs(5));
        })
    };
    held_rx
        .recv_timeout(std::time::Duration::from_secs(5))
        .expect("holder acquired both lanes");
    let (done_tx, done_rx) = std::sync::mpsc::channel::<bool>();
    let caller = std::thread::spawn(move || {
        let result = with_read_lane(handle, |_state| {
            Ok(crate::api_invoke::invoking_without_updates())
        });
        let _ = done_tx.send(result.expect("read lane call"));
    });
    assert!(
        done_rx
            .recv_timeout(std::time::Duration::from_millis(250))
            .is_err(),
        "read did not wait for a read lane"
    );
    release_tx.send(()).expect("release lanes");
    let used_read_lane = done_rx
        .recv_timeout(std::time::Duration::from_secs(5))
        .expect("read completed after a lane freed");
    holder.join().expect("holder thread");
    caller.join().expect("caller thread");
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
    assert!(used_read_lane, "read collapsed onto the updates lane");
}

fn authorize_test_client(handle: u64) -> (i64, Vec<u8>) {
    let client = get_client(handle).expect("client");
    let mut main = client.main.lock();
    main.snapshot.auth_key = Some(vec![7_u8; 256]);
    main.snapshot.server_salt = 99;
    let home_id = main.snapshot.session_id;
    let auth = main.snapshot.auth_key.clone().unwrap();
    let mut data = client.data.lock();
    data.user_id = Some(1);
    data.home_dc = main.snapshot.dc_id;
    data.home_session_id = home_id;
    data.home_auth_key = Some(auth.clone());
    data.home_salt = 99;
    (home_id, auth)
}

#[test]
fn extra_read_lane_reuses_home_auth_without_export() {
    let home = sample_home();
    let mut lane = fork_session(&home);
    let session_id = lane.session_id;
    assert_ne!(session_id, home.session_id);
    assert_eq!(
        prepare_media_lane_snapshot(&mut lane, &home, home.dc_id),
        MediaLanePrep::Reuse
    );
    assert_eq!(lane.session_id, session_id);
    assert_eq!(lane.auth_key, home.auth_key);
    assert_ne!(lane.session_id, home.session_id);
}

#[test]
fn extra_read_lane_does_not_replace_home_session_id() {
    let _allowance = allow_read_lanes();
    let path =
        std::env::temp_dir().join(format!("monogram-extra-rpc-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let (home_id, auth) = authorize_test_client(handle);
    let mut seen_session = 0_i64;
    with_read_lane(handle, |state| {
        seen_session = state.snapshot.session_id;
        assert_ne!(state.snapshot.session_id, home_id);
        assert_eq!(state.snapshot.auth_key.as_deref(), Some(auth.as_slice()));
        assert!(crate::api_invoke::invoking_without_updates());
        Ok(())
    })
    .expect("extra lane");
    let client = get_client(handle).expect("client");
    let data = client.data.lock();
    assert_eq!(data.home_session_id, home_id);
    assert_eq!(data.home_auth_key.as_deref(), Some(auth.as_slice()));
    assert_ne!(seen_session, home_id);
    assert!(!crate::api_invoke::invoking_without_updates());
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}

#[test]
fn idle_work_still_reaches_the_read_lane_family() {
    let _allowance = allow_read_lanes();
    let path = std::env::temp_dir().join(format!(
        "monogram-extra-busy-{}.session",
        std::process::id()
    ));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let (_home_id, _) = authorize_test_client(handle);
    let mut used_read_lane = false;
    with_read_lane(handle, |_state| {
        used_read_lane = crate::api_invoke::invoking_without_updates();
        Ok(())
    })
    .expect("read lane");
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
    assert!(used_read_lane);
}

#[test]
fn merge_changed_entries_reports_no_change() {
    let before = HashMap::from_iter([(1, 10)]);
    let mut current = before.clone();
    let incoming = before.clone();
    assert!(!merge_changed_entries(&mut current, &before, incoming));
    assert_eq!(current, before);
}

#[test]
fn empty_update_poll_skips_cache_clone() {
    let path =
        std::env::temp_dir().join(format!("monogram-empty-poll-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    authorize_test_client(handle);
    {
        let mut data = client.data.lock();
        data.updates_started = true;
        data.updates = Some(UpdatesStateDto {
            pts: 1,
            qts: 1,
            date: 1,
            seq: 1,
        });
        for i in 0..2_000 {
            data.peers.insert(
                i,
                peers::CachedPeer {
                    kind: peers::PeerKind::User,
                    id: i,
                    access_hash: i,
                    min_hash: false,
                },
            );
        }
    }
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let transport =
        crate::rpc::open_live_addr(2, &listener.local_addr().unwrap().to_string(), None, 1)
            .unwrap();
    let (_peer, _) = listener.accept().unwrap();
    {
        let mut main = client.main.lock();
        main.transport = Some(transport);
        main.last_difference = Some(std::time::Instant::now());
    }
    let _ = take_update_cache_clones();
    let events = drain_updates(handle).unwrap();
    assert!(events.is_empty());
    assert_eq!(take_update_cache_clones(), 0);
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}

#[test]
fn extra_lane_persist_is_coalesced_onto_one_worker() {
    let path =
        std::env::temp_dir().join(format!("monogram-persist-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    let session_id = client.data.lock().home_session_id;
    schedule_persist(&client, session_id);
    schedule_persist(&client, session_id);
    schedule_persist(&client, session_id);
    let started = std::time::Instant::now();
    while client.persist_running.load(Ordering::Acquire)
        || client.persist_queued.load(Ordering::Acquire)
    {
        if started.elapsed() > std::time::Duration::from_secs(2) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    destroy_client(handle);
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(format!("{}.tmp", path.display()));
    assert!(!client.persist_running.load(Ordering::Acquire));
    assert!(!client.persist_queued.load(Ordering::Acquire));
}

#[test]
fn main_lane_schedules_persist_and_flush_on_destroy() {
    let path = std::env::temp_dir().join(format!(
        "monogram-main-persist-{}.session",
        std::process::id()
    ));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    with_client_mut(handle, |_state| Ok(())).unwrap();
    with_client_mut(handle, |_state| Ok(())).unwrap();
    with_client_mut(handle, |_state| Ok(())).unwrap();
    let started = std::time::Instant::now();
    while client.persist_running.load(Ordering::Acquire)
        || client.persist_queued.load(Ordering::Acquire)
    {
        if started.elapsed() > std::time::Duration::from_secs(2) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    assert!(client.data.lock().persist_epoch >= 3);
    destroy_client(handle);
    assert!(!client.persist_running.load(Ordering::Acquire));
    let _ = std::fs::remove_file(&path);
}

#[test]
fn dead_session_reports_the_invalidating_error_not_a_fake_401() {
    let path =
        std::env::temp_dir().join(format!("mtproto-dead-reason-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    with_client_mut(handle, |state| {
        state.session_dead = true;
        state.session_dead_reason = Some("AUTH_KEY_DUPLICATED".into());
        let err = reject_if_dead(state).expect_err("a dead session must refuse RPCs");
        assert!(
            matches!(&err, MtprotoError::Message(message)
                    if message == "session invalidated: AUTH_KEY_DUPLICATED"),
            "unexpected error: {err}"
        );
        // A local gate must not be mistakable for a fresh server rejection.
        assert!(!err.to_string().contains("RPC 401"));
        assert!(!err.to_string().contains("AUTH_KEY_UNREGISTERED"));
        Ok(())
    })
    .expect("dead session probe");
    destroy_client(handle);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn session_token_keeps_only_catalog_tokens() {
    assert_eq!(
        session_dead_token(&MtprotoError::Message(
            "RPC 406: AUTH_KEY_DUPLICATED".into()
        )),
        "AUTH_KEY_DUPLICATED"
    );
    assert_eq!(
        session_dead_token(&MtprotoError::Message(
            "RPC 401: AUTH_KEY_UNREGISTERED".into()
        )),
        "AUTH_KEY_UNREGISTERED"
    );
    assert_eq!(
        session_dead_token(&MtprotoError::Message(
            "decode failed for +1 555 0100 secret note".into()
        )),
        "UNKNOWN"
    );
    assert_eq!(
        session_dead_token(&MtprotoError::RegistrationRequired),
        "UNKNOWN"
    );
}

#[test]
fn upload_batches_take_the_main_lane_once_per_batch() {
    let _allowance = crate::scheduler::ALLOWANCE_TEST_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let original = crate::scheduler::main_session_allowance();
    crate::scheduler::set_main_session_allowance(None);
    assert_eq!(
        crate::scheduler::extra_main_sessions(),
        0,
        "a home DC without tmp_sessions allows one main session"
    );

    let stem = format!("monogram-upload-{}", std::process::id());
    let file = std::env::temp_dir().join(format!("{stem}.bin"));
    let session = std::env::temp_dir().join(format!("{stem}.session"));
    std::fs::write(&file, vec![5_u8; crate::upload_rpc::FILE_PART * 2]).expect("fixture");
    let item = crate::UploadItemDto {
        path: file.to_string_lossy().into_owned(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "fixture.bin".into(),
        caption: String::new(),
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let handle = create_client(1, "hash".into(), session.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).expect("client");
    let mut staging = crate::upload_rpc::open_staging(&item).expect("staging");
    let mut batches = 0;
    upload_batches(&mut staging, 1, |_batch| {
        assert!(
            client.main.try_lock().is_some(),
            "the upload held the main lane across batches"
        );
        batches += 1;
        Ok(())
    })
    .expect("batches");
    assert_eq!(batches, 2, "one lane acquisition per part batch");
    destroy_client(handle);
    let _ = std::fs::remove_file(file);
    let _ = std::fs::remove_file(session);
    crate::scheduler::set_main_session_allowance(Some(original));
}
