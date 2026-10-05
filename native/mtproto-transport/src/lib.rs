#![forbid(unsafe_code)]
pub(crate) mod proxy;
pub mod tcp;

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::proxy::tls::TlsStream;
use tellers_mtproto_transport::{
    Connection, Error as TransportError, ObfuscatedProtocol, ObfuscatedStream,
};
use thiserror::Error;

const MAX_PROXY_REPLY: usize = 16 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProxyKind {
    Socks5,
    Http,
    Https,
    Mtproto,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransportMode {
    PaddedIntermediate,
    Http,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TransportConfig {
    pub proxy: Option<ProxyConfig>,
    pub mode: TransportMode,
}

impl Default for TransportConfig {
    fn default() -> Self {
        Self {
            proxy: None,
            mode: TransportMode::PaddedIntermediate,
        }
    }
}
#[derive(Clone, Eq, PartialEq)]
pub struct ProxyConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password: Option<String>,
    pub secret: Option<[u8; 16]>,
    pub fake_tls_domain: Option<String>,
}

impl std::fmt::Debug for ProxyConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ProxyConfig")
            .field("kind", &self.kind)
            .field("host", &self.host)
            .field("port", &self.port)
            .field("credentials", &"[REDACTED]")
            .finish_non_exhaustive()
    }
}

impl ProxyConfig {
    /// `None` means no secret. Fake-TLS secrets (`ee` + key + domain) keep the
    /// 16-byte key and the SNI domain; `dd` secrets keep only the key.
    pub fn decode_mtproto_secret(
        bytes: &[u8],
    ) -> Result<Option<([u8; 16], Option<String>)>, ProxyError> {
        if bytes.is_empty() {
            return Ok(None);
        }
        if bytes.len() > 17 + 182 {
            return Err(ProxyError::InvalidConfig("invalid proxy secret"));
        }
        let (prefix, start) = match (bytes.len(), bytes.first().copied()) {
            (17.., Some(0xdd) | Some(0xee)) => (Some(bytes[0]), 1),
            _ => (None, 0),
        };
        let valid = match prefix {
            None => bytes.len() == 16,
            Some(0xdd) => bytes.len() == 17,
            Some(0xee) => bytes.len() > 17,
            _ => false,
        };
        if !valid {
            return Err(ProxyError::InvalidConfig("invalid proxy secret"));
        }
        let mut key = [0_u8; 16];
        key.copy_from_slice(&bytes[start..start + 16]);
        let domain = if prefix == Some(0xee) {
            let domain = std::str::from_utf8(&bytes[start + 16..])
                .map_err(|_| ProxyError::InvalidConfig("invalid proxy secret"))?;
            if !valid_fake_tls_domain(domain) {
                return Err(ProxyError::InvalidConfig("invalid proxy secret"));
            }
            Some(domain.to_owned())
        } else {
            None
        };
        Ok(Some((key, domain)))
    }
}

fn valid_fake_tls_domain(domain: &str) -> bool {
    !domain.is_empty()
        && domain.len() <= 182
        && domain.is_ascii()
        && domain.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label.as_bytes()[0].is_ascii_alphanumeric()
                && label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
}

impl ProxyConfig {
    pub fn validate(&self) -> Result<(), ProxyError> {
        if self.host.is_empty() || self.host.len() > 255 || self.host.bytes().any(|b| b <= 0x20) {
            return Err(ProxyError::InvalidConfig("invalid proxy host"));
        }
        if self.port == 0 {
            return Err(ProxyError::InvalidConfig("invalid proxy port"));
        }
        match self.kind {
            ProxyKind::Mtproto => {
                if self.secret.is_none() || self.username.is_some() || self.password.is_some() {
                    return Err(ProxyError::InvalidConfig(
                        "invalid MTProto proxy credentials",
                    ));
                }
            }
            ProxyKind::Socks5 | ProxyKind::Http | ProxyKind::Https => {
                if self.username.is_some() != self.password.is_some() || self.secret.is_some() {
                    return Err(ProxyError::InvalidConfig("invalid proxy credentials"));
                }
            }
        }
        Ok(())
    }

    pub fn mtproto_secret_hex(&self) -> Option<String> {
        self.secret
            .map(|secret| secret.iter().map(|byte| format!("{byte:02x}")).collect())
    }
}

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
    #[error("transport failed: {0}")]
    Transport(String),
}

impl From<ProxyError> for TransportError {
    fn from(error: ProxyError) -> Self {
        TransportError::Connection(error.to_string())
    }
}

pub struct TcpConnection {
    stream: ProxyStream,
    socket: std::sync::Arc<TcpStream>,
    timeout: Duration,
}
enum ProxyStream {
    Plain(TcpStream),
    Tls(TlsStream),
    FakeTls(crate::proxy::faketls::FakeTlsStream),
}

