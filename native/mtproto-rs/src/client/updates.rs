use std::collections::VecDeque;

use crate::peers::channel_id_from_chat_id;
use crate::session_file::ChannelRecovery;
use crate::tcp;
use crate::updates_rpc;
use crate::{MtprotoError, UpdateEventDto, UpdatesStateDto};

use super::*;

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
) -> Result<Option<(scheduler::LaneGuard<'_>, parking_lot::MutexGuard<'_, SessionIo>)>, MtprotoError> {
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
    let Some((_gate, mut io)) = lock_updates_lane(&client)? else {
        return Ok(Vec::new());
    };
    let mut recovery = client.data.lock().channel_recovery.clone();
    let (
        api_id,
        mut peers,
        mut media,
        previous,
        home_dc,
        home_auth,
        home_salt,
        home_off,
        open_chat,
        mut channel_pts,
        mut seen_messages,
        session_id,
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
            d.peers.clone(),
            d.media.clone(),
            d.updates.clone(),
            d.home_dc,
            d.home_auth_key.clone(),
            d.home_salt,
            d.home_time_offset,
            d.last_history_chat_id,
            d.channel_pts.clone(),
            d.seen_messages.clone(),
            d.home_session_id,
        )
    };
    apply_home_auth(
        &mut io.snapshot,
        home_dc,
        home_auth.clone(),
        home_salt,
        home_off,
    );
    let before_peers = peers.clone();
    let before_media = media.clone();
    let mut cursor = previous.clone();
    let mut needs_difference = io.transport.is_none()
        || io
            .last_difference
            .map(|last| last.elapsed() >= std::time::Duration::from_secs(60))
            .unwrap_or(true);
    let mut changed = needs_difference;
    let mut slot = io.transport.take();
    crate::rpc::clear_new_session_metadata();
    let drained = with_client_transport(&client, &mut slot, || {
        // 4s sat on the successful-empty edge (4157ms) and on
        // `drain updates` RPC timeout recv=0 / last_ctor=msg_container.
        crate::rpc::with_rpc_timeout_secs(8, || {
            ensure_auth_key_on(&mut io.snapshot)?;
            if cursor.is_none() {
                cursor = Some(updates_rpc::get_updates_state(&mut io.snapshot, api_id)?);
            }
            let mut live = cursor.clone().unwrap();
            let mut pending_channels = Vec::new();
            let mut events = Vec::new();
            if !needs_difference {
                let pushes = crate::rpc::receive_updates(&mut io.snapshot)?;
                io.pending_push.append(pushes);
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
                            // A packet can advance pts/qts/seq without producing
                            // a UI event, so persist every successfully validated
                            // packet, including duplicates.
                            changed = true;
                            events.extend(applied);
                            Ok(true)
                        }
                        Err(MtprotoError::Message(message)) if message == "updates gap" => {
                            Ok(false)
                        }
                        Err(error) => Err(error),
                    }
                });
                match resolve {
                    Ok(recovery_required) => {
                        needs_difference = recovery_required;
                        if recovery_required {
                            changed = true;
                        }
                    }
                    Err(error) => {
                        // An invalid constructor or malformed packet cannot be
                        // retried from the reordering queue. Drop it and recover
                        // from the last committed cursor on a fresh connection.
                        io.pending_push = Default::default();
                        crate::rpc::drop_live_transport();
                        needs_difference = true;
                        changed = true;
                        tcp::wait_reconnect(std::time::Duration::from_millis(50))
                            .map_err(|e| MtprotoError::Message(e.to_string()))?;
                        let _ = error;
                    }
                }
            }
            if needs_difference {
                // Let a waiting interactive RPC take the home lane between
                // update recovery requests. Keep the cursor unchanged so the
                // next drain resumes recovery safely.
                if interactive_request_pending(&client) {
                    needs_difference = false;
                }
            }
            if needs_difference {
                let (page, has_more) = updates_rpc::drain_difference(
                    &mut io.snapshot,
                    api_id,
                    &mut peers,
                    &mut media,
                    &mut live,
                    &mut pending_channels,
                )?;
                // The difference is authoritative for the cursor. Discard
                // packets retained behind the gap before using the new state.
                io.pending_push = Default::default();
                events.extend(page);
                io.last_difference = (!has_more).then(std::time::Instant::now);
            }
            let now = recovery_now();
            prepare_channel_recovery(&mut recovery, pending_channels, open_chat, now);
            // Round robin pages, bounded per poll; a failed channel cannot
            // prevent the common cursor or another channel from progressing.
            let recovery_budget = std::time::Instant::now() + std::time::Duration::from_secs(8);
            for _ in 0..1 {
                if interactive_request_pending(&client)
                    || std::time::Instant::now() >= recovery_budget
                {
                    break;
                }
                let Some(index) = recovery.iter().position(|entry| entry.due_at <= now) else {
                    break;
                };
                let entry = recovery.remove(index).unwrap();
                let chat_id = entry.chat_id;
                let pts = channel_pts.get(&chat_id).copied().unwrap_or(1);
                match updates_rpc::drain_channel_difference(
                    &mut io.snapshot,
                    api_id,
                    &mut peers,
                    &mut media,
                    chat_id,
                    pts,
                ) {
                    Ok(page) => {
                        changed = true;
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
                        prepare_channel_recovery(
                            &mut recovery,
                            page.pending_channels,
                            open_chat,
                            now,
                        );
                    }
                    Err(err) => {
                        if is_unrecoverable_session(&err) {
                            return Err(err);
                        }
                        changed = true;
                        defer_channel_recovery(&mut recovery, entry, &err, recovery_now());
                        crate::rpc::drop_live_transport();
                        break;
                    }
                }
            }
            cursor = Some(live);
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
            Ok(events)
        })
    });
    let events = match drained {
        Ok(events) => {
            io.transport = slot;
            events
        }
        Err(err) => {
            io.transport = None;
            drop(io);
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
    if !changed && new_session.is_none() {
        return Ok(events);
    }
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
        merge_changed_entries(&mut d.peers, &before_peers, peers);
        merge_changed_entries(&mut d.media, &before_media, media);
        d.updates = prefer_newer_cursor(d.updates.clone(), cursor);
        d.channel_pts = channel_pts;
        d.channel_recovery = recovery;
        d.seen_messages = seen_messages;
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
    persist_updates_data(&client, session_id)?;
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
    entry.due_at = now.saturating_add(wait.max(5));
    queue.push_back(entry);
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
