pub type MtprotoError = mono_proto_client::adapter::types::error::MtprotoError;

#[uniffi::remote(Error)]
pub enum MtprotoError {
    UnknownClient,
    RegistrationRequired,
    PasswordRequired,
    Message(String),
}
