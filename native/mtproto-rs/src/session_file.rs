//! Durable client session: Snapshot, user id, peer cache, updates cursor.

#[cfg(test)]
use std::fs;

#[cfg(test)]
use mono_proto_session::Snapshot;

#[cfg(test)]
use crate::media::{MediaIndex, MediaRef};

pub use mono_proto_storage::client_session::{ChannelRecovery, ClientSession, DcAuthKey};

pub use mono_proto_media::persistence::{
    media_from_index, media_key, media_order_from_index, media_to_index,
};

pub use mono_proto_client::session_store::FileSessionStore;

#[cfg(test)]
#[path = "../tests/unit/session_file_tests.rs"]
mod tests;
