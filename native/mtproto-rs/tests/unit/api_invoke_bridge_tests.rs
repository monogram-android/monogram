use super::*;
use mono_proto::latest::api::HelpGetConfigRequest;

#[test]
fn empty_app_version_uses_bridge_package_version() {
    let mut info = fallback();
    info.app_version.clear();
    set_init_connection_info(info);
    let request = wrap_init_connection_at_layer(1, 232, HelpGetConfigRequest {});
    assert_eq!(request.query.0.app_version, env!("CARGO_PKG_VERSION"));
}