impl Read for ProxyStream {
    fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.read(output),
            Self::Tls(stream) => stream.read(output),
            Self::FakeTls(stream) => stream.read(output),
        }
    }
}
impl Write for ProxyStream {
    fn write(&mut self, input: &[u8]) -> std::io::Result<usize> {
        match self {
            Self::Plain(stream) => stream.write(input),
            Self::Tls(stream) => stream.write(input),
            Self::FakeTls(stream) => stream.write(input),
        }
    }
    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Self::Plain(stream) => stream.flush(),
            Self::Tls(stream) => stream.flush(),
            Self::FakeTls(stream) => stream.flush(),
        }
    }
}

impl TcpConnection {
    pub fn connect_controlled(
        target: &str,
        timeout: Duration,
        proxy: Option<&ProxyConfig>,
        mut register: impl FnMut(&std::sync::Arc<TcpStream>) -> Result<(), ProxyError>,
        mut check: impl FnMut() -> Result<(), ProxyError>,
    ) -> Result<Self, ProxyError> {
        check()?;
        let connection = Self::connect(target, timeout, proxy)?;
        let stream = connection.socket.clone();
        register(&stream)?;
        check()?;
        Ok(connection)
    }

    pub fn into_http(self, target: &str) -> Result<HttpTransport, ProxyError> {
        match self.into_transport(target, TransportMode::Http, None, None)? {
            TransportConnection::Http(connection) => Ok(connection),
            TransportConnection::Obfuscated(_) => {
                Err(ProxyError::Transport("unexpected transport mode".into()))
            }
        }
    }

    pub fn into_transport(
        self,
        _target: &str,
        _mode: TransportMode,
        dc_id: Option<i16>,
        secret: Option<[u8; 16]>,
    ) -> Result<TransportConnection, ProxyError> {
        match _mode {
            TransportMode::PaddedIntermediate => Ok(TransportConnection::Obfuscated(
                self.into_obfuscated(secret, dc_id)?,
            )),
            TransportMode::Http => Ok(TransportConnection::Http(HttpTransport {
                inner: self,
                host: _target
                    .rsplit_once(':')
                    .map(|(host, _)| host.to_owned())
                    .unwrap_or_else(|| _target.to_owned()),
                response: Vec::new(),
            })),
        }
    }
}

pub type ObfuscatedConnection = ObfuscatedTcp;
pub type HttpConnection = ObfuscatedTcp;
pub type HttpTransportConnection = HttpTransport;

pub struct HttpTransport {
    inner: TcpConnection,
    host: String,
    response: Vec<u8>,
}

pub enum TransportConnection {
    Obfuscated(ObfuscatedConnection),
    Http(HttpTransportConnection),
}
impl HttpTransport {
    pub fn set_io_timeout_ms(&mut self, timeout: u64) -> Result<(), ProxyError> {
        self.inner
            .set_timeout(Duration::from_millis(timeout.max(50)))
    }
}

impl Connection for HttpTransport {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        let request = tellers_mtproto_transport::http_request(&self.host, packet);
        self.inner.send(&request)
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        const MAX_PACKET: usize = 1024 * 1024;
        let mut chunk = [0_u8; 16 * 1024];
        loop {
            match tellers_mtproto_transport::http_response(&self.response, MAX_PACKET) {
                Ok(packet) => {
                    if packet.payload.len() > output.len() {
                        return Err(TransportError::Connection(
                            "HTTP response exceeds output buffer".into(),
                        ));
                    }
                    let count = packet.payload.len();
                    output[..count].copy_from_slice(&packet.payload);
                    self.response.drain(..packet.consumed);
                    return Ok(count);
                }
                Err(tellers_mtproto_transport::Error::Incomplete { .. }) => {
                    let count = self.inner.receive(&mut chunk)?;
                    if count == 0 {
                        return Ok(0);
                    }
                    if self.response.len().saturating_add(count) > MAX_PACKET + chunk.len() {
                        return Err(TransportError::Connection(
                            "HTTP response is too large".into(),
                        ));
                    }
                    self.response.extend_from_slice(&chunk[..count]);
                }
                Err(error) => return Err(TransportError::Connection(error.to_string())),
            }
        }
    }

    fn close(&mut self) -> Result<(), TransportError> {
        self.inner.close()
    }
}

impl Connection for TransportConnection {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        match self {
            Self::Obfuscated(connection) => connection.send(packet),
            Self::Http(connection) => connection.send(packet),
        }
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        match self {
            Self::Obfuscated(connection) => connection.receive(output),
            Self::Http(connection) => connection.receive(output),
        }
    }

    fn close(&mut self) -> Result<(), TransportError> {
        match self {
            Self::Obfuscated(connection) => connection.close(),
            Self::Http(connection) => connection.close(),
        }
    }
}

