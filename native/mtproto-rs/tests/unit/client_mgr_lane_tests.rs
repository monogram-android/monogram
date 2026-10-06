use super::*;

#[test]
fn download_sessions_grow_from_one_to_eight_and_reset_after_idle() {
    let path = std::env::temp_dir().join(format!("monogram-growth-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    client.policy.own_media_lanes(8);
    assert_eq!(client.media_open.load(Ordering::Acquire), 1);
    scheduler::with_class(scheduler::RequestClass::InteractiveMedia, || {
        let mut leases = Vec::new();
        for expected in 1..=8 {
            leases.push(lock_media_lane(&client).unwrap());
            assert_eq!(client.media_open.load(Ordering::Acquire), expected);
        }
        assert!(close_idle_file_transports(
            &client,
            std::time::Instant::now()
        ));
        assert_eq!(client.media_open.load(Ordering::Acquire), 8);
        drop(leases);
        assert!(!close_idle_file_transports(
            &client,
            std::time::Instant::now()
        ));
        assert_eq!(client.media_open.load(Ordering::Acquire), 1);
    });
    destroy_client(handle);
    std::fs::remove_file(path).ok();
}

#[test]
fn idle_main_ack_deadline_flushes_all_pending_ids_without_an_rpc() {
    let path = std::env::temp_dir().join(format!("monogram-ack-idle-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap().to_string();
    let transport = crate::rpc::open_live_addr(2, &address, None, 1).unwrap();
    let (_peer, _) = listener.accept().unwrap();
    {
        let mut io = client.main.lock();
        io.snapshot.auth_key = Some(vec![7; 256]);
        for id in 0..130 {
            io.snapshot.acknowledge((id * 4) + 1).unwrap();
        }
        io.transport = Some(transport);
    }
    let now = std::time::Instant::now();
    let due = now + crate::rpc::ACK_DELAY;
    assert!(flush_idle_main_acks(&client, now, due));
    assert_eq!(
        client.main.lock().snapshot.pending_acknowledgements.len(),
        130
    );
    assert!(!flush_idle_main_acks(&client, due, due));
    assert!(
        client
            .main
            .lock()
            .snapshot
            .pending_acknowledgements
            .is_empty()
    );
    assert!(client.main.lock().transport.is_some());
    destroy_client(handle);
    std::fs::remove_file(path).ok();
}

#[test]
fn measured_upload_rtt_selects_the_small_part_path_in_production_staging() {
    let path =
        std::env::temp_dir().join(format!("monogram-upload-rtt-{}.json", std::process::id()));
    let file = path.with_extension("bin");
    std::fs::write(&file, vec![1; 96 * 1024]).unwrap();
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    client.policy.own_file_part_kib(512);
    client.policy.own_pipeline_parts(8);
    let dc = client.main.lock().snapshot.dc_id;
    *client.policy.upload_rtt.lock() = Some((
        dc,
        std::time::Instant::now(),
        std::time::Duration::from_secs(1),
    ));
    let item = crate::UploadItemDto {
        path: file.to_string_lossy().into(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "test.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let mut sizes = Vec::new();
    save_items_with(&client, 1, &[item], |_, batch| {
        assert_eq!(batch.bytes.len(), 1);
        sizes.push(batch.bytes[0].len());
        Ok(())
    })
    .unwrap();
    assert_eq!(sizes, vec![32 * 1024; 3]);
    destroy_client(handle);
    std::fs::remove_file(path).ok();
    std::fs::remove_file(file).ok();
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

/// Advertise server permission to verify that permission alone cannot enable
/// permanent-key parallel sessions before PFS is implemented.
fn allow_read_lanes() -> std::sync::MutexGuard<'static, ()> {
    let guard = crate::scheduler::ALLOWANCE_TEST_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    crate::scheduler::set_main_session_allowance(Some(3));
    guard
}

#[test]
fn read_keeps_home_session_when_a_secondary_lane_is_busy() {
    let _allowance = allow_read_lanes();
    let path =
        std::env::temp_dir().join(format!("monogram-read-lane-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).expect("client");
    // A busy unused secondary lane must not affect the single home session.
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
    assert!(
        !used_read_lane,
        "permanent-key read opened a parallel session"
    );
    assert!(
        elapsed < std::time::Duration::from_millis(150),
        "read waited for an unused secondary lane ({elapsed:?})"
    );
}

#[test]
fn read_does_not_wait_for_unused_secondary_lanes_without_pfs() {
    let _allowance = allow_read_lanes();
    let path =
        std::env::temp_dir().join(format!("monogram-read-wait-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).expect("client");
    // Hold both secondary lanes: the caller must still use the home session.
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
    let used_read_lane = done_rx.recv_timeout(std::time::Duration::from_secs(5));
    release_tx.send(()).expect("release lanes");
    holder.join().expect("holder thread");
    caller.join().expect("caller thread");
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
    assert!(!used_read_lane.expect("home read completed while secondary lanes held"));
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
fn server_allowance_without_pfs_preserves_home_identity() {
    let _allowance = allow_read_lanes();
    let path =
        std::env::temp_dir().join(format!("monogram-extra-rpc-{}.session", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let (home_id, auth) = authorize_test_client(handle);
    let mut seen_session = 0_i64;
    with_read_lane(handle, |state| {
        seen_session = state.snapshot.session_id;
        assert_eq!(state.snapshot.session_id, home_id);
        assert_eq!(state.snapshot.auth_key.as_deref(), Some(auth.as_slice()));
        assert!(!crate::api_invoke::invoking_without_updates());
        Ok(())
    })
    .expect("extra lane");
    let client = get_client(handle).expect("client");
    {
        let data = client.data.lock();
        assert_eq!(data.home_session_id, home_id);
        assert_eq!(data.home_auth_key.as_deref(), Some(auth.as_slice()));
    }
    assert_eq!(seen_session, home_id);
    assert!(!crate::api_invoke::invoking_without_updates());
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}

#[test]
fn idle_work_uses_home_session_without_pfs() {
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
    assert!(!used_read_lane);
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
    std::fs::write(
        &file,
        vec![5_u8; crate::upload::upload_rpc::FILE_PART_FAST + 1],
    )
    .expect("fixture");
    let item = crate::UploadItemDto {
        path: file.to_string_lossy().into_owned(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "fixture.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let handle = create_client(1, "hash".into(), session.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).expect("client");
    let mut staging = crate::upload::upload_rpc::open_staging(&item).expect("staging");
    let expected_parts =
        crate::upload::upload_rpc::part_count(std::fs::metadata(&file).unwrap().len());
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
    assert_eq!(
        batches, expected_parts,
        "one lane acquisition per part batch"
    );
    destroy_client(handle);
    let _ = std::fs::remove_file(file);
    let _ = std::fs::remove_file(session);
    crate::scheduler::set_main_session_allowance(Some(original));
}

#[test]
fn even_odd_media_offsets_split_by_chunk() {
    let chunk = 128 * 1024;
    let offsets = [0, chunk, chunk * 2, chunk * 3, chunk * 4];
    let (even, odd) = split_even_odd_request_indices(&offsets, chunk as i32);
    assert_eq!(even, vec![0, 2, 4]);
    assert_eq!(odd, vec![1, 3]);
}

#[test]
fn extra_main_sessions_stay_zero_without_tmp_sessions() {
    assert_eq!(crate::scheduler::extra_main_sessions(), 0);
}

#[test]
fn file_parts_use_one_upload_session_and_metadata_stays_on_main() {
    let stem = format!(
        "monogram-upload-session-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    );
    let small = std::env::temp_dir().join(format!("{stem}-small.bin"));
    let big = std::env::temp_dir().join(format!("{stem}-big.bin"));
    let session = std::env::temp_dir().join(format!("{stem}.session"));
    std::fs::write(&small, vec![9_u8; 64]).expect("small fixture");
    let big_file = std::fs::File::create(&big).expect("big fixture");
    big_file
        .set_len(crate::upload::upload_rpc::BIG_FILE_THRESHOLD + 1)
        .expect("big length");
    drop(big_file);
    let small_item = crate::UploadItemDto {
        path: small.to_string_lossy().into_owned(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "small.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let big_item = crate::UploadItemDto {
        path: big.to_string_lossy().into_owned(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "big.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let handle = create_client(1, "hash".into(), session.to_string_lossy().into());
    let client = get_client(handle).expect("client");
    let main_id = client.main.lock().snapshot.session_id;
    let media_ids: Vec<i64> = client
        .media
        .iter()
        .map(|lane| lane.io.lock().snapshot.session_id)
        .collect();
    let rpc_ids = [
        client.rpc[0].io.lock().snapshot.session_id,
        client.rpc[1].io.lock().snapshot.session_id,
    ];
    let mut seen = Vec::new();
    save_items_with(&client, 1, &[small_item, big_item], |snapshot, batch| {
        seen.push((snapshot.session_id, batch.big));
        Ok(())
    })
    .expect("recorded parts");
    let upload_id = client.upload.lock().snapshot.session_id;
    assert_ne!(upload_id, main_id);
    assert!(!media_ids.contains(&upload_id));
    assert!(!rpc_ids.contains(&upload_id));
    assert!(seen.iter().any(|(_, big)| !big), "saveFilePart");
    assert!(seen.iter().any(|(_, big)| *big), "saveBigFilePart");
    assert!(seen.iter().all(|(id, _)| *id == upload_id));
    with_interactive_client_mut(handle, |state| {
        assert_eq!(state.snapshot.session_id, main_id);
        Ok(())
    })
    .expect("main metadata session");
    destroy_client(handle);
    let _ = std::fs::remove_file(small);
    let _ = std::fs::remove_file(big);
    let _ = std::fs::remove_file(session);
}

#[test]
fn idle_file_cleanup_closes_sockets_and_preserves_busy_lanes_and_main() {
    use std::io::Read;
    let path = std::env::temp_dir().join(format!("monogram-file-idle-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap().to_string();
    let now = std::time::Instant::now();
    let mut media = crate::rpc::open_live_addr(2, &address, None, 1).unwrap();
    let (mut media_peer, _) = listener.accept().unwrap();
    let mut upload = crate::rpc::open_live_addr(2, &address, None, 1).unwrap();
    let (mut upload_peer, _) = listener.accept().unwrap();
    let main = crate::rpc::open_live_addr(2, &address, None, 1).unwrap();
    let (_main_peer, _) = listener.accept().unwrap();
    media.last_io = now;
    upload.last_io = now;
    client.media[0].io.lock().transport = Some(media);
    client.upload.lock().transport = Some(upload);
    client.main.lock().transport = Some(main);
    assert!(close_idle_file_transports(&client, now));
    let busy = client.media[0].io.lock();
    let later = now + crate::media::DOWNLOAD_SESSION_IDLE;
    assert!(close_idle_file_transports(&client, later));
    assert!(busy.transport.is_some());
    assert!(client.upload.lock().transport.is_none());
    drop(busy);
    assert!(!close_idle_file_transports(&client, later));
    assert!(client.media[0].io.lock().transport.is_none());
    assert!(client.main.lock().transport.is_some());
    for peer in [&mut media_peer, &mut upload_peer] {
        peer.set_read_timeout(Some(std::time::Duration::from_secs(1)))
            .unwrap();
        let mut header = [0u8; 64];
        peer.read_exact(&mut header).unwrap();
        assert_eq!(peer.read(&mut [0u8; 1]).unwrap(), 0);
    }
    destroy_client(handle);
    std::fs::remove_file(path).ok();
}

#[test]
fn media_waiters_use_any_freed_lane_and_admit_interactive_before_background() {
    let path =
        std::env::temp_dir().join(format!("monogram-lane-wakeup-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    client.policy.own_media_lanes(2);
    let (first, last) = scheduler::with_class(scheduler::RequestClass::InteractiveMedia, || {
        (
            lock_media_lane(&client).unwrap(),
            lock_media_lane(&client).unwrap(),
        )
    });
    let first_id = first.snapshot.session_id;
    let (tx, rx) = std::sync::mpsc::channel();
    let background = client.clone();
    let background_tx = tx.clone();
    let worker = std::thread::spawn(move || {
        scheduler::with_class(scheduler::RequestClass::BackgroundMedia, || {
            let lease = lock_media_lane(&background).unwrap();
            background_tx
                .send((false, lease.snapshot.session_id))
                .unwrap();
        })
    });
    let wait_for_queue = |count| {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while client.media_gate.waiter_count() < count && std::time::Instant::now() < deadline {
            std::thread::yield_now();
        }
        assert_eq!(client.media_gate.waiter_count(), count);
    };
    wait_for_queue(1);
    let interactive = client.clone();
    let foreground = std::thread::spawn(move || {
        scheduler::with_class(scheduler::RequestClass::InteractiveMedia, || {
            let lease = lock_media_lane(&interactive).unwrap();
            tx.send((true, lease.snapshot.session_id)).unwrap();
        })
    });
    wait_for_queue(2);
    drop(first);
    assert_eq!(
        rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),
        (true, first_id)
    );
    assert_eq!(
        rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap(),
        (false, first_id)
    );
    drop(last);
    foreground.join().unwrap();
    worker.join().unwrap();
    destroy_client(handle);
    std::fs::remove_file(path).ok();
}

#[test]
fn expired_upload_rtt_does_not_require_a_probe_connection_or_inherit_download_caps() {
    let path = std::env::temp_dir().join(format!(
        "monogram-upload-fallback-{}.json",
        std::process::id()
    ));
    let file = path.with_extension("bin");
    std::fs::write(&file, vec![1; 96 * 1024]).unwrap();
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    client.policy.own_pipeline_parts(2);
    let dc = client.main.lock().snapshot.dc_id;
    *client.policy.upload_rtt.lock() = Some((
        dc,
        std::time::Instant::now() - std::time::Duration::from_secs(61),
        std::time::Duration::from_secs(1),
    ));
    let item = crate::UploadItemDto {
        path: file.to_string_lossy().into(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "test.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let staging = save_items_with(&client, 1, &[item], |snapshot, batch| {
        assert!(snapshot.auth_key.is_none());
        assert_eq!(batch.bytes[0].len(), 96 * 1024);
        Ok(())
    })
    .unwrap();
    assert_eq!(staging[0].in_flight(), 8);
    assert!(client.upload.lock().transport.is_none());
    destroy_client(handle);
    std::fs::remove_file(path).ok();
    std::fs::remove_file(file).ok();
}

#[test]
fn failed_rtt_probe_on_an_existing_upload_connection_does_not_fail_saved_parts() {
    let path = std::env::temp_dir().join(format!(
        "monogram-upload-probe-error-{}.json",
        std::process::id()
    ));
    let file = path.with_extension("bin");
    std::fs::write(&file, vec![1; 96 * 1024]).unwrap();
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    authorize_test_client(handle);
    let client = get_client(handle).unwrap();
    let home = client.main.lock().snapshot.clone();
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let transport = crate::rpc::open_live_addr(
        home.dc_id,
        &listener.local_addr().unwrap().to_string(),
        None,
        1,
    )
    .unwrap();
    let (peer, _) = listener.accept().unwrap();
    drop(peer);
    {
        let mut io = client.upload.lock();
        io.snapshot = fork_session(&home);
        io.transport = Some(transport);
    }
    let item = crate::UploadItemDto {
        path: file.to_string_lossy().into(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "test.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let mut saved = 0;
    let staging = save_items_with(&client, 1, &[item], |_, batch| {
        saved += batch.bytes.len();
        Ok(())
    })
    .unwrap();
    assert_eq!(saved, 1);
    assert_eq!(staging.len(), 1);
    assert!(client.policy.upload_rtt.lock().is_none());
    assert!(client.upload.lock().transport.is_none());
    destroy_client(handle);
    std::fs::remove_file(path).ok();
    std::fs::remove_file(file).ok();
}
