#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MtprotoError {
    #[error("unknown client handle")]
    UnknownClient,
    #[error("registration is not supported")]
    RegistrationRequired,
    #[error("two-factor authentication password required")]
    PasswordRequired,
    #[error("{0}")]
    Message(String),
}
