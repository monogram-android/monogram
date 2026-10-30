//! Android UniFFI surface for Monogram (Mono MTProto runtime).
//! https://core.telegram.org/mtproto
//! https://core.telegram.org/api/invoking
//! https://core.telegram.org/api/layers

#![deny(unsafe_code)]

mod api_invoke;
mod dto;
mod error;
mod ffi;
mod lottie;
mod qr;
#[allow(unsafe_code)]
mod vpx;
mod waveform;

#[cfg(test)]
#[path = "../tests/unit/adapter_boundary_tests.rs"]
mod adapter_boundary_tests;

use mono_proto_types::collections;

pub(crate) use collections::{
    CompactString, HashMap, HashMapExt, HashSet, HashSetExt, IndexMap, SmallVec,
};

pub use dto::*;
pub use error::*;
pub use ffi::*;
pub use qr::QrCodeMatrix;

uniffi::setup_scaffolding!();
