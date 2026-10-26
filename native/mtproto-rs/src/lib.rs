//! Android UniFFI surface for Monogram (Tellers MTProto runtime).
//! https://core.telegram.org/mtproto
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/layers

#![deny(unsafe_code)]

mod api_invoke;
mod auth;
mod client;
mod collections;
mod dialogs;
mod dns_txt;
mod dto;
mod emoji_status;
mod error;
mod ffi;
mod instant_view;
mod lottie;
pub mod media;
mod messages;
mod passkeys;
mod peers;
mod perf;
mod presence;
mod profile;
mod qr;
mod reply_markup;
mod request_control;
mod rpc;
mod scheduler;
mod service_messages;
mod session_crypto;
mod session_file;
mod stripped_jpeg;
mod tcp;
mod transfer_policy;
mod update_buffer;
mod update_journal;
mod updates;
mod upload;
#[allow(unsafe_code)]
mod vpx;
mod waveform;

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
