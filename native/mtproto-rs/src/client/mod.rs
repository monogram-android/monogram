//! Tellers-backed MTProto client handles.
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/datacenter
//! https://core.telegram.org/api/auth

mod auth;
mod dispatch;
mod extras;
mod lanes;
mod media_download;
mod persist;
mod session;
mod updates;

pub use auth::*;
pub use dispatch::*;
pub use extras::*;
pub use lanes::*;
pub use media_download::*;
pub(crate) use persist::*;
pub(crate) use session::*;
pub use updates::*;

use crate::{HashMap, HashMapExt, HashSet, HashSetExt};
use std::cell::Cell;
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};

use parking_lot::Mutex;
use tellers_mtproto_session::{OsRandom, Snapshot};

use crate::media::MediaIndex;
use crate::peers::CachedPeer;
use crate::scheduler;
use crate::session_file::{media_to_index, ChannelRecovery, FileSessionStore};
use crate::tcp;
use crate::{MtprotoError, UpdatesStateDto};

pub(crate) static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
pub(crate) static CLIENTS: LazyLock<Mutex<HashMap<u64, Arc<Client>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

pub(crate) const DEFAULT_DC_ID: i32 = 2;

pub(crate) struct ClientData {
    pub(crate) api_id: i32,
    pub(crate) api_hash: String,
    pub(crate) session_path: PathBuf,
    pub(crate) user_id: Option<i64>,
    pub(crate) peers: HashMap<i64, CachedPeer>,
    pub(crate) dialogs: crate::IndexMap<i64, crate::ChatDto>,
    pub(crate) updates: Option<UpdatesStateDto>,
    pub(crate) media: MediaIndex,
    pub(crate) updates_started: bool,
    pub(crate) session_dead: bool,
    /// Catalog token of the error that killed the session (`AUTH_KEY_DUPLICATED`).
    pub(crate) session_dead_reason: Option<String>,
    pub(crate) last_history_chat_id: i64,
    pub(crate) channel_pts: HashMap<i64, i32>,
    pub(crate) channel_recovery: VecDeque<ChannelRecovery>,
    pub(crate) seen_messages: HashSet<(i64, i32)>,
    pub(crate) logout_tokens: Vec<Vec<u8>>,
    pub(crate) new_session: Option<crate::rpc::NewSessionMetadata>,
    pub(crate) home_dc: i32,
    pub(crate) home_session_id: i64,
    pub(crate) home_auth_key: Option<Vec<u8>>,
    pub(crate) home_salt: i64,
    pub(crate) home_time_offset: i64,
    pub(crate) test_dc: bool,
    pub(crate) last_inline: Option<LastInlineQuery>,
    pub(crate) persist_epoch: u64,
    pub(crate) persisted_epoch: u64,
}

#[derive(Clone)]
pub(crate) struct LastInlineQuery {
    pub(crate) chat_id: i64,
    pub(crate) bot_id: i64,
    pub(crate) query: crate::CompactString,
    pub(crate) offset: crate::CompactString,
    pub(crate) last_refresh: Option<std::time::Instant>,
}

pub(crate) struct Client {
    pub(crate) _session_key: Option<Arc<crate::session_crypto::SessionKey>>,
    pub(crate) connections: Arc<tcp::ConnectionControl>,
    pub(crate) data: Mutex<ClientData>,
    pub(crate) main_gate: scheduler::LaneGate,
    pub(crate) main: Mutex<SessionIo>,
    /// Overlapping reads; never the updates subscriber.
    pub(crate) rpc: [Lane; scheduler::READ_LANES],
    /// File RPCs on separate sessions.
    pub(crate) media: crate::SmallVec<[Lane; scheduler::MAX_MEDIA_LANES]>,
    pub(crate) persist_queued: AtomicBool,
    pub(crate) persist_running: AtomicBool,
    pub(crate) interactive_waiters: AtomicU64,
}

