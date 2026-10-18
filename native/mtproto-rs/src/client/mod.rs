//! Tellers-backed MTProto client handles.
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/datacenter
//! https://core.telegram.org/api/auth

mod auth;
mod dispatch;
mod extras;
pub(crate) mod extras_rpc;
pub(crate) mod lanes;
mod media_download;
mod membership;
mod persist;
pub(crate) mod push_rpc;
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

#[cfg(test)]
use crate::media as media_rpc;
#[cfg(test)]
use crate::peers;

#[cfg(test)]
#[path = "../../tests/unit/client_mgr_channel_tests.rs"]
mod channel_tests;
#[cfg(test)]
#[path = "../../tests/unit/client_mgr_lane_tests.rs"]
mod lane_tests;
#[cfg(test)]
#[path = "../../tests/unit/lazy_sync_tests.rs"]
mod lazy_sync_tests;
#[cfg(test)]
#[path = "../../tests/unit/client_mgr_logout_tests.rs"]
mod logout_tests;
#[cfg(test)]
#[path = "../../tests/unit/client_mgr_pfs_tests.rs"]
mod pfs_tests;
#[cfg(test)]
#[path = "../../tests/unit/client_mgr_session_tests.rs"]
mod session_tests;
#[cfg(test)]
#[path = "../../tests/unit/transfer_policy_tests.rs"]
mod transfer_policy_tests;

use crate::{HashMap, HashMapExt, HashSet, HashSetExt, IndexMap};
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
use crate::session_file::{ChannelRecovery, DcAuthKey, FileSessionStore, media_to_index};
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
    pub(crate) home_snapshot: Snapshot,
    pub(crate) home_dc: i32,
    pub(crate) home_session_id: i64,
    pub(crate) home_auth_key: Option<Vec<u8>>,
    pub(crate) home_salt: i64,
    pub(crate) home_time_offset: i64,
    pub(crate) test_dc: bool,
    pub(crate) last_inline: Option<LastInlineQuery>,
    /// Imported foreign-DC master keys. Not a temporary PFS cache.
    pub(crate) dc_auth_keys: HashMap<i32, DcAuthKey>,
    /// Bound temporary keys for media lanes. Process-local; never persisted.
    pub(crate) dc_temp_auth: HashMap<i32, DcTempAuth>,
    pub(crate) persist_epoch: u64,
    pub(crate) persisted_epoch: u64,
    /// In-memory catch-up edge. Not written into the session file.
    pub(crate) is_syncing: bool,
    pub(crate) lazy_channel_updates: bool,
    pub(crate) lazy_sync_exceptions: HashSet<i64>,
    /// Optional metadata previews; gaps remain durable in `channel_recovery`.
    pub(crate) lazy_channel_recovery: HashSet<i64>,
    pub(crate) lazy_retry_at: u64,
    pub(crate) journal: Arc<crate::update_journal::UpdateJournal>,
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
    pub(crate) policy: std::sync::Arc<crate::transfer_policy::TransferPolicy>,
    pub(crate) data: Mutex<ClientData>,
    pub(crate) main_gate: scheduler::LaneGate,
    pub(crate) main: Mutex<SessionIo>,
    /// Overlapping reads; never the updates subscriber.
    pub(crate) rpc: [Lane; scheduler::READ_LANES],
    /// File RPCs on separate sessions.
    pub(crate) media: crate::SmallVec<[Lane; scheduler::MAX_MEDIA_LANES]>,
    pub(crate) upload: [Lane; scheduler::UPLOAD_LANES],
    pub(crate) file_cleanup_running: AtomicBool,
    pub(crate) media_open: std::sync::atomic::AtomicUsize,
    pub(crate) media_gate: scheduler::LaneGate,
    pub(crate) persist_queued: AtomicBool,
    pub(crate) persist_running: AtomicBool,
    pub(crate) persist_exhausted: AtomicBool,
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
    /// Set while the live snapshot uses a PFS temp key. Disk writes keep this key.
    pub(crate) permanent: Option<Snapshot>,
    pub(crate) temp_expires_at: i32,
    pub(crate) dc_auth_keys: HashMap<i32, DcAuthKey>,
    pub(crate) journal: Arc<crate::update_journal::UpdateJournal>,
}

