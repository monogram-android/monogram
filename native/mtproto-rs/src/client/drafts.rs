pub use mono_proto_client::drafts::{get_cloud_drafts, save_cloud_draft};

pub type CloudDraft = mono_proto_types::event_types::CloudDraft;

#[uniffi::remote(Record)]
pub struct CloudDraft {
    pub chat_id: i64,
    pub thread_id: i32,
    pub text: String,
    pub entities_json: Option<String>,
    pub reply_to_message_id: i32,
    pub date: i32,
    pub no_webpage: bool,
    pub has_unsupported_content: bool,
}
