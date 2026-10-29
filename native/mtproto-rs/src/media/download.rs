pub use mono_proto_media::media_download::*;

pub fn set_progress_callback(cb: Option<ProgressCallback>) {
    crate::transfer_policy::default_policy().assign_progress(cb.clone());
    crate::client::visit_policies(|policy| policy.follow_progress(&cb));
}

pub fn set_chunk_size(size: i32) {
    let size = crate::transfer_policy::normalize_chunk(size);
    crate::transfer_policy::default_policy().assign_chunk_size(size);
    crate::client::visit_policies(|policy| policy.follow_chunk_size(size));
}