/// Combined view for existing RPC closures. Snapshot lives on the main lane;
/// `data` is copied in/out so the data mutex is not held across TCP.
pub(crate) struct ClientState {
    pub(crate) api_id: i32,
    pub(crate) api_hash: String,
    pub(crate) session_path: PathBuf,
    pub(crate) snapshot: Snapshot,
    pub(crate) user_id: Option<i64>,
    pub(crate) peers: HashMap<i64, CachedPeer>,
    pub(crate) updates: Option<UpdatesStateDto>,
    pub(crate) media: MediaIndex,
    pub(crate) updates_started: bool,
    pub(crate) channel_pts: HashMap<i64, i32>,
    pub(crate) channel_recovery: VecDeque<ChannelRecovery>,
    pub(crate) seen_messages: HashSet<(i64, i32)>,
    pub(crate) logout_tokens: Vec<Vec<u8>>,
    pub(crate) new_session: Option<crate::rpc::NewSessionMetadata>,
    /// Home auth key is dead (401 AUTH_KEY_* / SESSION_*). Fail RPCs without more network.
    pub(crate) session_dead: bool,
    pub(crate) session_dead_reason: Option<String>,
    pub(crate) test_dc: bool,
    pub(crate) last_inline: Option<LastInlineQuery>,
}

pub(crate) fn prefer_newer_cursor(
    current: Option<UpdatesStateDto>,
    incoming: Option<UpdatesStateDto>,
) -> Option<UpdatesStateDto> {
    match (current, incoming) {
        (None, x) | (x, None) => x,
        (Some(a), Some(b)) => Some(UpdatesStateDto {
            pts: a.pts.max(b.pts),
            qts: a.qts.max(b.qts),
            date: a.date.max(b.date),
            seq: a.seq.max(b.seq),
        }),
    }
}

// Commit only this lane's changes. An unchanged cloned entry must never
// overwrite data fetched by another lane while this lane was on the network.
pub(crate) fn merge_changed_entries<K: Eq + std::hash::Hash, V: PartialEq>(
    current: &mut HashMap<K, V>,
    before: &HashMap<K, V>,
    incoming: HashMap<K, V>,
) -> bool {
    let mut changed = false;
    current.retain(|key, value| {
        let keep = incoming.contains_key(key) || before.get(key) != Some(value);
        if !keep {
            changed = true;
        }
        keep
    });
    for (key, value) in incoming {
        if before.get(&key) != Some(&value) && current.get(&key) == before.get(&key) {
            current.insert(key, value);
            changed = true;
        }
    }
    changed
}

pub(crate) fn get_client(handle: u64) -> Result<Arc<Client>, MtprotoError> {
    CLIENTS
        .lock()
        .get(&handle)
        .cloned()
        .ok_or(MtprotoError::UnknownClient)
}

pub fn create_client(api_id: i32, api_hash: String, session_path: String) -> u64 {
    let path = PathBuf::from(session_path);
    let store = FileSessionStore::new(&path);
    let (loaded, load_failed) = match store.load() {
        Ok(session) => (session, false),
        Err(_) => (None, true),
    };
    let channel_recovery = loaded
        .as_ref()
        .map(|s| s.channel_recovery.clone())
        .unwrap_or_default();
    let (
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
    ) = match loaded {
        Some(session) => (
            session.snapshot,
            session.user_id,
            session.peers,
            session.updates,
            media_to_index(&session.media),
            session.channel_pts,
            session.seen_messages,
            session.logout_tokens,
            session.new_session,
            session.session_dead,
            session.test_dc,
        ),
        None => (
            Snapshot::new(DEFAULT_DC_ID, &mut OsRandom).expect("snapshot"),
            None,
            HashMap::new(),
            None,
            MediaIndex::new(),
            HashMap::new(),
            HashSet::new(),
            Vec::new(),
            None,
            load_failed,
            false,
        ),
    };
    crate::rpc::set_use_test_dc(test_dc);
    let media_snapshot = fork_session(&snapshot);
    let rpc_snapshot = fork_session(&snapshot);
    let rpc_snapshot_b = fork_session(&snapshot);
    let media_snapshots: Vec<Snapshot> = (0..scheduler::MAX_MEDIA_LANES)
        .map(|index| {
            if index == 0 {
                media_snapshot.clone()
            } else {
                fork_session(&snapshot)
            }
        })
        .collect();
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    CLIENTS.lock().insert(
        handle,
        Arc::new(Client {
            _session_key: crate::session_crypto::key_for(&path),
            connections: Arc::new(tcp::ConnectionControl::default()),
            data: Mutex::new(ClientData {
                api_id,
                api_hash,
                session_path: path,
                user_id,
                peers,
                dialogs: crate::IndexMap::default(),
                updates,
                media,
                updates_started: false,
                session_dead,
                session_dead_reason: None,
                last_history_chat_id: 0,
                channel_pts,
                channel_recovery,
                seen_messages,
                logout_tokens,
                new_session,
                home_dc: snapshot.dc_id,
                home_session_id: snapshot.session_id,
                home_auth_key: snapshot.auth_key.clone(),
                home_salt: snapshot.server_salt,
                home_time_offset: snapshot.time_offset_micros,
                test_dc,
                last_inline: None,
                persist_epoch: 0,
                persisted_epoch: 0,
            }),
            main_gate: scheduler::LaneGate::new(),
            main: Mutex::new(SessionIo {
                pending_push: Default::default(),
                last_difference: None,
                snapshot,
                transport: None,
            }),
            rpc: [
                Lane::new(SessionIo {
                    pending_push: Default::default(),
                    last_difference: None,
                    snapshot: rpc_snapshot,
                    transport: None,
                }),
                Lane::new(SessionIo {
                    pending_push: Default::default(),
                    last_difference: None,
                    snapshot: rpc_snapshot_b,
                    transport: None,
                }),
            ],
            media: media_snapshots
                .into_iter()
                .map(|snapshot| {
                    Lane::new(SessionIo {
                        snapshot,
                        pending_push: Default::default(),
                        last_difference: None,
                        transport: None,
                    })
                })
                .collect(),
            persist_queued: AtomicBool::new(false),
            persist_running: AtomicBool::new(false),
            interactive_waiters: AtomicU64::new(0),
        }),
    );
    handle
}

