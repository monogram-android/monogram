use std::collections::VecDeque;

use crate::peers::channel_id_from_chat_id;
use crate::session_file::ChannelRecovery;
use crate::tcp;
use crate::updates::updates_rpc;
use crate::{MtprotoError, UpdateEventDto, UpdatesStateDto};

use super::*;

#[cfg(test)]
std::thread_local! {
    static UPDATE_CACHE_CLONES: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
pub(crate) fn take_update_cache_clones() -> u64 {
    UPDATE_CACHE_CLONES.with(|cell| cell.replace(0))
}

fn note_update_cache_clone() {
    crate::perf::count("updates.cache_clone");
    #[cfg(test)]
    UPDATE_CACHE_CLONES.with(|cell| cell.set(cell.get() + 1));
}

struct AppliedDrain {
    peers: crate::HashMap<i64, crate::peers::CachedPeer>,
    before_peers: crate::HashMap<i64, crate::peers::CachedPeer>,
    media: crate::media::MediaIndex,
    before_media: crate::media::MediaIndex,
    channel_pts: crate::HashMap<i64, i32>,
    recovery: VecDeque<ChannelRecovery>,
    seen_messages: crate::HashSet<(i64, i32)>,
    lazy_queue: crate::HashSet<i64>,
    patched_dialogs: Vec<crate::ChatDto>,
    lazy_retry_at: u64,
    stopped_channel: Option<i64>,
    cursor: Option<UpdatesStateDto>,
}

pub fn start_updates(handle: u64) -> Result<(), MtprotoError> {
    let client = get_client(handle)?;
    {
        let mut data = client.data.lock();
        if data.session_dead {
            return Err(MtprotoError::Message(format!(
                "session invalidated: {}",
                data.session_dead_reason.as_deref().unwrap_or("UNKNOWN"),
            )));
        }
        data.updates_started = true;
    }
    Ok(())
}

/// A cursor becomes durable only after `getDifference` applies it. Fetching
/// `updates.getState` is used solely as the in-memory bootstrap cursor in
/// `drain_updates`; persisting that server head could skip a crash-window gap.
pub(crate) fn begin_updates(state: &mut ClientState) {
    state.updates_started = true;
}

pub fn clear_active_dialog(handle: u64) -> Result<(), MtprotoError> {
    let client = get_client(handle)?;
    let session_id = {
        let mut data = client.data.lock();
        data.last_history_chat_id = 0;
        // Keep server-driven recovery hints, but discard the periodic watcher
        // that existed only while this dialog was open.
        data.channel_recovery.retain(|entry| !entry.watching);
        data.home_session_id
    };
    persist_updates_data(&client, session_id)
}

pub(crate) fn lock_updates_lane(
    client: &Client,
) -> Result<
    Option<(
        scheduler::LaneGuard<'_>,
        parking_lot::MutexGuard<'_, SessionIo>,
    )>,
    MtprotoError,
> {
    // Updates share the home transport with main RPCs. Taking the same gate as
    // request batches prevents a just-freed media batch from barging ahead.
    let gate = client
        .main_gate
        .acquire(scheduler::RequestClass::BackgroundRead)?;
    Ok(client.main.try_lock().map(|io| (gate, io)))
}

pub fn drain_updates(handle: u64) -> Result<Vec<UpdateEventDto>, MtprotoError> {
    let client = get_client(handle)?;
    if interactive_request_pending(&client) {
        return Ok(Vec::new());
    }
    let Some((gate, mut io)) = lock_updates_lane(&client)? else {
        return Ok(Vec::new());
    };
    let now = recovery_now();
    let (
        api_id,
        previous,
        home_dc,
        home_auth,
        home_salt,
        home_off,
        open_chat,
        session_id,
        due_recovery,
        lazy_pending,
    ) = {
        let d = client.data.lock();
        if d.session_dead {
            return Err(MtprotoError::Message(format!(
                "session invalidated: {}",
                d.session_dead_reason.as_deref().unwrap_or("UNKNOWN")
            )));
        }
        if d.user_id.is_none() || !d.updates_started {
            return Ok(Vec::new());
        }
        (
            d.api_id,
            d.updates.clone(),
            d.home_dc,
            d.home_auth_key.clone(),
            d.home_salt,
            d.home_time_offset,
            d.last_history_chat_id,
            d.home_session_id,
            d.channel_recovery.iter().any(|entry| entry.due_at <= now)
                || (channel_id_from_chat_id(d.last_history_chat_id).is_some()
                    && !d
                        .channel_recovery
                        .iter()
                        .any(|entry| entry.chat_id == d.last_history_chat_id)),
            d.lazy_channel_updates && !d.lazy_channel_recovery.is_empty() && d.lazy_retry_at <= now,
        )
    };
    apply_home_auth(
        &mut io.snapshot,
        home_dc,
        home_auth.clone(),
        home_salt,
        home_off,
    );
    let mut needs_difference = io.transport.is_none()
        || io
            .last_difference
            .map(|last| last.elapsed() >= std::time::Duration::from_secs(60))
            .unwrap_or(true);
    let mut slot = io.transport.take();
    crate::rpc::clear_new_session_metadata();
    let drained = with_client_transport(&client, &mut slot, || {
        // Receive stays on the idle budget; recovery RPCs use their own 8s scope
        // so a waiting interactive caller can take the lane between pages.
        ensure_auth_key_on(&mut io.snapshot)?;
        let mut cursor = previous.clone();
        if cursor.is_none() {
            cursor = Some(updates_rpc::get_updates_state(&mut io.snapshot, api_id)?);
        }
        if !needs_difference {
            let _span = crate::perf::span("updates.receive");
            let pushes = crate::rpc::receive_updates(&mut io.snapshot)?;
            io.pending_push.append(pushes);
        }
        let has_work =
            needs_difference || !io.pending_push.is_empty() || due_recovery || lazy_pending;
        if !has_work {
            crate::perf::count("updates.empty_fast_path");
            return Ok((Vec::new(), None));
        }
        note_update_cache_clone();
        let mut recovery;
        let mut peers;
        let mut media;
        let mut channel_pts;
        let mut seen_messages;
        let mut lazy_queue;
        let mut lazy_retry_at;
        let lazy_enabled;
        let lazy_exceptions;
        {
            let d = client.data.lock();
            if d.session_dead {
                return Err(MtprotoError::Message(format!(
                    "session invalidated: {}",
                    d.session_dead_reason.as_deref().unwrap_or("UNKNOWN")
                )));
            }
            if d.home_dc != home_dc
                || d.home_auth_key != home_auth
                || !session_lease_valid(&d, session_id)
            {
                return Err(expired_session_lease());
            }
            recovery = d.channel_recovery.clone();
            peers = d.peers.clone();
            media = d.media.clone();
            channel_pts = d.channel_pts.clone();
            seen_messages = d.seen_messages.clone();
            lazy_queue = d.lazy_channel_recovery.clone();
            lazy_retry_at = d.lazy_retry_at;
            lazy_enabled = d.lazy_channel_updates;
            lazy_exceptions = d.lazy_sync_exceptions.clone();
        }
        let before_peers = peers.clone();
        let before_media = media.clone();
        let mut live = cursor.clone().unwrap();
        let mut pending_channels = Vec::new();
        let mut stopped_channel = None;
        let mut events = Vec::new();
        let sync_started = !client.data.lock().is_syncing
            && catch_up_active(!needs_difference, gap_recovery_pending(&recovery), false);
        if sync_started {
            events.push(UpdateEventDto::SyncState { is_syncing: true });
        }
        if !needs_difference {
            let _span = crate::perf::span("updates.apply");
            let resolve = io.pending_push.resolve(std::time::Instant::now(), |push| {
                match updates_rpc::apply_push(
                    push,
                    &mut peers,
                    &mut media,
                    &mut live,
                    &mut channel_pts,
                    &mut pending_channels,
                ) {
                    Ok(applied) => {
                        events.extend(applied);
                        Ok(true)
                    }
                    Err(MtprotoError::Message(message)) if message == "updates gap" => Ok(false),
                    Err(error) => Err(error),
                }
            });
            match resolve {
                Ok(recovery_required) => {
                    needs_difference = recovery_required;
                }
                Err(error) => {
                    // An invalid constructor or malformed packet cannot be
                    // retried from the reordering queue. Drop it and recover
                    // from the last committed cursor on a fresh connection.
                    io.pending_push = Default::default();
                    crate::rpc::drop_live_transport();
                    needs_difference = true;
                    tcp::wait_reconnect(std::time::Duration::from_millis(50))
                        .map_err(|e| MtprotoError::Message(e.to_string()))?;
                    let _ = error;
                }
            }
        }
        let defer_recovery = events
            .iter()
            .any(|event| !matches!(event, UpdateEventDto::SyncState { .. }));
        if needs_difference {
            // Let a waiting interactive RPC take the home lane between
            // update recovery requests. Keep the cursor unchanged so the
            // next drain resumes recovery safely. Deliver already-applied
            // pushes this poll instead of holding the lane for getDifference.
            if interactive_request_pending(&client) || defer_recovery {
                crate::perf::count("updates.defer_difference");
                needs_difference = false;
            }
        }
        if needs_difference {
            if !client.data.lock().is_syncing && !sync_started {
                events.push(UpdateEventDto::SyncState { is_syncing: true });
            }
            let _span = crate::perf::span("updates.difference");
            let (page, has_more) = crate::rpc::with_rpc_timeout_secs(8, || {
                updates_rpc::drain_difference(
                    &mut io.snapshot,
                    api_id,
                    &mut peers,
                    &mut media,
                    &mut live,
                    &mut pending_channels,
                )
            })?;
            // The difference is authoritative for the cursor. Discard
            // packets retained behind the gap before using the new state.
            io.pending_push = Default::default();
            events.extend(page);
            io.last_difference = (!has_more).then(std::time::Instant::now);
        }
        let now = recovery_now();
        let pending_channels = route_lazy_channels(
            pending_channels,
            open_chat,
            &lazy_exceptions,
            lazy_enabled,
            &mut lazy_queue,
        );
        pull_priority_channels(
            &mut lazy_queue,
            &mut recovery,
            open_chat,
            &lazy_exceptions,
            now,
        );
        prepare_channel_recovery(&mut recovery, pending_channels, open_chat, now);
        // Round robin pages, bounded per poll; a failed channel cannot
        // prevent the common cursor or another channel from progressing.
        let recovery_budget = std::time::Instant::now() + std::time::Duration::from_secs(8);
        for _ in 0..1 {
            if interactive_request_pending(&client)
                || defer_recovery
                || std::time::Instant::now() >= recovery_budget
            {
                break;
            }
            let Some(index) = recovery
                .iter()
                .position(|entry| entry.due_at <= now && entry.chat_id == open_chat)
                .or_else(|| recovery.iter().position(|entry| entry.due_at <= now))
            else {
                break;
            };
            let entry = recovery.remove(index).unwrap();
            if !entry.watching
                && !client.data.lock().is_syncing
                && !events
                    .iter()
                    .any(|event| matches!(event, UpdateEventDto::SyncState { is_syncing: true }))
            {
                events.push(UpdateEventDto::SyncState { is_syncing: true });
            }
            let chat_id = entry.chat_id;
            let pts = channel_pts.get(&chat_id).copied().unwrap_or(1);
            let _span = crate::perf::span("updates.channel_diff");
            match crate::rpc::with_rpc_timeout_secs(8, || {
                updates_rpc::drain_channel_difference(
                    &mut io.snapshot,
                    api_id,
                    &mut peers,
                    &mut media,
                    chat_id,
                    pts,
                )
            }) {
                Ok(page) => {
                    channel_pts.insert(chat_id, page.pts.max(pts));
                    events.extend(page.events);
                    finish_channel_recovery(
                        &mut recovery,
                        entry,
                        page.final_page,
                        page.timeout,
                        open_chat,
                        now,
                    );
                    let page_pending = route_lazy_channels(
                        page.pending_channels,
                        open_chat,
                        &lazy_exceptions,
                        lazy_enabled,
                        &mut lazy_queue,
                    );
                    prepare_channel_recovery(&mut recovery, page_pending, open_chat, now);
                }
                Err(err) => {
                    if is_unrecoverable_session(&err) {
                        return Err(err);
                    }
                    if !terminal_channel_error(&err) {
                        defer_channel_recovery(&mut recovery, entry, &err, recovery_now());
                    } else {
                        lazy_queue.remove(&chat_id);
                        if chat_id == open_chat {
                            stopped_channel = Some(chat_id);
                        }
                    }
                    crate::rpc::drop_live_transport();
                    break;
                }
            }
        }
        let mut patched_dialogs = Vec::new();
        pull_priority_channels(
            &mut lazy_queue,
            &mut recovery,
            if stopped_channel.is_some() {
                0
            } else {
                open_chat
            },
            &lazy_exceptions,
            recovery_now(),
        );
        let lazy_batch = if lazy_retry_at <= recovery_now()
            && !interactive_request_pending(&client)
            && !defer_recovery
            && !needs_difference
        {
            take_lazy_batch(
                &mut lazy_queue,
                |chat_id| crate::peers::usable_cached_peer(&peers, chat_id).is_some(),
                LAZY_CHANNEL_BATCH,
            )
        } else {
            Vec::new()
        };
        if !lazy_batch.is_empty() {
            let _span = crate::perf::span("updates.lazy_channels");
            match crate::rpc::with_rpc_timeout_secs(8, || {
                updates_rpc::drain_lazy_channels(
                    &mut io.snapshot,
                    api_id,
                    &mut peers,
                    &mut media,
                    &mut channel_pts,
                    &lazy_batch,
                )
            }) {
                Ok(page) => {
                    lazy_retry_at = recovery_now().saturating_add(5);
                    restore_lazy_batch(&mut lazy_queue, &page.unresolved);
                    patched_dialogs.extend(page.chats.iter().cloned());
                    events.extend(lazy_dialog_events(page.chats));
                }
                Err(err) => {
                    let plan = plan_lazy_failure(is_unrecoverable_session(&err));
                    if !plan.restore {
                        return Err(err);
                    }
                    if !terminal_channel_error(&err) {
                        restore_lazy_batch(&mut lazy_queue, &lazy_batch);
                    }
                    lazy_retry_at = recovery_now().saturating_add(recovery_wait_secs(&err));
                    if plan.drop_transport {
                        crate::rpc::drop_live_transport();
                    }
                }
            }
        }
        let mut saw_chats = false;
        if seen_messages.len() > 4_000 {
            seen_messages.clear();
        }
        events.retain(|event| match event {
            UpdateEventDto::ChatsChanged if saw_chats => false,
            UpdateEventDto::ChatsChanged => {
                saw_chats = true;
                true
            }
            UpdateEventDto::NewMessage { message } => {
                seen_messages.insert((message.chat_id, message.id))
            }
            UpdateEventDto::MessageEdited { message } => {
                seen_messages.insert((message.chat_id, message.id));
                true
            }
            UpdateEventDto::MessagesDeleted {
                chat_id,
                message_ids,
            } => {
                let inferred_chat = *chat_id;
                for message_id in message_ids {
                    if let Some(chat) = inferred_chat {
                        seen_messages.remove(&(chat, *message_id));
                    } else {
                        seen_messages.retain(|(_, id)| id != message_id);
                    }
                }
                true
            }
            _ => true,
        });
        Ok((
            events,
            Some(AppliedDrain {
                peers,
                before_peers,
                media,
                before_media,
                channel_pts,
                recovery,
                seen_messages,
                lazy_queue,
                patched_dialogs,
                lazy_retry_at,
                stopped_channel,
                cursor: Some(live),
            }),
        ))
    });
    let (mut events, applied) = match drained {
        Ok(pair) => {
            io.transport = slot;
            pair
        }
        Err(err) => {
            io.transport = None;
            drop(io);
            drop(gate);
            if is_unrecoverable_session(&err) {
                with_client_mut(handle, |state| {
                    if state.snapshot.session_id == session_id {
                        mark_session_dead(state, &err);
                    }
                    Ok(())
                })?;
            }
            return Err(err);
        }
    };
    let new_session = crate::rpc::take_new_session_metadata();
    let Some(applied) = applied else {
        drop(io);
        drop(gate);
        if let Some(metadata) = new_session {
            {
                let mut d = client.data.lock();
                if d.home_dc == home_dc
                    && d.home_auth_key == home_auth
                    && session_lease_valid(&d, session_id)
                {
                    d.new_session = Some(metadata);
                    d.persist_epoch = d.persist_epoch.saturating_add(1);
                }
            }
            schedule_persist(&client, session_id);
        }
        return Ok(events);
    };
    let persist_needed;
    let is_syncing_now = catch_up_active(
        io.last_difference.is_some(),
        gap_recovery_pending(&applied.recovery),
        false,
    );
    let should_emit_sync_state;
    {
        let mut d = client.data.lock();
        // A completed old-account poll must not repopulate state after logout
        // or authorization migration on the main lane.
        if d.home_dc != home_dc
            || d.home_auth_key != home_auth
            || !session_lease_valid(&d, session_id)
        {
            io.transport = None;
            return Err(expired_session_lease());
        }
        let peers_changed =
            merge_changed_entries(&mut d.peers, &applied.before_peers, applied.peers);
        let media_changed =
            merge_changed_entries(&mut d.media, &applied.before_media, applied.media);
        let next_cursor = prefer_newer_cursor(d.updates.clone(), applied.cursor);
        should_emit_sync_state = sync_edge(
            d.is_syncing
                || events
                    .iter()
                    .any(|event| matches!(event, UpdateEventDto::SyncState { is_syncing: true })),
            is_syncing_now,
        );
        d.is_syncing = is_syncing_now;
        persist_needed = new_session.is_some()
            || peers_changed
            || media_changed
            || d.channel_pts != applied.channel_pts
            || d.channel_recovery != applied.recovery
            || d.seen_messages != applied.seen_messages
            || next_cursor != d.updates;
        if persist_needed {
            d.persist_epoch = d.persist_epoch.saturating_add(1);
        }
        d.updates = next_cursor;
        d.channel_pts = applied.channel_pts;
        d.channel_recovery = applied.recovery;
        d.seen_messages = applied.seen_messages;
        d.lazy_channel_recovery = applied.lazy_queue;
        d.lazy_retry_at = applied.lazy_retry_at;
        if applied.stopped_channel == Some(d.last_history_chat_id) {
            d.last_history_chat_id = 0;
        }
        for chat in applied.patched_dialogs {
            d.dialogs.insert(chat.id, chat);
        }
        if new_session.is_some() {
            d.new_session = new_session.clone();
        }
        d.updates_started = true;
        // The updates lane can receive a fresher server salt/time correction.
        // Feed it back to the home lane on the next RPC instead of repeatedly
        // restoring stale values from the previous main-lane snapshot.
        if io.snapshot.dc_id == d.home_dc && io.snapshot.auth_key == d.home_auth_key {
            d.home_salt = io.snapshot.server_salt;
            d.home_time_offset = io.snapshot.time_offset_micros;
        }
    }
    drop(io);
    drop(gate);
    schedule_file_cleanup(&client);
    if persist_needed {
        crate::perf::count("updates.persist");
        schedule_persist(&client, session_id);
    }
    if should_emit_sync_state {
        events.push(UpdateEventDto::SyncState {
            is_syncing: is_syncing_now,
        });
    }
    Ok(events)
}

pub fn get_updates_state(handle: u64) -> Result<UpdatesStateDto, MtprotoError> {
    with_read_lane(handle, |state| {
        let cursor = call_with_migrate(state, |state| {
            updates_rpc::get_updates_state(&mut state.snapshot, state.api_id)
        })?;
        // Reading the server head is not applying its updates. Advancing the
        // recovery cursor here would skip messages on the next getDifference.
        Ok(cursor)
    })
}

pub(crate) fn recovery_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub(crate) fn defer_channel_recovery(
    queue: &mut VecDeque<ChannelRecovery>,
    mut entry: ChannelRecovery,
    error: &MtprotoError,
    now: u64,
) {
    entry.due_at = now.saturating_add(recovery_wait_secs(error));
    queue.push_back(entry);
}

pub(crate) fn terminal_channel_error(error: &MtprotoError) -> bool {
    matches!(error, MtprotoError::Message(message) if
        message.contains("CHANNEL_PRIVATE") || message.contains("CHANNEL_INVALID")
        || message.contains("PEER_ID_INVALID"))
}

pub(crate) fn recovery_wait_secs(error: &MtprotoError) -> u64 {
    let wait = match error {
        MtprotoError::Message(message) => ["FLOOD_WAIT_", "FLOOD_PREMIUM_WAIT_"]
            .iter()
            .filter_map(|prefix| message.split_once(prefix))
            .filter_map(|(_, suffix)| {
                suffix
                    .split(|c: char| !c.is_ascii_digit())
                    .next()?
                    .parse::<u64>()
                    .ok()
            })
            .max()
            .unwrap_or(5),
        _ => 5,
    };
    wait.max(5)
}

pub(crate) fn prepare_channel_recovery(
    queue: &mut VecDeque<ChannelRecovery>,
    mut pending: Vec<i64>,
    open_chat: i64,
    now: u64,
) {
    queue.retain(|entry| !entry.watching || entry.chat_id == open_chat);
    for chat_id in pending
        .drain(..)
        .filter(|id| channel_id_from_chat_id(*id).is_some())
    {
        if let Some(entry) = queue.iter_mut().find(|entry| entry.chat_id == chat_id) {
            if entry.watching {
                entry.watching = false;
                entry.due_at = now;
            }
        } else {
            queue.push_back(ChannelRecovery {
                chat_id,
                due_at: now,
                watching: false,
            });
        }
    }
    if channel_id_from_chat_id(open_chat).is_some()
        && !queue.iter().any(|entry| entry.chat_id == open_chat)
    {
        queue.push_back(ChannelRecovery {
            chat_id: open_chat,
            due_at: now,
            watching: true,
        });
    }
}

pub(crate) fn finish_channel_recovery(
    queue: &mut VecDeque<ChannelRecovery>,
    mut entry: ChannelRecovery,
    final_page: bool,
    timeout: Option<i32>,
    open_chat: i64,
    now: u64,
) {
    if !final_page || entry.chat_id == open_chat {
        entry.watching = final_page;
        entry.due_at = now.saturating_add(if final_page {
            timeout.unwrap_or(1).max(1) as u64
        } else {
            0
        });
        queue.push_back(entry);
    }
}

pub(crate) const LAZY_CHANNEL_BATCH: usize = 100;

pub(crate) fn sync_edge(previous: bool, now: bool) -> bool {
    previous != now
}

/// Difference still running or a channel gap still queued.
/// A `watching` entry is the open channel's short poll, not catch-up.
pub(crate) fn catch_up_active(
    difference_settled: bool,
    recovery_pending: bool,
    lazy_pending: bool,
) -> bool {
    !difference_settled || recovery_pending || lazy_pending
}

pub(crate) fn gap_recovery_pending(recovery: &VecDeque<ChannelRecovery>) -> bool {
    recovery.iter().any(|entry| !entry.watching)
}

/// Chats the user opened, or listed as exceptions, must not stay on the lazy
/// batch. They go to `getChannelDifference` even if an earlier poll queued them.
pub(crate) fn pull_priority_channels(
    lazy_queue: &mut crate::HashSet<i64>,
    recovery: &mut VecDeque<ChannelRecovery>,
    open_chat: i64,
    exceptions: &crate::HashSet<i64>,
    now: u64,
) {
    let promoted: Vec<i64> = lazy_queue
        .iter()
        .copied()
        .filter(|id| *id == open_chat || exceptions.contains(id))
        .collect();
    for chat_id in promoted {
        lazy_queue.remove(&chat_id);
        if let Some(entry) = recovery.iter_mut().find(|entry| entry.chat_id == chat_id) {
            entry.watching = false;
            entry.due_at = now;
        } else {
            recovery.push_back(ChannelRecovery {
                chat_id,
                due_at: now,
                watching: false,
            });
        }
    }
}

pub(crate) struct LazyFailurePlan {
    pub restore: bool,
    pub drop_transport: bool,
}

/// A lazy `getPeerDialogs` failure restores the batch. It does not drop the
/// update transport. An unrecoverable session still fails the drain.
pub(crate) fn plan_lazy_failure(unrecoverable: bool) -> LazyFailurePlan {
    if unrecoverable {
        LazyFailurePlan {
            restore: false,
            drop_transport: false,
        }
    } else {
        LazyFailurePlan {
            restore: true,
            drop_transport: false,
        }
    }
}

/// Preview offscreen dialogs, but retain durable difference recovery for every gap.
pub(crate) fn route_lazy_channels(
    pending: Vec<i64>,
    open_chat: i64,
    exceptions: &crate::HashSet<i64>,
    lazy_enabled: bool,
    lazy_queue: &mut crate::HashSet<i64>,
) -> Vec<i64> {
    if !lazy_enabled {
        return pending;
    }
    let mut full = Vec::new();
    for chat_id in pending {
        if chat_id == open_chat || exceptions.contains(&chat_id) {
            full.push(chat_id);
        } else if channel_id_from_chat_id(chat_id).is_some() {
            lazy_queue.insert(chat_id);
            full.push(chat_id);
        }
    }
    full
}

/// Takes at most `limit` peers that can be sent. Ids without a usable peer stay queued.
pub(crate) fn take_lazy_batch(
    queue: &mut crate::HashSet<i64>,
    mut usable: impl FnMut(i64) -> bool,
    limit: usize,
) -> Vec<i64> {
    let mut ids: Vec<i64> = queue.iter().copied().collect();
    ids.sort_unstable();
    let mut ready = Vec::new();
    for id in ids {
        if ready.len() == limit {
            break;
        }
        if usable(id) {
            queue.remove(&id);
            ready.push(id);
        }
    }
    ready
}

pub(crate) fn restore_lazy_batch(queue: &mut crate::HashSet<i64>, batch: &[i64]) {
    queue.extend(batch.iter().copied());
}

pub(crate) fn lazy_dialog_events(chats: Vec<crate::ChatDto>) -> Vec<UpdateEventDto> {
    if chats.is_empty() {
        Vec::new()
    } else {
        vec![UpdateEventDto::DialogsPatched { chats }]
    }
}