impl TransportConnection {
    pub fn set_io_timeout_ms(&mut self, timeout: u64) -> Result<(), ProxyError> {
        match self {
            Self::Obfuscated(connection) => connection.set_io_timeout_ms(timeout),
            Self::Http(connection) => connection.set_io_timeout_ms(timeout),
        }
    }
}
impl TcpConnection {
    pub fn connect(
        target: &str,
        timeout: Duration,
        proxy: Option<&ProxyConfig>,
    ) -> Result<Self, ProxyError> {
        if let Some(proxy) = proxy {
            proxy.validate()?;
        }
        let address = proxy
            .map(|proxy| format!("{}:{}", proxy.host, proxy.port))
            .unwrap_or_else(|| target.to_owned());
        let socket = address
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| ProxyError::Handshake("proxy address did not resolve".into()))?;
        let mut stream = TcpStream::connect_timeout(&socket, timeout)?;
        stream.set_read_timeout(Some(timeout))?;
        stream.set_write_timeout(Some(timeout))?;
        stream.set_nodelay(true)?;
        let socket = std::sync::Arc::new(stream.try_clone()?);
        let stream = match proxy {
            Some(proxy) => match proxy.kind {
                ProxyKind::Socks5 => {
                    let mut stream = stream;
                    socks5_connect(&mut stream, target, proxy)?;
                    ProxyStream::Plain(stream)
                }
                ProxyKind::Http => {
                    let mut stream = stream;
                    http_connect(&mut stream, target, proxy)?;
                    ProxyStream::Plain(stream)
                }
                ProxyKind::Https => {
                    let mut stream = crate::proxy::tls::connect(stream, proxy, timeout)?;
                    crate::proxy::tls::http_connect(&mut stream, target, proxy)?;
                    ProxyStream::Tls(stream)
                }
                ProxyKind::Mtproto => {
                    if let Some(domain) = proxy.fake_tls_domain.clone() {
                        let key = proxy
                            .secret
                            .ok_or_else(|| ProxyError::InvalidConfig("invalid proxy secret"))?;
                        crate::proxy::faketls::handshake(&mut stream, &domain, &key)?;
                        ProxyStream::FakeTls(crate::proxy::faketls::FakeTlsStream::new(stream))
                    } else {
                        ProxyStream::Plain(stream)
                    }
                }
            },
            None => ProxyStream::Plain(stream),
        };
        Ok(Self {
            stream,
            socket,
            timeout,
        })
    }
    pub fn set_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.socket.set_read_timeout(Some(timeout))?;
        self.socket.set_write_timeout(Some(timeout))?;
        self.timeout = timeout;
        Ok(())
    }

    pub fn into_obfuscated(
        self,
        secret: Option<[u8; 16]>,
        dc_id: Option<i16>,
    ) -> Result<ObfuscatedTcp, ProxyError> {
        let mut nonce = [0_u8; 64];
        tellers_mtproto_crypto::fill_random(&mut nonce)
            .map_err(|error| ProxyError::Transport(error.to_string()))?;
        let obf = ObfuscatedStream::new_client(
            nonce,
            ObfuscatedProtocol::PaddedIntermediate,
            dc_id,
            secret.as_ref().map(|secret| secret.as_slice()),
        )
        .map_err(|error| ProxyError::Transport(error.to_string()))?;
        let mut result = ObfuscatedTcp { inner: self, obf };
        let header = *result.obf.header();
        result.inner.stream.write_all(&header)?;
        Ok(result)
    }
}

impl Connection for TcpConnection {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        self.stream
            .write_all(packet)
            .map_err(|error| TransportError::Connection(error.to_string()))
    }
    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        self.stream
            .read(output)
            .map_err(|error| TransportError::Connection(error.to_string()))
    }
    fn close(&mut self) -> Result<(), TransportError> {
        self.socket
            .shutdown(std::net::Shutdown::Both)
            .map_err(|error| TransportError::Connection(error.to_string()))
    }
}

pub struct ObfuscatedTcp {
    inner: TcpConnection,
    obf: ObfuscatedStream,
}

impl ObfuscatedTcp {
    pub fn set_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.inner.set_timeout(timeout)
    }
    pub fn set_io_timeout_ms(&mut self, timeout: u64) -> Result<(), ProxyError> {
        self.set_timeout(Duration::from_millis(timeout.max(50)))
    }
}

impl Connection for ObfuscatedTcp {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        let mut encrypted = packet.to_vec();
        self.obf.encrypt(&mut encrypted);
        self.inner.send(&encrypted)
    }
    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        let count = self.inner.receive(output)?;
        self.obf.decrypt(&mut output[..count]);
        Ok(count)
    }
    fn close(&mut self) -> Result<(), TransportError> {
        self.inner.close()
    }
}

