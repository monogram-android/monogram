use std::cell::Cell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};

use parking_lot::Mutex;

use crate::session_file::{media_from_index, ClientSession, FileSessionStore};
use crate::tcp;
use crate::MtprotoError;

use super::*;

/// Orders session file writes without holding the home RPC lane, and remembers
/// the last committed save per session file. A process-wide counter is wrong:
/// another client's save would make this file look stale and skip its write.
static PERSIST_ORDER: LazyLock<Mutex<HashMap<PathBuf, Arc<AtomicU64>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
// Disk writes serialize independently from generation lookups. Snapshot capture
// holds the main lane and must never wait for another writer's fsync.
static PERSIST_WRITE: Mutex<()> = Mutex::new(());

fn persist_generation(path: &Path) -> Arc<AtomicU64> {
    PERSIST_ORDER.lock().entry(path.to_path_buf())
        .or_insert_with(|| Arc::new(AtomicU64::new(0))).clone()
}

fn bump_persist_clock(clock: &AtomicU64) {
    let _ = clock.fetch_update(Ordering::AcqRel, Ordering::Acquire,
        |value| Some(value.saturating_add(1)));
}

/// True when this capture must not be written. A generation change means a
/// direct `persist` committed while the copy was in flight. An epoch change
/// means a newer snapshot was committed and another persist owns it.
pub(crate) fn captured_session_is_stale(
    captured_generation: u64,
    current_generation: u64,
    captured_epoch: u64,
    current_epoch: u64,
) -> bool {
    captured_generation != current_generation || captured_epoch != current_epoch
}

thread_local! {
    pub(crate) static EXTRA_READ_LANE: Cell<bool> = const { Cell::new(false) };
}

pub(crate) fn persist(state: &ClientState) -> Result<(), MtprotoError> {
    // Extra-lane snapshot_id must never replace the main session on disk.
    if EXTRA_READ_LANE.with(Cell::get) {
        return Ok(());
    }
    let session = ClientSession {
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
    };
    let clock = persist_generation(&state.session_path);
    let _write = PERSIST_WRITE.lock();
    FileSessionStore::new(&state.session_path)
        .save(&session)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    bump_persist_clock(&clock);
    Ok(())
}

pub(crate) fn session_lease_valid(data: &ClientData, session_id: i64) -> bool {
    data.home_session_id == session_id && !data.session_dead
}

pub(crate) fn expired_session_lease() -> MtprotoError {
    MtprotoError::Message("request cancelled: session changed".into())
}

pub(crate) fn persist_updates_data(client: &Client, session_id: i64) -> Result<(), MtprotoError> {
    // Copy under the home lane, then release it before the disk write. Chat
    // opens, media, and channel info share that lane and were waiting out the
    // whole save.
    let (path, session, epoch, generation) = {
        let main = client.main.lock();
        let d = client.data.lock();
        if !session_lease_valid(&d, session_id) {
            return Err(expired_session_lease());
        }
        let session = ClientSession {
            snapshot: main.snapshot.clone(),
            user_id: d.user_id,
            peers: d.peers.clone(),
            updates: d.updates.clone(),
            media: media_from_index(&d.media),
            channel_pts: d.channel_pts.clone(),
            channel_recovery: d.channel_recovery.clone(),
            seen_messages: d.seen_messages.clone(),
            logout_tokens: d.logout_tokens.clone(),
            new_session: d.new_session.clone(),
            session_dead: d.session_dead,
            test_dc: d.test_dc,
        };
        let path = d.session_path.clone();
        let epoch = d.persist_epoch;
        drop(d);
        // Read the clock while the home lane is still held, so `persist`
        // cannot commit this file between the copy and the snapshot.
        let generation = persist_generation(&path).load(Ordering::Acquire);
        (path, session, epoch, generation)
    };
    let clock = persist_generation(&path);
    let _write = PERSIST_WRITE.lock();
    {
        let d = client.data.lock();
        if !session_lease_valid(&d, session_id) {
            return Err(expired_session_lease());
        }
        // A newer snapshot was committed while we copied. The persist loop
        // writes that one; do not replace it with this older capture.
        if captured_session_is_stale(
            generation,
            clock.load(Ordering::Acquire),
            epoch,
            d.persist_epoch,
        ) {
            return Ok(());
        }
    }
    let store = FileSessionStore::new(&path);
    tcp::with_connection_control(&client.connections, || store.save(&session))
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    bump_persist_clock(&clock);
    let mut d = client.data.lock();
    if session_lease_valid(&d, session_id) && d.persisted_epoch < epoch {
        d.persisted_epoch = epoch;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generation_lookup_does_not_wait_for_disk_write() {
        let write = PERSIST_WRITE.lock();
        let (tx, rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let clock = persist_generation(Path::new("generation-lookup-test"));
            tx.send(clock.load(Ordering::Acquire)).unwrap();
        });
        let result = rx.recv_timeout(std::time::Duration::from_millis(500));
        drop(write);
        worker.join().unwrap();
        assert_eq!(result.ok(), Some(0));
    }

    #[test]
    fn generations_are_per_file_and_visible_to_existing_captures() {
        let first = persist_generation(Path::new("generation-first-test"));
        let same = persist_generation(Path::new("generation-first-test"));
        let other = persist_generation(Path::new("generation-other-test"));
        let previous = first.load(Ordering::Acquire);
        let unrelated = other.load(Ordering::Acquire);
        bump_persist_clock(&first);
        assert_eq!(same.load(Ordering::Acquire), previous + 1);
        assert_eq!(other.load(Ordering::Acquire), unrelated);
        assert!(captured_session_is_stale(previous, same.load(Ordering::Acquire), 1, 1));
    }
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
