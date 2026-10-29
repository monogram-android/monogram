pub(crate) use mono_proto_messages::messages_contact_send::*;

pub type ContactSendSpec = mono_proto_messages::messages_contact_send::ContactSendSpec;

#[uniffi::remote(Record)]
pub struct ContactSendSpec {
    pub phone: String,
    pub first_name: String,
    pub last_name: String,
    pub vcard: String,
}
