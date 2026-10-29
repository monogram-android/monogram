pub use mono_proto_messages::upload_rpc::*;

pub fn set_file_part_kib(kib: i32) {
    let bytes = crate::transfer_policy::normalize_file_part_kib(kib);
    let kib = if bytes == FILE_PART_FAST { 512 } else { 32 };
    crate::transfer_policy::default_policy().assign_file_part_kib(kib);
    crate::client::visit_policies(|policy| policy.follow_file_part_kib(kib));
}
