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
        perm_auth_key: None,
        perm_salt: 0,
        perm_session_id: 0,
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
