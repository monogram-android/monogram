pub(crate) use mono_proto_client::adapter::messages::messages_poll_creation::*;

pub type PollSendSpec = mono_proto_client::adapter::messages::messages_poll_creation::PollSendSpec;

#[uniffi::remote(Record)]
pub struct PollSendSpec {
    pub question: String,
    pub answers: Vec<String>,
    pub anonymous: bool,
    pub multiple_choice: bool,
    pub quiz: bool,
    pub correct_answer: Option<u32>,
    pub solution: Option<String>,
}
