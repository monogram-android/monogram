use std::cell::Cell;
use std::sync::atomic::Ordering;
use std::sync::Arc;

use crate::session_file::{media_from_index, ClientSession, FileSessionStore};
use crate::tcp;
use crate::MtprotoError;

use super::*;

thread_local! {
    pub(crate) static EXTRA_READ_LANE: Cell<bool> = const { Cell::new(false) };
}

pub(crate) fn persist(state: &ClientState) -> Result<(), MtprotoError> {
    // Extra-lane snapshot_id must never replace the main session on disk.
    if EXTRA_READ_LANE.with(Cell::get) {
        return Ok(());
    }
    let store = FileSessionStore::new(&state.session_path);
    store
        .save(&ClientSession {
            snapshot: state.snapshot.clone(),
            user_id: state.user_id,
            peers: state.peers.clone(),
            updates: state.updates.clone(),
            media: media_from_index(&state.media),
            channel_pts: state.channel_pts.clone(),
            channel_recovery: state.channel_recovery.clone(),
            seen_messages: state.seen_messages.clone(),
            logout_tokens: state.logout_tokens.clone(),
            new_session: state.new_session.clone(),
            session_dead: state.session_dead,
            test_dc: state.test_dc,
        })
        .map_err(|e| MtprotoError::Message(e.to_string()))
}

pub(crate) fn session_lease_valid(data: &ClientData, session_id: i64) -> bool {
    data.home_session_id == session_id && !data.session_dead
}

pub(crate) fn expired_session_lease() -> MtprotoError {
    MtprotoError::Message("request cancelled: session changed".into())
}

pub(crate) fn persist_updates_data(client: &Client, session_id: i64) -> Result<(), MtprotoError> {
    // Main RPC persistence holds this same lock. Keep it through the write so
    // an older captured snapshot cannot replace a newer main-lane snapshot.
    let main = client.main.lock();
    let (
        path,
        snapshot,
        user_id,
        peers,
        updates,
        media,
        channel_pts,
        seen_messages,
        logout_tokens,
        new_session,
        session_dead,
        test_dc,
    ) = {
        let d = client.data.lock();
        if !session_lease_valid(&d, session_id) {
            return Err(expired_session_lease());
        }
        (
            d.session_path.clone(),
            main.snapshot.clone(),
            d.user_id,
            d.peers.clone(),
            d.updates.clone(),
            d.media.clone(),
            d.channel_pts.clone(),
            d.seen_messages.clone(),
            d.logout_tokens.clone(),
            d.new_session.clone(),
            d.session_dead,
            d.test_dc,
        )
    };
    let store = FileSessionStore::new(path);
    let session = ClientSession {
        snapshot,
        user_id,
        peers,
        updates,
        media: media_from_index(&media),
        channel_pts,
        channel_recovery: client.data.lock().channel_recovery.clone(),
        seen_messages,
        logout_tokens,
        new_session,
        session_dead,
        test_dc,
    };
    tcp::with_connection_control(&client.connections, || store.save(&session))
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    {
        let mut d = client.data.lock();
        if session_lease_valid(&d, session_id) {
            d.persisted_epoch = d.persist_epoch;
        }
    }
    Ok(())
}

/// Coalesce session writes so getHistory/getChats can return before disk I/O.
/// In-memory state is already merged; this is crash recovery, not the RPC result.
pub(crate) fn schedule_persist(client: &Arc<Client>, session_id: i64) {
    client.persist_queued.store(true, Ordering::Release);
    if client.persist_running.swap(true, Ordering::AcqRel) {
        return;
    }
    let client = Arc::clone(client);
    let _ = std::thread::Builder::new()
        .name("mtproto-persist".into())
        .spawn(move || loop {
            client.persist_queued.store(false, Ordering::Release);
            let skip = {
                let d = client.data.lock();
                d.persist_epoch == d.persisted_epoch
            };
            if !skip {
                let span = crate::perf::span("persist_session");
                let _ = persist_updates_data(&client, session_id);
                drop(span);
            }
            if client.persist_queued.load(Ordering::Acquire) {
                continue;
            }
            client.persist_running.store(false, Ordering::Release);
            if client.persist_queued.load(Ordering::Acquire)
                && !client.persist_running.swap(true, Ordering::AcqRel)
            {
                continue;
            }
            break;
        });
}

pub(crate) fn flush_persist(client: &Arc<Client>) {
    let session_id = client.data.lock().home_session_id;
    schedule_persist(client, session_id);
    let started = std::time::Instant::now();
    while (client.persist_running.load(Ordering::Acquire)
        || client.persist_queued.load(Ordering::Acquire))
        && started.elapsed() < std::time::Duration::from_secs(2)
    {
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
}
