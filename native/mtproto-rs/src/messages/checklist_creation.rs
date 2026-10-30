pub(crate) use mono_proto_client::adapter::messages::messages_checklist_creation::*;

pub type ChecklistSendSpec =
    mono_proto_client::adapter::messages::messages_checklist_creation::ChecklistSendSpec;

#[uniffi::remote(Record)]
pub struct ChecklistSendSpec {
    pub title: String,
    pub items: Vec<String>,
    pub others_can_append: bool,
    pub others_can_complete: bool,
}
