use thiserror::Error;

#[derive(Debug, Error)]
pub enum ProxyError {
    #[error("invalid proxy configuration: {0}")]
    InvalidConfig(&'static str),
    #[error("proxy handshake failed: {0}")]
    Handshake(String),
    #[error("proxy I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("TLS failed: {0}")]
    Tls(String),
}
