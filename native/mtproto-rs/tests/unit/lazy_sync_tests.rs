use super::*;

#[test]
fn sync_edge_emits_on_change_and_not_while_unchanged() {
    let mut syncing = false;
    assert!(sync_edge(syncing, true));
    syncing = true;
    assert!(!sync_edge(syncing, true));
    assert!(sync_edge(syncing, false));
    syncing = false;
    assert!(!sync_edge(syncing, false));
}

#[test]
fn lazy_route_preserves_difference_recovery_for_every_channel_gap() {
    let open = peers::chat_id_for_channel(1);
    let exception = peers::chat_id_for_channel(2);
    let background = peers::chat_id_for_channel(3);
    let user = peers::chat_id_for_user(4);
    let mut exceptions = HashSet::new();
    exceptions.insert(exception);
    let mut lazy = HashSet::new();
    let full = route_lazy_channels(
        vec![open, exception, background, user, background],
        open,
        &exceptions,
        true,
        &mut lazy,
    );
    assert_eq!(full, vec![open, exception, background, background]);
    assert_eq!(lazy.len(), 1);
    assert!(lazy.contains(&background));
    assert!(!lazy.contains(&open));
    assert!(!lazy.contains(&exception));
    assert!(!lazy.contains(&user));
}

#[test]
fn lazy_gap_survives_session_reload_without_advancing_pts() {
    let path = std::env::temp_dir().join(format!("monogram-lazy-gap-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let chat = peers::chat_id_for_channel(3);
    let mut lazy = HashSet::new();
    let pending = route_lazy_channels(vec![chat], 0, &HashSet::new(), true, &mut lazy);
    with_client_mut(handle, |state| {
        state.channel_pts.insert(chat, 20);
        prepare_channel_recovery(&mut state.channel_recovery, pending, 0, 100);
        persist(state)
    })
    .unwrap();
    destroy_client(handle);
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    {
        let data = client.data.lock();
        assert_eq!(data.channel_pts.get(&chat), Some(&20));
        assert!(
            data.channel_recovery
                .iter()
                .any(|entry| entry.chat_id == chat && !entry.watching)
        );
    }
    destroy_client(handle);
    std::fs::remove_file(path).ok();
}

#[test]
fn lazy_failures_honor_flood_deadlines_and_terminal_peers() {
    let flood = MtprotoError::Message("RPC 420: FLOOD_WAIT_120".into());
    assert_eq!(recovery_wait_secs(&flood), 120);
    assert!(!terminal_channel_error(&flood));
    assert!(terminal_channel_error(&MtprotoError::Message(
        "RPC 400: CHANNEL_PRIVATE".into()
    )));
    assert_eq!(
        recovery_wait_secs(&MtprotoError::Message("RPC timeout".into())),
        5
    );
}

#[test]
fn lazy_route_is_unchanged_when_disabled() {
    let open = peers::chat_id_for_channel(1);
    let other = peers::chat_id_for_channel(2);
    let mut lazy = HashSet::new();
    let pending = vec![open, other];
    let full = route_lazy_channels(pending.clone(), open, &HashSet::new(), false, &mut lazy);
    assert_eq!(full, pending);
    assert!(lazy.is_empty());
}

#[test]
fn lazy_batch_leaves_unusable_peers_queued_and_restores_after_failure() {
    let ready = peers::chat_id_for_channel(1);
    let waiting = peers::chat_id_for_channel(2);
    let mut queue = HashSet::new();
    queue.insert(waiting);
    queue.insert(ready);
    let batch = take_lazy_batch(&mut queue, |id| id == ready, LAZY_CHANNEL_BATCH);
    assert_eq!(batch, vec![ready]);
    assert!(queue.contains(&waiting));
    assert!(!queue.contains(&ready));
    restore_lazy_batch(&mut queue, &batch);
    assert!(queue.contains(&ready));
    assert!(queue.contains(&waiting));
}

#[test]
fn open_channel_watcher_is_not_catch_up() {
    let open = peers::chat_id_for_channel(1);
    let mut queue = VecDeque::new();
    prepare_channel_recovery(&mut queue, vec![open], open, 100);
    let entry = queue.pop_front().unwrap();
    finish_channel_recovery(&mut queue, entry, true, Some(1), open, 100);
    assert!(queue.front().unwrap().watching);
    assert!(!gap_recovery_pending(&queue));
    assert!(!catch_up_active(true, gap_recovery_pending(&queue), false));
    queue.push_back(ChannelRecovery {
        chat_id: peers::chat_id_for_channel(2),
        due_at: 100,
        watching: false,
    });
    assert!(catch_up_active(true, gap_recovery_pending(&queue), false,));
}

#[test]
fn opening_a_queued_channel_leaves_the_lazy_batch() {
    let open = peers::chat_id_for_channel(1);
    let other = peers::chat_id_for_channel(2);
    let mut lazy = HashSet::new();
    lazy.insert(open);
    lazy.insert(other);
    let mut recovery = VecDeque::new();
    pull_priority_channels(&mut lazy, &mut recovery, open, &HashSet::new(), 50);
    assert!(!lazy.contains(&open));
    assert!(lazy.contains(&other));
    let promoted = recovery.iter().find(|entry| entry.chat_id == open).unwrap();
    assert!(!promoted.watching);
    assert_eq!(promoted.due_at, 50);
    let batch = take_lazy_batch(&mut lazy, |_| true, LAZY_CHANNEL_BATCH);
    assert_eq!(batch, vec![other]);
}

#[test]
fn lazy_rpc_failure_restores_without_dropping_transport() {
    let plan = plan_lazy_failure(false);
    assert!(plan.restore);
    assert!(!plan.drop_transport);
    let fatal = plan_lazy_failure(true);
    assert!(!fatal.restore);
    assert!(!fatal.drop_transport);
}

#[test]
fn lazy_batch_caps_at_one_hundred() {
    let mut queue = HashSet::new();
    for id in 1..=101 {
        queue.insert(peers::chat_id_for_channel(id));
    }
    let batch = take_lazy_batch(&mut queue, |_| true, LAZY_CHANNEL_BATCH);
    assert_eq!(batch.len(), LAZY_CHANNEL_BATCH);
    assert_eq!(queue.len(), 1);
}
