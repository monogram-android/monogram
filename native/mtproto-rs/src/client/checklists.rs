pub use mono_proto_client::checklists::*;

#[uniffi::remote(Record)]
pub struct ChecklistTextEditDto {
    pub item_id: Option<i32>,
    pub text: String,
    pub entities_json: Option<String>,
    pub expected_text: String,
    pub expected_entities_json: Option<String>,
}
