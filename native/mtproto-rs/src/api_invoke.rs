use std::sync::Once;

use crate::InitConnectionInfo;

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