/// In-memory bound temporary key. Deliberately not `Debug`: it holds key material.
#[derive(Clone)]
pub(crate) struct DcTempAuth {
    pub(crate) snapshot: Snapshot,
    pub(crate) temp_expires_at: i32,
    pub(crate) perm_key: Vec<u8>,
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
pub(crate) trait MergeMap<K, V> {
    fn contains_key(&self, key: &K) -> bool;
    fn get(&self, key: &K) -> Option<&V>;
    fn insert(&mut self, key: K, value: V) -> Option<V>;
    fn retain(&mut self, f: &mut dyn FnMut(&K, &mut V) -> bool);
    fn for_each_entry(self, f: &mut dyn FnMut(K, V));
}

impl<K, V> MergeMap<K, V> for HashMap<K, V>
where
    K: Eq + std::hash::Hash,
{
    fn contains_key(&self, key: &K) -> bool {
        HashMap::contains_key(self, key)
    }
    fn get(&self, key: &K) -> Option<&V> {
        HashMap::get(self, key)
    }
    fn insert(&mut self, key: K, value: V) -> Option<V> {
        HashMap::insert(self, key, value)
    }
    fn retain(&mut self, f: &mut dyn FnMut(&K, &mut V) -> bool) {
        HashMap::retain(self, |key, value| f(key, value));
    }
    fn for_each_entry(self, f: &mut dyn FnMut(K, V)) {
        for (key, value) in self {
            f(key, value);
        }
    }
}

impl<K, V> MergeMap<K, V> for IndexMap<K, V>
where
    K: Eq + std::hash::Hash,
{
    fn contains_key(&self, key: &K) -> bool {
        IndexMap::contains_key(self, key)
    }
    fn get(&self, key: &K) -> Option<&V> {
        IndexMap::get(self, key)
    }
    fn insert(&mut self, key: K, value: V) -> Option<V> {
        IndexMap::insert(self, key, value)
    }
    fn retain(&mut self, f: &mut dyn FnMut(&K, &mut V) -> bool) {
        IndexMap::retain(self, |key, value| f(key, value));
    }
    fn for_each_entry(self, f: &mut dyn FnMut(K, V)) {
        for (key, value) in self {
            f(key, value);
        }
    }
}

impl MergeMap<i64, ChannelRecovery> for VecDeque<ChannelRecovery> {
    fn contains_key(&self, key: &i64) -> bool {
        self.iter().any(|entry| entry.chat_id == *key)
    }
    fn get(&self, key: &i64) -> Option<&ChannelRecovery> {
        self.iter().find(|entry| entry.chat_id == *key)
    }
    fn insert(&mut self, key: i64, value: ChannelRecovery) -> Option<ChannelRecovery> {
        if let Some(entry) = self.iter_mut().find(|entry| entry.chat_id == key) {
            Some(std::mem::replace(entry, value))
        } else {
            self.push_back(value);
            None
        }
    }
    fn retain(&mut self, f: &mut dyn FnMut(&i64, &mut ChannelRecovery) -> bool) {
        self.retain_mut(|entry| {
            let key = entry.chat_id;
            f(&key, entry)
        });
    }
    fn for_each_entry(self, f: &mut dyn FnMut(i64, ChannelRecovery)) {
        for entry in self {
            f(entry.chat_id, entry);
        }
    }
}

// Commit only this lane's changes. An unchanged cloned entry must never
// overwrite data fetched by another lane while this lane was on the network.
pub(crate) fn merge_changed_entries<K, V, M>(current: &mut M, before: &M, incoming: M) -> bool
where
    K: Eq + std::hash::Hash,
    V: PartialEq,
    M: MergeMap<K, V>,
{
    let mut changed = false;
    current.retain(&mut |key, value| {
        let keep = incoming.contains_key(key) || before.get(key) != Some(value);
        if !keep {
            changed = true;
        }
        keep
    });
    incoming.for_each_entry(&mut |key, value| {
        if before.get(&key) != Some(&value) && current.get(&key) == before.get(&key) {
            current.insert(key, value);
            changed = true;
        }
    });
    changed
}

pub(crate) fn get_client(handle: u64) -> Result<Arc<Client>, MtprotoError> {
    CLIENTS
        .lock()
        .get(&handle)
        .cloned()
        .ok_or(MtprotoError::UnknownClient)
}

pub(crate) fn visit_policies(visit: impl Fn(&crate::transfer_policy::TransferPolicy)) {
    for client in CLIENTS.lock().values() {
        visit(&client.policy);
    }
}

pub fn create_client(api_id: i32, api_hash: String, session_path: String) -> u64 {
    let connections = Arc::new(tcp::ConnectionControl::default());
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
        dc_auth_keys,
        journal,
    ) = match loaded {
        Some(session) => (
            session.snapshot,
            session.user_id,
            session.peers,
            session.updates,
            media_to_index(&session.media, &session.media_order),
            session.channel_pts,
            session.seen_messages,
            session.logout_tokens,
            session.new_session,
            session.session_dead,
            session.test_dc,
            session.dc_auth_keys,
            session.journal,
        ),
        None => (
            Snapshot::new(DEFAULT_DC_ID, &mut OsRandom).expect("snapshot"),
            None,
            HashMap::new(),
            None,
            MediaIndex::default(),
            HashMap::new(),
            HashSet::new(),
            Vec::new(),
            None,
            load_failed,
            false,
            HashMap::new(),
            Default::default(),
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
    let upload_lanes = core::array::from_fn(|_| {
        Lane::new(SessionIo {
            pending_push: Default::default(),
            last_difference: None,
            snapshot: fork_session(&snapshot),
            transport: None,
            temp_expires_at: 0,
            perm_key: None,
        })
    });
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    CLIENTS.lock().insert(
        handle,
        Arc::new(Client {
            _session_key: crate::session_crypto::key_for(&path),
            connections,
            policy: crate::transfer_policy::snapshot(),
            data: Mutex::new(ClientData {
                api_id,
                api_hash,
                session_path: path,
                user_id,
                peers,
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
                home_snapshot: snapshot.clone(),
                home_dc: snapshot.dc_id,
                home_session_id: snapshot.session_id,
                home_auth_key: snapshot.auth_key.clone(),
                home_salt: snapshot.server_salt,
                home_time_offset: snapshot.time_offset_micros,
                test_dc,
                last_inline: None,
                dc_auth_keys,
                dc_temp_auth: HashMap::new(),
                persist_epoch: 0,
                persisted_epoch: 0,
                is_syncing: false,
                lazy_channel_updates: false,
                lazy_sync_exceptions: HashSet::new(),
                lazy_channel_recovery: HashSet::new(),
                lazy_retry_at: 0,
                journal: Arc::new(journal),
            }),
            main_gate: scheduler::LaneGate::new(),
            main: Mutex::new(SessionIo {
                pending_push: Default::default(),
                last_difference: None,
                snapshot,
                transport: None,
                temp_expires_at: 0,
                perm_key: None,
            }),
            rpc: [
                Lane::new(SessionIo {
                    pending_push: Default::default(),
                    last_difference: None,
                    snapshot: rpc_snapshot,
                    transport: None,
                    temp_expires_at: 0,
                    perm_key: None,
                }),
                Lane::new(SessionIo {
                    pending_push: Default::default(),
                    last_difference: None,
                    snapshot: rpc_snapshot_b,
                    transport: None,
                    temp_expires_at: 0,
                    perm_key: None,
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
                        temp_expires_at: 0,
                        perm_key: None,
                    })
                })
                .collect(),
            upload: upload_lanes,
            file_cleanup_running: AtomicBool::new(false),
            media_open: std::sync::atomic::AtomicUsize::new(1),
            media_gate: scheduler::LaneGate::new(),
            persist_queued: AtomicBool::new(false),
            persist_running: AtomicBool::new(false),
            persist_exhausted: AtomicBool::new(false),
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
        client.connections.begin_shutdown();
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

pub fn client_exists(handle: u64) -> bool {
    CLIENTS.lock().contains_key(&handle)
}

pub(crate) fn schedule_transfer_config(handle: u64) {
    let Ok(client) = get_client(handle) else {
        return;
    };
    if client.data.lock().user_id.is_none() {
        return;
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let due = client.policy.config_due.load(Ordering::Acquire);
    if due > now
        || client
            .policy
            .config_due
            .compare_exchange(due, now + 3600, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
    {
        return;
    }
    let weak = Arc::downgrade(&client);
    let spawned = std::thread::Builder::new()
        .name("mtproto-transfer-config".into())
        .spawn(move || {
            let result = scheduler::with_class(scheduler::RequestClass::BackgroundRead, || {
                with_client_mut(handle, |state| {
                    crate::rpc::with_rpc_timeout_secs(8, || {
                        extras_rpc::animated_emoji_max(&mut state.snapshot, state.api_id)
                    })
                })
            });
            if result.is_err() {
                if let Some(client) = weak.upgrade() {
                    client.policy.config_due.store(now + 60, Ordering::Release);
                }
            }
        });
    if spawned.is_err() {
        client.policy.config_due.store(now + 60, Ordering::Release);
    }
}

pub fn client_api_id(handle: u64) -> i32 {
    get_client(handle)
        .map(|c| c.data.lock().api_id)
        .unwrap_or(0)
}

pub fn client_uses_test_dc(handle: u64) -> bool {
    get_client(handle)
        .map(|c| c.data.lock().test_dc)
        .unwrap_or(false)
}

pub(crate) fn with_client_mut<T>(
    handle: u64,
    f: impl FnOnce(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    with_client_mut_binding(handle, f, crate::auth::bind_temp::negotiate_bound_temp)
}

pub(crate) fn with_client_mut_binding<T>(
    handle: u64,
    f: impl FnOnce(&mut ClientState) -> Result<T, MtprotoError>,
    negotiate: impl FnOnce(&[u8], i32, i64) -> Result<crate::auth::bind_temp::BoundTemp, MtprotoError>,
) -> Result<T, MtprotoError> {
    let client = get_client(handle)?;
    let _policy = crate::transfer_policy::bind(std::sync::Arc::clone(&client.policy));
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
    crate::auth::bind_temp::ensure_main_temp_with(&client, &mut io, false, &crate::rpc::SystemClock, negotiate)?;
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
            permanent: (io.temp_expires_at != 0 || io.perm_key.is_some())
                .then(|| d.home_snapshot.clone()),
            temp_expires_at: io.temp_expires_at,
            dc_auth_keys: d.dc_auth_keys.clone(),
            journal: Arc::clone(&d.journal),
        }
    };
    crate::rpc::set_use_test_dc(state.test_dc);
    crate::rpc::clear_new_session_metadata();
    let before_peers = state.peers.clone();
    let before_media = state.media.clone();
    let before_channel_pts = state.channel_pts.clone();
    let before_recovery = state.channel_recovery.clone();
    let before_user_id = state.user_id;
    let before_updates = state.updates.clone();
    let before_logout = state.logout_tokens.clone();
    let before_new_session = state.new_session.clone();
    let before_dead = state.session_dead;
    let before_test_dc = state.test_dc;
    let before_dc_auth_keys = state.dc_auth_keys.clone();
    let mut slot = io.transport.take();
    let before_snapshot = io.snapshot.clone();
    let result = with_client_transport(&client, &mut slot, || f(&mut state));
    let temp_failed = result.as_ref().err().is_some_and(|err| temporary_key_failure(&state, err));
    if !temp_failed && !state.session_dead {
        if let Err(error) = &result {
            if kills_session(state.user_id, error) {
                mark_session_dead(&mut state, error);
            }
        }
    }
    if temp_failed {
        expire_temporary_lane(&client, &mut io, result.as_ref().err().unwrap());
        state.temp_expires_at = 1;
    }
    if let Some(metadata) = crate::rpc::take_new_session_metadata() {
        state.new_session = Some(metadata);
    }
    // A session/auth-key or session_id change invalidates the old TCP lane.
    // Never put a transport bound to the previous session back into the slot
    // after auth restart, bad-msg recovery, DC migration, or key recreation.
    let snapshot_changed = before_snapshot.dc_id != state.snapshot.dc_id
        || before_snapshot.auth_key != state.snapshot.auth_key
        || before_snapshot.session_id != state.snapshot.session_id;
    io.transport = if snapshot_changed || state.session_dead || temp_failed {
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
    let drop_dc_auth = identity_changed || before_test_dc != state.test_dc;
    if drop_dc_auth {
        state.dc_auth_keys.clear();
    }
    let dc_keys_changed = before_dc_auth_keys != state.dc_auth_keys;
    if identity_changed {
        client.policy.bound_extra_sessions.store(0, Ordering::Release);
        client.policy.main_session_allowance.store(-1, Ordering::Release);
        if state.user_id.is_none() || before_user_id.is_some() {
            client.policy.premium.store(false, Ordering::Relaxed);
        }
        *client.policy.upload_rtt.lock() = None;
    }
    let updates_changed = before_updates != state.updates;
    let tokens_changed = before_logout != state.logout_tokens;
    let session_meta_changed = before_new_session != state.new_session
        || before_dead != state.session_dead
        || before_test_dc != state.test_dc;
    let home = durable_snapshot(&state);
    if let Some(permanent) = &state.permanent {
        io.perm_key = permanent.auth_key.clone();
        io.temp_expires_at = state.temp_expires_at.max(1);
    } else if state.snapshot.auth_key != before_snapshot.auth_key || state.snapshot.dc_id != before_snapshot.dc_id {
        io.temp_expires_at = 0;
        io.perm_key = None;
    }
    let persist_needed;
    {
        let mut d = client.data.lock();
        d.user_id = state.user_id;
        let maps_changed = if before_user_id != state.user_id {
            d.peers = state.peers;
            d.media = state.media;
            d.updates = state.updates;
            d.channel_pts = state.channel_pts;
            d.channel_recovery = state.channel_recovery;
            d.seen_messages = state.seen_messages;
            d.updates_started = state.updates_started;
            if before_user_id.is_some() {
                Arc::make_mut(&mut d.journal).reset_account();
            }
            true
        } else {
            let peers_changed = merge_changed_entries(&mut d.peers, &before_peers, state.peers);
            let media_changed = merge_changed_entries(&mut d.media, &before_media, state.media);
            let channels_changed = merge_changed_entries(&mut d.channel_pts, &before_channel_pts, state.channel_pts);
            let recovery_changed = state.channel_recovery != before_recovery
                && merge_changed_entries(&mut d.channel_recovery, &before_recovery, state.channel_recovery);
            let seen_before = d.seen_messages.len();
            d.seen_messages.extend(state.seen_messages);
            d.updates = prefer_newer_cursor(d.updates.clone(), state.updates);
            d.updates_started |= state.updates_started;
            peers_changed || media_changed || channels_changed || recovery_changed || seen_before != d.seen_messages.len() || updates_changed
        };
        d.logout_tokens = state.logout_tokens;
        d.new_session = state.new_session;
        d.session_dead = if before_dead { state.session_dead } else { d.session_dead || state.session_dead };
        if before_dead || state.session_dead || !d.session_dead {
            d.session_dead_reason = if state.session_dead { state.session_dead_reason.clone() } else { None };
        }
        if d.session_dead {
            io.transport = None;
        }
        d.home_dc = home.dc_id;
        d.home_time_offset = home.time_offset_micros;
        d.home_session_id = home.session_id;
        d.home_auth_key = home.auth_key.clone();
        d.home_salt = home.server_salt;
        d.home_snapshot = home;
        d.test_dc = state.test_dc;
        d.last_inline = state.last_inline;
        if dc_keys_changed {
            d.dc_auth_keys = state.dc_auth_keys.clone();
        }
        if drop_dc_auth {
            d.dc_temp_auth.clear();
        }
        persist_needed = result.is_ok()
            && (identity_changed
                || snapshot_changed
                || maps_changed
                || tokens_changed
                || session_meta_changed
                || dc_keys_changed);
        if persist_needed {
            d.persist_epoch = d.persist_epoch.saturating_add(1);
        }
    }
    if identity_changed {
        for lane in client.rpc.iter() {
            if let Some(mut rpc) = lane.io.try_lock() {
                rpc.temp_expires_at = 0;
                rpc.transport = None;
                rpc.pending_push = Default::default();
                rpc.last_difference = None;
                if io.temp_expires_at == 0 && io.perm_key.is_none() {
                    rpc.snapshot = fork_session(&io.snapshot);
                }
            }
        }
    }
    if persist_needed {
        let persist_id = client.data.lock().home_session_id;
        schedule_persist(&client, persist_id);
    }
    schedule_file_cleanup(&client);
    if temp_failed { Err(temporary_key_retry_error()) } else { result }
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
    let _policy = crate::transfer_policy::bind(client.policy.clone());
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
    crate::auth::bind_temp::ensure_main_temp(&client, &mut io, false)?;
    let bound_temp = crate::auth::bind_temp::temp_key_valid(&io.snapshot, io.temp_expires_at)
        && io.snapshot.auth_key.as_ref() != home.auth_key.as_ref()
        && io.snapshot.auth_key.as_ref().map(Vec::len) == Some(256);
    if !bound_temp {
        drop(io);
        return with_client_mut(handle, f);
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
                permanent: (io.temp_expires_at != 0 || io.perm_key.is_some())
                    .then(|| d.home_snapshot.clone()),
                temp_expires_at: io.temp_expires_at,
                dc_auth_keys: d.dc_auth_keys.clone(),
                journal: Arc::clone(&d.journal),
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
    let before_recovery = state.channel_recovery.clone();
    let before_dc_auth_keys = state.dc_auth_keys.clone();
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
    let temp_failed = result.as_ref().err().is_some_and(|err| temporary_key_failure(&state, err));
    if temp_failed {
        expire_temporary_lane(&client, &mut io, result.as_ref().err().unwrap());
        return Err(temporary_key_retry_error());
    }
    let still_temp = (io.temp_expires_at != 0 || io.perm_key.is_some())
        && state.snapshot.auth_key.as_ref() != home_auth.as_ref();
    let diverged =
        state.snapshot.dc_id != home_dc || (state.snapshot.auth_key != home_auth && !still_temp);
    if diverged {
        io.temp_expires_at = 0;
        io.perm_key = None;
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
            if state.channel_recovery != before_recovery {
                merge_changed_entries(&mut d.channel_recovery, &before_recovery, state.channel_recovery);
            }
            d.seen_messages.extend(state.seen_messages);
            d.updates = prefer_newer_cursor(d.updates.clone(), state.updates);
            d.updates_started |= state.updates_started;
            d.logout_tokens = state.logout_tokens;
            if state.new_session.is_some() {
                d.new_session = state.new_session;
            }
            let terminal_changed = !d.session_dead && state.session_dead;
            d.session_dead |= state.session_dead;
            if state.session_dead {
                d.session_dead_reason = state.session_dead_reason.clone();
            }
            d.last_inline = state.last_inline;
            if state.permanent.is_none() && state.snapshot.auth_key == d.home_auth_key {
                d.home_salt = state.snapshot.server_salt;
                d.home_snapshot.server_salt = state.snapshot.server_salt;
            }
            d.home_time_offset = state.snapshot.time_offset_micros;
            if before_dc_auth_keys != state.dc_auth_keys {
                d.dc_auth_keys = state.dc_auth_keys.clone();
            }
            if result.is_ok() || terminal_changed {
                d.persist_epoch = d.persist_epoch.saturating_add(1);
            }
        }
    }
    drop(io);
    schedule_file_cleanup(&client);
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

/// Offscreen previews leave gaps durable; only the open chat and exceptions recover them.
pub(crate) fn update_lazy_sync_config(handle: u64, lazy: bool, exceptions: Vec<i64>) {
    let Ok(client) = get_client(handle) else {
        return;
    };
    let mut d = client.data.lock();
    if lazy && !d.lazy_channel_updates {
        let pending: Vec<_> = d
            .channel_recovery
            .iter()
            .filter(|entry| {
                !entry.watching
                    && entry.chat_id != d.last_history_chat_id
                    && !exceptions.contains(&entry.chat_id)
            })
            .map(|entry| entry.chat_id)
            .collect();
        d.lazy_channel_recovery.extend(pending);
    }
    d.lazy_channel_updates = lazy;
    d.lazy_sync_exceptions = exceptions.into_iter().collect();
    if lazy {
        return;
    }
    let pending = std::mem::take(&mut d.lazy_channel_recovery);
    for chat_id in pending {
        if d.channel_recovery
            .iter()
            .any(|entry| entry.chat_id == chat_id)
        {
            continue;
        }
        d.channel_recovery.push_back(ChannelRecovery {
            chat_id,
            due_at: 0,
            watching: false,
        });
    }
}
