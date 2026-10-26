#![forbid(unsafe_code)]
pub(crate) mod proxy;
pub mod tcp;

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::proxy::{http::http_connect, socks5::socks5_connect, tls::TlsStream};
use tellers_mtproto_transport::{
    Connection, Error as TransportError, ObfuscatedProtocol, ObfuscatedStream,
};
use thiserror::Error;

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
        let connection =
            Self::connect_registered(target, timeout, proxy, &mut register, &mut check)?;
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

    pub fn set_read_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.inner.set_read_timeout(timeout)
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

    pub fn set_read_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        match self {
            Self::Obfuscated(connection) => connection.set_read_timeout(timeout),
            Self::Http(connection) => connection.set_read_timeout(timeout),
        }
    }
}
impl TcpConnection {
    pub fn connect(
        target: &str,
        timeout: Duration,
        proxy: Option<&ProxyConfig>,
    ) -> Result<Self, ProxyError> {
        Self::connect_controlled(target, timeout, proxy, |_| Ok(()), || Ok(()))
    }

    fn connect_registered(
        target: &str,
        timeout: Duration,
        proxy: Option<&ProxyConfig>,
        register: &mut impl FnMut(&std::sync::Arc<TcpStream>) -> Result<(), ProxyError>,
        check: &mut impl FnMut() -> Result<(), ProxyError>,
    ) -> Result<Self, ProxyError> {
        check()?;
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
        check()?;
        let mut stream = TcpStream::connect_timeout(&socket, timeout)?;
        stream.set_read_timeout(Some(timeout))?;
        stream.set_write_timeout(Some(timeout))?;
        stream.set_nodelay(true)?;
        let socket = std::sync::Arc::new(stream.try_clone()?);
        register(&socket)?;
        check()?;
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
    fn io_socket(&self) -> &TcpStream {
        match &self.stream {
            ProxyStream::Plain(stream) => stream,
            ProxyStream::Tls(stream) => stream.get_ref(),
            ProxyStream::FakeTls(stream) => stream.tcp(),
        }
    }

    pub fn set_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.socket.set_read_timeout(Some(timeout))?;
        self.socket.set_write_timeout(Some(timeout))?;
        self.io_socket().set_read_timeout(Some(timeout))?;
        self.io_socket().set_write_timeout(Some(timeout))?;
        self.timeout = timeout;
        Ok(())
    }

    pub fn set_read_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.socket.set_read_timeout(Some(timeout))?;
        self.io_socket().set_read_timeout(Some(timeout))?;
        Ok(())
    }

    pub fn into_obfuscated(
        self,
        secret: Option<[u8; 16]>,
        dc_id: Option<i16>,
    ) -> Result<ObfuscatedTcp, ProxyError> {
        let obf = make_obfuscated_stream(secret, dc_id, |nonce| {
            tellers_mtproto_crypto::fill_random(nonce)
                .map_err(|error| ProxyError::Transport(error.to_string()))
        })?;
        let mut result = ObfuscatedTcp { inner: self, obf };
        let header = *result.obf.header();
        result.inner.stream.write_all(&header)?;
        Ok(result)
    }
}

fn make_obfuscated_stream(
    secret: Option<[u8; 16]>,
    dc_id: Option<i16>,
    mut fill: impl FnMut(&mut [u8; 64]) -> Result<(), ProxyError>,
) -> Result<ObfuscatedStream, ProxyError> {
    for _ in 0..16 {
        let mut nonce = [0_u8; 64];
        fill(&mut nonce)?;
        match ObfuscatedStream::new_client(
            nonce,
            ObfuscatedProtocol::PaddedIntermediate,
            dc_id,
            secret.as_ref().map(|secret| secret.as_slice()),
        ) {
            Ok(stream) => return Ok(stream),
            Err(TransportError::InvalidObfuscationNonce) => continue,
            Err(error) => return Err(ProxyError::Transport(error.to_string())),
        }
    }
    Err(ProxyError::Transport(
        "could not generate transport obfuscation nonce".into(),
    ))
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
    pub fn set_read_timeout(&mut self, timeout: Duration) -> Result<(), ProxyError> {
        self.inner.set_read_timeout(timeout)
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

#[cfg(test)]
#[path = "../tests/unit/mtproxy_wire_tests.rs"]
mod mtproxy_wire_tests;

#[cfg(test)]
#[path = "../tests/unit/proxy_config_tests.rs"]
mod proxy_config_tests;
