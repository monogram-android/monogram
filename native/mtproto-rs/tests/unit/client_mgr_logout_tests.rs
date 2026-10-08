use super::*;

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
        perm_auth_key: None,
        perm_salt: 0,
        perm_session_id: 0,
    };
    persist(&state).expect("seed session");
    assert!(path.exists());

    logout(handle).expect("local logout");
    assert!(!path.exists());
    assert!(!is_authorized(handle).expect("authorization state"));
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
        temp_expires_at: 0,
        perm_key: None,
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
    assert_eq!(client.data.lock().persist_epoch, 0);
    with_client_mut(handle, |state| {
        state.user_id = Some(1);
        Ok(())
    })
    .unwrap();
    let started = std::time::Instant::now();
    while client.persist_running.load(Ordering::Acquire)
        || client.persist_queued.load(Ordering::Acquire)
    {
        if started.elapsed() > std::time::Duration::from_secs(2) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    assert!(client.data.lock().persist_epoch >= 1);
    destroy_client(handle);
    assert!(!client.persist_running.load(Ordering::Acquire));
    let _ = std::fs::remove_file(&path);
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