pub(crate) fn interactive_request_pending(client: &Client) -> bool {
    client.interactive_waiters.load(Ordering::Acquire) != 0
}

pub(crate) struct InteractiveWaiter<'a>(pub(crate) &'a AtomicU64);

impl Drop for InteractiveWaiter<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

pub(crate) fn with_interactive_client_mut<T>(
    handle: u64,
    f: impl FnOnce(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    with_client_mut(handle, f)
}

pub fn destroy_client(handle: u64) {
    let client = CLIENTS.lock().remove(&handle);
    if let Some(client) = client {
        flush_persist(&client);
        client.connections.close();
    }
}

pub(crate) fn with_client_transport<T>(
    client: &Client,
    slot: &mut Option<crate::rpc::LiveTransport>,
    body: impl FnOnce() -> T,
) -> T {
    tcp::with_connection_control(&client.connections, || {
        crate::rpc::with_live_transport(slot, body)
    })
}

pub(crate) fn remember_dialogs(handle: u64, chats: &[crate::ChatDto]) {
    let Ok(client) = get_client(handle) else {
        return;
    };
    let mut data = client.data.lock();
    for chat in chats {
        data.dialogs.insert(chat.id, chat.clone());
    }
}

pub fn client_exists(handle: u64) -> bool {
    CLIENTS.lock().contains_key(&handle)
}

pub fn client_api_id(handle: u64) -> i32 {
    get_client(handle)
        .map(|c| c.data.lock().api_id)
        .unwrap_or(0)
}

pub(crate) fn with_client_mut<T>(
    handle: u64,
    f: impl FnOnce(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    let client = get_client(handle)?;
    let class = scheduler::current_class();
    let _waiter = match class {
        scheduler::RequestClass::InteractiveRead | scheduler::RequestClass::InteractiveWrite => {
            client.interactive_waiters.fetch_add(1, Ordering::AcqRel);
            Some(InteractiveWaiter(&client.interactive_waiters))
        }
        _ => None,
    };
    let _gate = {
        let _wait = crate::perf::span("gate_wait.main");
        client.main_gate.acquire(class)?
    };
    let mut io = lock_request_lane(&client.main, "lane_wait.main")?;
    let mut state = {
        let d = client.data.lock();
        ClientState {
            api_id: d.api_id,
            api_hash: d.api_hash.clone(),
            session_path: d.session_path.clone(),
            snapshot: io.snapshot.clone(),
            user_id: d.user_id,
            peers: d.peers.clone(),
            updates: d.updates.clone(),
            media: d.media.clone(),
            updates_started: d.updates_started,
            channel_pts: d.channel_pts.clone(),
            channel_recovery: d.channel_recovery.clone(),
            seen_messages: d.seen_messages.clone(),
            logout_tokens: d.logout_tokens.clone(),
            new_session: d.new_session.clone(),
            session_dead: d.session_dead,
            session_dead_reason: d.session_dead_reason.clone(),
            test_dc: d.test_dc,
            last_inline: d.last_inline.clone(),
        }
    };
    crate::rpc::set_use_test_dc(state.test_dc);
    crate::rpc::clear_new_session_metadata();
    let before_peers = state.peers.clone();
    let before_media = state.media.clone();
    let before_user_id = state.user_id;
    let mut slot = io.transport.take();
    let before_snapshot = io.snapshot.clone();
    let result = with_client_transport(&client, &mut slot, || f(&mut state));
    if let Some(metadata) = crate::rpc::take_new_session_metadata() {
        state.new_session = Some(metadata);
    }
    // A session/auth-key or session_id change invalidates the old TCP lane.
    // Never put a transport bound to the previous session back into the slot
    // after auth restart, bad-msg recovery, DC migration, or key recreation.
    let snapshot_changed = before_snapshot.dc_id != state.snapshot.dc_id
        || before_snapshot.auth_key != state.snapshot.auth_key
        || before_snapshot.session_id != state.snapshot.session_id;
    io.transport = if snapshot_changed || state.session_dead {
        None
    } else {
        slot
    };
    io.snapshot = state.snapshot.clone();
    if snapshot_changed || state.session_dead {
        io.pending_push = Default::default();
        io.last_difference = None;
    }
    let identity_changed =
        before_user_id != state.user_id || before_snapshot.auth_key != state.snapshot.auth_key;
    {
        let mut d = client.data.lock();
        d.user_id = state.user_id;
        if identity_changed {
            d.peers = state.peers;
            d.media = state.media;
            d.updates = state.updates;
            d.channel_pts = state.channel_pts;
            d.channel_recovery = state.channel_recovery;
            d.seen_messages = state.seen_messages;
            d.updates_started = state.updates_started;
        } else {
            let _ = merge_changed_entries(&mut d.peers, &before_peers, state.peers);
            let _ = merge_changed_entries(&mut d.media, &before_media, state.media);
            d.updates = prefer_newer_cursor(d.updates.clone(), state.updates);
            d.updates_started |= state.updates_started;
        }
        d.logout_tokens = state.logout_tokens;
        d.new_session = state.new_session;
        d.session_dead = state.session_dead;
        d.session_dead_reason = if state.session_dead {
            state.session_dead_reason.clone()
        } else {
            None
        };
        d.home_dc = state.snapshot.dc_id;
        d.home_session_id = state.snapshot.session_id;
        d.home_auth_key = state.snapshot.auth_key.clone();
        d.home_salt = state.snapshot.server_salt;
        d.home_time_offset = state.snapshot.time_offset_micros;
        d.test_dc = state.test_dc;
        d.last_inline = state.last_inline;
        if result.is_ok() {
            d.persist_epoch = d.persist_epoch.saturating_add(1);
        }
    }
    if identity_changed {
        for lane in client.rpc.iter() {
            if let Some(mut rpc) = lane.io.try_lock() {
                rpc.snapshot = fork_session(&io.snapshot);
                rpc.transport = None;
                rpc.pending_push = Default::default();
                rpc.last_difference = None;
            }
        }
    }
    if result.is_ok() {
        let persist_id = client.data.lock().home_session_id;
        schedule_persist(&client, persist_id);
    }
    result
}

pub(crate) fn home_fork_source(data: &ClientData) -> Option<Snapshot> {
    let auth = data.home_auth_key.clone()?;
    if auth.len() != 256 || data.user_id.is_none() || data.session_dead {
        return None;
    }
    let mut snap = Snapshot::new(data.home_dc, &mut OsRandom).ok()?;
    snap.auth_key = Some(auth);
    snap.server_salt = data.home_salt;
    snap.time_offset_micros = data.home_time_offset;
    snap.session_id = data.home_session_id;
    Some(snap)
}

pub(crate) fn with_read_lane<T>(
    handle: u64,
    mut f: impl FnMut(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    let client = get_client(handle)?;
    // A non-media DC permits a single main session. Extra read lanes are home-DC
    // sessions, so they run only when the server granted them (`config.tmp_sessions`).
    if scheduler::extra_main_sessions() == 0 {
        return with_client_mut(handle, f);
    }
    let home = {
        let d = client.data.lock();
        home_fork_source(&d)
    };
    let Some(home) = home else {
        return with_client_mut(handle, f);
    };
    // Reads wait for a read lane; the `with_client_mut` paths below are authorization
    // repairs, not contention fallbacks.
    let mut io = acquire_read_lane(&client)?;
    match prepare_media_lane_snapshot(&mut io.snapshot, &home, home.dc_id) {
        MediaLanePrep::NeedExport => {
            drop(io);
            return with_client_mut(handle, f);
        }
        MediaLanePrep::Replaced => io.transport = None,
        MediaLanePrep::Reuse => {}
    }
    let home_session_id = home.session_id;
    let home_dc = home.dc_id;
    let home_auth = home.auth_key.clone();
    let built = {
        let d = client.data.lock();
        if d.session_dead || d.home_session_id != home_session_id {
            None
        } else {
            Some(ClientState {
                api_id: d.api_id,
                api_hash: d.api_hash.clone(),
                session_path: d.session_path.clone(),
                snapshot: io.snapshot.clone(),
                user_id: d.user_id,
                peers: d.peers.clone(),
                updates: d.updates.clone(),
                media: d.media.clone(),
                updates_started: d.updates_started,
                channel_pts: d.channel_pts.clone(),
                channel_recovery: d.channel_recovery.clone(),
                seen_messages: d.seen_messages.clone(),
                logout_tokens: d.logout_tokens.clone(),
                new_session: d.new_session.clone(),
                session_dead: d.session_dead,
                session_dead_reason: d.session_dead_reason.clone(),
                test_dc: d.test_dc,
                last_inline: d.last_inline.clone(),
            })
        }
    };
    let mut state = match built {
        Some(state) => state,
        None => {
            drop(io);
            return with_client_mut(handle, f);
        }
    };
    crate::rpc::set_use_test_dc(state.test_dc);
    crate::rpc::clear_new_session_metadata();
    let before_peers = state.peers.clone();
    let before_media = state.media.clone();
    let before_channel_pts = state.channel_pts.clone();
    let mut slot = io.transport.take();
    let before_snapshot = io.snapshot.clone();
    let result = EXTRA_READ_LANE.with(|cell| {
        struct Restore<'a>(&'a Cell<bool>, bool);
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                self.0.set(self.1);
            }
        }
        let previous = cell.replace(true);
        let _restore = Restore(cell, previous);
        crate::api_invoke::with_invoke_without_updates(|| {
            with_client_transport(&client, &mut slot, || f(&mut state))
        })
    });
    if let Some(metadata) = crate::rpc::take_new_session_metadata() {
        state.new_session = Some(metadata);
    }
    let diverged = state.snapshot.dc_id != home_dc || state.snapshot.auth_key != home_auth;
    if diverged {
        io.snapshot = fork_session(&home);
        io.transport = None;
        drop(io);
        return with_client_mut(handle, f);
    }
    let snapshot_changed = before_snapshot.dc_id != state.snapshot.dc_id
        || before_snapshot.auth_key != state.snapshot.auth_key
        || before_snapshot.session_id != state.snapshot.session_id;
    io.transport = if snapshot_changed || state.session_dead {
        None
    } else {
        slot
    };
    io.snapshot = state.snapshot.clone();
    if snapshot_changed || state.session_dead {
        io.pending_push = Default::default();
        io.last_difference = None;
    }
    {
        let mut d = client.data.lock();
        if d.home_session_id == home_session_id && !d.session_dead {
            let _ = merge_changed_entries(&mut d.peers, &before_peers, state.peers);
            let _ = merge_changed_entries(&mut d.media, &before_media, state.media);
            let _ =
                merge_changed_entries(&mut d.channel_pts, &before_channel_pts, state.channel_pts);
            d.seen_messages.extend(state.seen_messages);
            d.updates = prefer_newer_cursor(d.updates.clone(), state.updates);
            d.updates_started |= state.updates_started;
            d.logout_tokens = state.logout_tokens;
            if state.new_session.is_some() {
                d.new_session = state.new_session;
            }
            d.session_dead |= state.session_dead;
            if state.session_dead {
                d.session_dead_reason = state.session_dead_reason.clone();
            }
            d.last_inline = state.last_inline;
            d.home_salt = state.snapshot.server_salt;
            d.home_time_offset = state.snapshot.time_offset_micros;
            if result.is_ok() {
                d.persist_epoch = d.persist_epoch.saturating_add(1);
            }
        }
    }
    drop(io);
    if result
        .as_ref()
        .err()
        .and_then(crate::api_invoke::migrate_dc)
        .is_none()
    {
        schedule_persist(&client, home_session_id);
    }
    if result
        .as_ref()
        .err()
        .and_then(crate::api_invoke::migrate_dc)
        .is_some()
    {
        return with_client_mut(handle, f);
    }
    result
}

pub(crate) fn ensure_ready(state: &mut ClientState) -> Result<(), MtprotoError> {
    ensure_auth_key(state)
}
