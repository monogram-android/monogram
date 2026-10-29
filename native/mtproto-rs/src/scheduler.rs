pub(crate) use mono_proto_runtime::scheduler::*;

pub fn set_active_media_lanes(lanes: usize) {
    mono_proto_runtime::scheduler::set_active_media_lanes(lanes);
    crate::client::visit_policies(|policy| policy.follow_media_lanes(lanes));
}
