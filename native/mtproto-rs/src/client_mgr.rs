//! Tellers-backed MTProto client handles.
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/auth

pub use crate::client::*;

#[cfg(test)]
use crate::media::{self as media_rpc, MediaIndex};
#[cfg(test)]
use crate::peers;
#[cfg(test)]
use crate::session_file::{ChannelRecovery, FileSessionStore};
#[cfg(test)]
use crate::{MtprotoError, UpdatesStateDto};
#[cfg(test)]
use parking_lot::Mutex;
#[cfg(test)]
use std::collections::{HashMap, HashSet, VecDeque};
#[cfg(test)]
use std::sync::atomic::Ordering;
#[cfg(test)]
use std::sync::Arc;
#[cfg(test)]
use tellers_mtproto_session::{OsRandom, Snapshot};

#[cfg(test)]
#[path = "client_mgr_tests.rs"]
mod tests;
