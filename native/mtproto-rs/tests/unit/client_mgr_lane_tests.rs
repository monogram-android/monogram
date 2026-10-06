use super::*;

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
    std::fs::write(&file, vec![5_u8; crate::upload::upload_rpc::FILE_PART * 2]).expect("fixture");
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
    let mut staging = crate::upload::upload_rpc::open_staging(&item).expect("staging");
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
