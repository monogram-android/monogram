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
mod media {
    pub(crate) mod wallpaper_rpc;
}
mod messages;
mod qr;
#[allow(unsafe_code)]
mod vpx;
mod waveform;

#[cfg(test)]
#[path = "../tests/unit/adapter_boundary_tests.rs"]
mod adapter_boundary_tests;

use mono_proto_client::adapter::types::collections;

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
pub use messages::{
    ChecklistSendSpec, ContactSendSpec, LocationSendSpec, OutgoingMessageResult, PollSendSpec,
    SendOptions,
};
pub use qr::QrCodeMatrix;

uniffi::setup_scaffolding!();