fn socks5_connect(
    stream: &mut TcpStream,
    target: &str,
    proxy: &ProxyConfig,
) -> Result<(), ProxyError> {
    let authenticated = proxy.username.is_some();
    stream.write_all(if authenticated {
        &[5, 2, 0, 2]
    } else {
        &[5, 1, 0]
    })?;
    let mut response = [0_u8; 2];
    stream.read_exact(&mut response)?;
    if response[0] != 5 || response[1] == 0xff {
        return Err(ProxyError::Handshake(
            "SOCKS5 authentication rejected".into(),
        ));
    }
    if response[1] == 2 {
        let username = proxy.username.as_deref().unwrap_or_default().as_bytes();
        let password = proxy.password.as_deref().unwrap_or_default().as_bytes();
        if username.len() > 255 || password.len() > 255 {
            return Err(ProxyError::InvalidConfig("SOCKS5 credentials are too long"));
        }
        stream.write_all(&[1, username.len() as u8])?;
        stream.write_all(username)?;
        stream.write_all(&[password.len() as u8])?;
        stream.write_all(password)?;
        let mut auth = [0_u8; 2];
        stream.read_exact(&mut auth)?;
        if auth != [1, 0] {
            return Err(ProxyError::Handshake("SOCKS5 credentials rejected".into()));
        }
    }
    let target = target
        .rsplit_once(':')
        .ok_or(ProxyError::InvalidConfig("target must contain a port"))?;
    let host = target.0.as_bytes();
    if host.len() > 255 {
        return Err(ProxyError::InvalidConfig("target host is too long"));
    }
    let port: u16 = target
        .1
        .parse()
        .map_err(|_| ProxyError::InvalidConfig("target port is invalid"))?;
    stream.write_all(&[5, 1, 0, 3, host.len() as u8])?;
    stream.write_all(host)?;
    stream.write_all(&port.to_be_bytes())?;
    let mut head = [0_u8; 4];
    stream.read_exact(&mut head)?;
    if head[1] != 0 {
        return Err(ProxyError::Handshake("SOCKS5 CONNECT rejected".into()));
    }
    let length = match head[3] {
        1 => 4,
        4 => 16,
        3 => {
            let mut size = [0_u8; 1];
            stream.read_exact(&mut size)?;
            size[0] as usize
        }
        _ => return Err(ProxyError::Handshake("SOCKS5 address type invalid".into())),
    };
    let mut discard = vec![0_u8; length + 2];
    stream.read_exact(&mut discard)?;
    Ok(())
}

fn http_connect(
    stream: &mut TcpStream,
    target: &str,
    proxy: &ProxyConfig,
) -> Result<(), ProxyError> {
    let mut request =
        format!("CONNECT {target} HTTP/1.1\\r\\nHost: {target}\\r\\nConnection: keep-alive\\r\\n");
    if let (Some(username), Some(password)) = (&proxy.username, &proxy.password) {
        use base64::Engine;
        request.push_str("Proxy-Authorization: Basic ");
        request.push_str(
            &base64::engine::general_purpose::STANDARD.encode(format!("{username}:{password}")),
        );
        request.push_str("\\r\\n");
    }
    request.push_str("\\r\\n");
    stream.write_all(request.as_bytes())?;
    let mut response = Vec::new();
    let mut byte = [0_u8; 1];
    while response.len() < MAX_PROXY_REPLY {
        stream.read_exact(&mut byte)?;
        response.push(byte[0]);
        if response.ends_with(b"\\r\\n\\r\\n") {
            break;
        }
    }
    let line = response
        .split(|byte| *byte == b'\n')
        .next()
        .ok_or_else(|| ProxyError::Handshake("HTTP proxy response missing status".into()))?;
    let text = std::str::from_utf8(line)
        .map_err(|_| ProxyError::Handshake("HTTP proxy response is not UTF-8".into()))?;
    let status = text
        .split_whitespace()
        .nth(1)
        .and_then(|value| value.parse::<u16>().ok())
        .ok_or_else(|| ProxyError::Handshake("HTTP proxy status is invalid".into()))?;
    if !(200..300).contains(&status) {
        return Err(ProxyError::Handshake(format!(
            "HTTP proxy returned status {status}"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_proxy_variants_without_exposing_secrets() {
        let config = ProxyConfig {
            kind: ProxyKind::Mtproto,
            host: "proxy.example".into(),
            port: 443,
            username: None,
            password: None,
            secret: Some([7; 16]),
            fake_tls_domain: None,
        };
        assert!(config.validate().is_ok());
        assert!(!format!("{config:?}").contains("070707"));
    }

    #[test]
    fn rejects_partial_credentials() {
        let config = ProxyConfig {
            kind: ProxyKind::Http,
            host: "proxy.example".into(),
            port: 8080,
            username: Some("user".into()),
            password: None,
            secret: None,
            fake_tls_domain: None,
        };
        assert!(config.validate().is_err());
    }
}
