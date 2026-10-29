//! Android UniFFI surface for Monogram (Mono MTProto runtime).
//! https://core.telegram.org/mtproto
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/layers

#![deny(unsafe_code)]

mod api_invoke;
mod client;
mod dto;
mod error;
mod ffi;
mod instant_view;
mod lottie;
pub mod media;
mod messages;
mod qr;
mod request_control;
mod scheduler;
mod session_file;
mod upload;
#[allow(unsafe_code)]
mod vpx;
mod waveform;

mod auth {
    pub(crate) mod auth_key { pub(crate) use mono_proto_runtime::auth_key::*; }
    pub(crate) mod auth_rpc { pub(crate) use mono_proto_runtime::auth_rpc::*; }
    pub(crate) mod bind_temp { pub(crate) use mono_proto_client::bind_temp::*; }
    pub(crate) mod srp;
}
mod dialogs { pub(crate) use mono_proto_messages::dialogs::*; }
mod dns_txt { pub(crate) use mono_proto_runtime::dns_txt::*; }
mod emoji_status { pub(crate) use mono_proto_types::emoji_status::*; }
mod passkeys { pub(crate) use mono_proto_client::passkey_mapping::*; }
mod peers { pub(crate) use mono_proto_types::peers::*; }
mod perf { pub(crate) use mono_proto_observability::perf::*; }
mod presence { pub(crate) use mono_proto_types::presence::*; }
mod profile { pub(crate) use mono_proto_messages::profile::*; }
mod reply_markup { pub(crate) use mono_proto_types::reply_markup::*; }
mod rpc { pub(crate) use mono_proto_runtime::rpc::*; }
mod service_messages { pub(crate) use mono_proto_types::service_messages::*; }
mod stripped_jpeg { pub(crate) use mono_proto_media::stripped_jpeg::*; }
mod tcp { pub(crate) use mono_proto_runtime::tcp::*; }
mod transfer_policy { pub use mono_proto_runtime::transfer_policy::*; }
mod session_crypto { pub(crate) use mono_proto_storage::session_crypto::*; }
mod update_buffer { pub(crate) use mono_proto_storage::update_buffer::*; }
mod update_journal { pub(crate) use mono_proto_storage::update_journal::*; }
mod updates { pub(crate) mod updates_rpc { pub(crate) use mono_proto_updates::rpc::*; } }

use mono_proto_types::collections;

pub(crate) use collections::{
    CompactString, HashMap, HashMapExt, HashSet, HashSetExt, IndexMap, SmallVec,
};

pub use client::chat_invites;
pub use client::contacts::{
    ContactImportMatch, ContactImportPopularity, ContactImportResult, ContactImportSpec,
};
pub use client::drafts;
pub use client::forum_management;
pub use client::group_management;
pub use client::management;
pub use client::scheduled;
pub use client::scheduled::ScheduledMessageDto;
pub use client::shared_folders;
pub use dto::*;
pub use error::*;
pub use ffi::*;
pub use instant_view::InstantViewDto;
pub use media::wallpaper_rpc::{WallpaperCatalogDto, WallpaperDto};
pub use media::{ProgressCallback, notify_progress, set_progress_callback};
pub use messages::{
    ChecklistSendSpec, ContactSendSpec, LocationSendSpec, OutgoingMessageResult, PollSendSpec,
    SendOptions,
};
pub use qr::QrCodeMatrix;

uniffi::setup_scaffolding!();

#[cfg(test)]
#[path = "../tests/unit/lib_tests.rs"]
mod tests;
