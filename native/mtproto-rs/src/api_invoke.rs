use std::sync::Once;

use mono_proto::codec::{Boxed, BoxedDecode, TlEncode};
use mono_proto::latest::api::{InitConnectionRequest, InvokeWithLayerRequest};
use mono_proto_session::Snapshot;

use crate::rpc::BoxedQuery;
use crate::{InitConnectionInfo, MtprotoError};

#[cfg(test)]
#[path = "../tests/unit/api_invoke_bridge_tests.rs"]
mod tests;

pub use mono_proto_runtime::api_invoke::{
    invoking_without_updates, migrate_dc, with_invoke_without_updates,
};

fn fallback() -> InitConnectionInfo {
    InitConnectionInfo {
        app_version: env!("CARGO_PKG_VERSION").into(),
        ..InitConnectionInfo::default()
    }
}

pub(crate) fn initialize() {
    static INIT: Once = Once::new();
    INIT.call_once(|| mono_proto_runtime::api_invoke::set_init_connection_info(fallback()));
}

pub fn set_init_connection_info(info: InitConnectionInfo) {
    initialize();
    mono_proto_runtime::api_invoke::set_init_connection_info(info.sanitized_with(&fallback()));
}

pub fn wrap_init_connection_at_layer<X: TlEncode>(
    api_id: i32,
    layer: i32,
    query: X,
) -> InvokeWithLayerRequest<BoxedQuery<InitConnectionRequest<BoxedQuery<X>>>> {
    initialize();
    mono_proto_runtime::api_invoke::wrap_init_connection_at_layer(api_id, layer, query)
}

pub fn invoke_api<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    request: Req,
) -> Result<Res, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    initialize();
    mono_proto_runtime::api_invoke::invoke_api(snapshot, api_id, request)
}

pub fn invoke_api_allow_updates<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    request: Req,
) -> Result<Res, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    initialize();
    mono_proto_runtime::api_invoke::invoke_api_allow_updates(snapshot, api_id, request)
}

pub fn invoke_api_without_updates<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    request: Req,
) -> Result<Res, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    initialize();
    mono_proto_runtime::api_invoke::invoke_api_without_updates(snapshot, api_id, request)
}

pub fn invoke_api_batch_without_updates<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    requests: Vec<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    initialize();
    mono_proto_runtime::api_invoke::invoke_api_batch_without_updates(snapshot, api_id, requests)
}

pub fn invoke_api_batch_without_updates_streaming<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    requests: Vec<Req>,
    on_chunk: &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    initialize();
    mono_proto_runtime::api_invoke::invoke_api_batch_without_updates_streaming(
        snapshot, api_id, requests, on_chunk,
    )
}
