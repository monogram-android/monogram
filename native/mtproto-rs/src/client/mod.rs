//! Mono MTProto client handles.
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/datacenter
//! https://core.telegram.org/api/auth

mod auth { pub(crate) use mono_proto_client::auth::*; }
mod passkeys { pub use mono_proto_client::passkeys::*; }
pub mod chat_invites { pub use mono_proto_client::chat_invites::*; }
pub mod checklists;
pub mod contacts;
mod dispatch { pub(crate) use mono_proto_client::dispatch::*; }
pub mod drafts;
mod extras { pub(crate) use mono_proto_client::extras::*; }
pub(crate) mod extras_rpc;
pub mod forum_management;
pub mod group_management;
pub(crate) mod lanes { pub(crate) use mono_proto_client::lanes::*; }
pub mod management;
mod media_download { pub(crate) use mono_proto_client::media_download::*; }
mod membership { pub(crate) use mono_proto_client::membership::*; }
mod persist { pub(crate) use mono_proto_client::persist::*; }
pub mod polls;
pub(crate) mod push_rpc { pub(crate) use mono_proto_messages::notifications::*; }
pub mod scheduled;
mod session;
pub mod shared_folders;
mod updates { pub(crate) use mono_proto_client::updates::*; pub(crate) use mono_proto_updates::recovery::*; }

pub use auth::*;
pub use passkeys::*;
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
use std::sync::Arc;

use parking_lot::Mutex;
use mono_proto_session::{OsRandom, Snapshot};

use crate::media::MediaIndex;
use crate::peers::CachedPeer;
use crate::scheduler;
use crate::session_file::{ChannelRecovery, DcAuthKey, FileSessionStore, media_to_index};
use crate::tcp;
use crate::{MtprotoError, UpdatesStateDto};

pub(crate) use mono_proto_client::registry::CLIENTS;

pub(crate) const DEFAULT_DC_ID: i32 = 2;

pub(crate) use mono_proto_client::state::{ClientData, ClientState, DcTempAuth, LastInlineQuery};

pub(crate) use mono_proto_client::client::Client;

pub(crate) use mono_proto_client::merge::{merge_changed_entries, prefer_newer_cursor};

pub(crate) use mono_proto_client::registry::{get_client, visit_policies};

pub fn create_client(api_id: i32, api_hash: String, session_path: String) -> u64 {
    crate::api_invoke::initialize();
    mono_proto_client::construct::create_client(api_id, api_hash, session_path)
}

pub(crate) use mono_proto_client::access::{home_fork_source, interactive_request_pending, with_client_mut, with_client_mut_binding, with_client_transport, with_interactive_client_mut, with_read_lane, ensure_ready};
pub use mono_proto_client::access::{client_api_id, client_uses_test_dc};

pub use mono_proto_client::registry::destroy_client;

pub use mono_proto_client::registry::client_exists;

pub(crate) use mono_proto_client::config::{schedule_transfer_config, update_lazy_sync_config};
