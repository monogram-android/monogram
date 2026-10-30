pub(crate) mod extras_rpc;
pub(crate) mod push_rpc {
    pub(crate) use mono_proto_client::adapter::messages::notifications::*;
}
pub mod chat_invites {
    pub use mono_proto_client::chat_invites::*;
}
pub mod checklists;
pub mod contacts;
pub mod drafts;
pub mod forum_management {
    pub(crate) use mono_proto_client::forum_management::*;
}
pub mod group_management {
    pub(crate) use mono_proto_client::group_management::*;
}
pub mod management {
    pub(crate) use mono_proto_client::management::*;
}
pub mod polls {
    pub(crate) use mono_proto_client::polls::*;
}
pub mod scheduled;
pub mod shared_folders {
    pub(crate) use mono_proto_client::shared_folders::*;
}

pub use mono_proto_client::access::{client_api_id, client_uses_test_dc};
pub use mono_proto_client::auth::*;
pub use mono_proto_client::config::update_lazy_sync_config;
pub use mono_proto_client::dispatch::*;
pub use mono_proto_client::extras::*;
pub use mono_proto_client::media_download::*;
pub use mono_proto_client::passkeys::*;
pub use mono_proto_client::registry::{client_exists, destroy_client, visit_policies};
pub use mono_proto_client::updates::*;

pub fn create_client(api_id: i32, api_hash: String, session_path: String) -> u64 {
    crate::api_invoke::initialize();
    mono_proto_client::create_client(api_id, api_hash, session_path)
}

pub fn set_pipeline_parts(parts: usize) {
    mono_proto_client::lanes::set_pipeline_parts(parts);
}

pub fn pipeline_parts() -> usize {
    mono_proto_client::lanes::pipeline_parts()
}
