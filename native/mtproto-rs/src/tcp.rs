use std::cell::RefCell;
use std::net::TcpStream;
use std::sync::Arc;
use std::time::Duration;

use monogram_mtproto_transport::tcp as transport_tcp;
use tellers_mtproto_transport::{Connection, Error as TransportError};

pub(crate) fn set_proxy(
    config: Option<monogram_mtproto_transport::ProxyConfig>,
) -> Result<(), TransportError> {
    transport_tcp::set_proxy(config)
}

fn proxy() -> Option<monogram_mtproto_transport::ProxyConfig> {
    transport_tcp::config().proxy
}

pub(crate) fn set_transport_mode(mode: monogram_mtproto_transport::TransportMode) {
    transport_tcp::set_transport_mode(mode);
}
fn transport_mode() -> monogram_mtproto_transport::TransportMode {
    transport_tcp::config().mode
}
pub struct TcpConnection {
    inner: monogram_mtproto_transport::TcpConnection,
    stream: Arc<TcpStream>,
    read_timeout: Duration,
}

fn closed_error() -> TransportError {
    TransportError::Connection("client closed".into())
}
pub(crate) type ConnectionControl = monogram_mtproto_transport::tcp::ConnectionControl;

thread_local! {
    static CONTROL: RefCell<Option<Arc<ConnectionControl>>> = const { RefCell::new(None) };
}

pub(crate) fn with_connection_control<T>(
    control: &Arc<ConnectionControl>,
    body: impl FnOnce() -> T,
) -> T {
    struct Restore(Option<Arc<ConnectionControl>>);
    impl Drop for Restore {
        fn drop(&mut self) {
            crate::request_control::detach_sockets();
            CONTROL.with(|cell| cell.replace(self.0.take()));
        }
    }
    let _restore = Restore(CONTROL.with(|cell| cell.replace(Some(control.clone()))));
    transport_tcp::with_connection_control(control, body)
}
pub(crate) fn wait_reconnect(delay: Duration) -> Result<(), TransportError> {
    let deadline = std::time::Instant::now() + delay;
    loop {
        crate::request_control::check()?;
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        transport_tcp::wait_reconnect(remaining.min(Duration::from_millis(50)))?;
        if remaining.is_zero() {
            return Ok(());
        }
    }
}

fn check_open() -> Result<(), TransportError> {
    wait_reconnect(Duration::ZERO)
}

pub(crate) fn while_client_open<T>(operation: impl FnOnce() -> T) -> Result<T, TransportError> {
    transport_tcp::while_client_open(operation)
}

fn open_registered_connection(
    addr: &str,
    connect_secs: u64,
) -> Result<(monogram_mtproto_transport::TcpConnection, Arc<TcpStream>), TransportError> {
    check_open()?;
    let mut socket = None;
    let inner = monogram_mtproto_transport::TcpConnection::connect_controlled(
        addr,
        Duration::from_secs(connect_secs.max(1)),
        proxy().as_ref(),
        |stream| {
            let stream = stream.clone();
            if let Some(control) = CONTROL.with(|cell| cell.borrow().clone()) {
                control.register(&stream).map_err(|_| {
                    monogram_mtproto_transport::ProxyError::Handshake("connection cancelled".into())
                })?;
            }
            crate::request_control::register(&stream).map_err(|_| {
                monogram_mtproto_transport::ProxyError::Handshake("request cancelled".into())
            })?;
            socket = Some(stream);
            Ok(())
        },
        || {
            check_open().map_err(|_| {
                monogram_mtproto_transport::ProxyError::Handshake("connection cancelled".into())
            })
        },
    )
    .map_err(|error| TransportError::Connection(error.to_string()))?;
    let socket = socket.ok_or_else(closed_error)?;
    check_open()?;
    Ok((inner, socket))
}

impl TcpConnection {
    pub fn set_io_timeout(&mut self, secs: u64) {
        self.set_io_timeout_ms(secs.saturating_mul(1000).max(1));
    }

    pub fn set_io_timeout_ms(&mut self, ms: u64) {
        let timeout = Duration::from_millis(ms.max(50));
        self.read_timeout = timeout;
        let _ = self.stream.set_read_timeout(Some(timeout));
        let _ = self.stream.set_write_timeout(Some(timeout));
    }

    pub fn connect_timeout_secs(addr: &str, connect_secs: u64) -> Result<Self, TransportError> {
        let (inner, stream) = open_registered_connection(addr, connect_secs)?;
        Ok(Self {
            inner,
            stream,
            read_timeout: Duration::from_secs(8),
        })
    }
}

impl Connection for TcpConnection {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        check_open()?;
        crate::request_control::register(&self.stream)?;
        self.inner
            .send(packet)
            .map_err(|e| TransportError::Connection(e.to_string()))
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        crate::request_control::register(&self.stream)?;
        let deadline = std::time::Instant::now() + self.read_timeout;
        loop {
            check_open()?;
            match self.inner.receive(output) {
                Ok(result) => return Ok(result),
                Err(error) if std::time::Instant::now() < deadline => {
                    let _ = error;
                    continue;
                }
                Err(error) => return Err(TransportError::Connection(error.to_string())),
            }
        }
    }

    fn close(&mut self) -> Result<(), TransportError> {
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
        Ok(())
    }
}

enum NativeTransportConnection {
    Obfuscated(monogram_mtproto_transport::ObfuscatedConnection),
    Http(monogram_mtproto_transport::TransportConnection),
}

pub struct ObfuscatedTcp {
    inner: NativeTransportConnection,
    stream: Arc<TcpStream>,
    read_timeout: Duration,
}

impl ObfuscatedTcp {
    pub fn set_io_timeout(&mut self, secs: u64) {
        self.set_io_timeout_ms(secs.saturating_mul(1000));
    }

    pub fn set_io_timeout_ms(&mut self, ms: u64) {
        self.read_timeout = Duration::from_millis(ms.max(50));
        let read = self.read_timeout;
        let _ = match &mut self.inner {
            NativeTransportConnection::Obfuscated(connection) => connection.set_io_timeout_ms(ms),
            NativeTransportConnection::Http(connection) => connection.set_io_timeout_ms(ms),
        };
        let _ = match &mut self.inner {
            NativeTransportConnection::Obfuscated(connection) => connection.set_read_timeout(read),
            NativeTransportConnection::Http(connection) => connection.set_read_timeout(read),
        };
        let _ = self.stream.set_read_timeout(Some(read));
    }
}

impl Connection for ObfuscatedTcp {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        check_open()?;
        crate::request_control::register(&self.stream)?;
        match &mut self.inner {
            NativeTransportConnection::Obfuscated(connection) => connection.send(packet),
            NativeTransportConnection::Http(connection) => connection.send(packet),
        }
        .map_err(|error| TransportError::Connection(error.to_string()))
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        crate::request_control::register(&self.stream)?;
        let deadline = std::time::Instant::now() + self.read_timeout;
        loop {
            check_open()?;
            match match &mut self.inner {
                NativeTransportConnection::Obfuscated(connection) => connection.receive(output),
                NativeTransportConnection::Http(connection) => connection.receive(output),
            } {
                Ok(result) => return Ok(result),
                Err(error) if std::time::Instant::now() < deadline => {
                    let _ = error;
                    continue;
                }
                Err(error) => return Err(TransportError::Connection(error.to_string())),
            }
        }
    }

    fn close(&mut self) -> Result<(), TransportError> {
        match &mut self.inner {
            NativeTransportConnection::Obfuscated(connection) => connection.close(),
            NativeTransportConnection::Http(connection) => connection.close(),
        }
        .map_err(|error| TransportError::Connection(error.to_string()))
    }
}

pub fn connect_obfuscated(addr: &str) -> Result<ObfuscatedTcp, TransportError> {
    connect_obfuscated_timeout(addr, 5)
}

pub fn connect_obfuscated_timeout(
    addr: &str,
    connect_secs: u64,
) -> Result<ObfuscatedTcp, TransportError> {
    connect_obfuscated_timeout_obf(addr, connect_secs, None, None)
}

fn normalize_dc_secret(secret: Option<&[u8]>) -> Result<Option<[u8; 16]>, TransportError> {
    match secret {
        Some(bytes) if bytes.len() == 16 => {
            let mut normalized = [0_u8; 16];
            normalized.copy_from_slice(bytes);
            Ok(Some(normalized))
        }
        Some(_) => Err(TransportError::Connection(
            "invalid MTProto secret length".into(),
        )),
        None => Ok(None),
    }
}

pub fn connect_obfuscated_timeout_obf(
    addr: &str,
    connect_secs: u64,
    dc_id: Option<i16>,
    secret: Option<&[u8]>,
) -> Result<ObfuscatedTcp, TransportError> {
    let secret = if let Some(config) = proxy() {
        if config.kind == monogram_mtproto_transport::ProxyKind::Mtproto {
            Some(
                config
                    .secret
                    .ok_or_else(|| TransportError::Connection("invalid proxy secret".into()))?,
            )
        } else {
            normalize_dc_secret(secret)?
        }
    } else {
        normalize_dc_secret(secret)?
    };
    let (connection, stream) = open_registered_connection(addr, connect_secs)?;
    let mode = transport_mode();
    let inner = match mode {
        monogram_mtproto_transport::TransportMode::PaddedIntermediate => {
            NativeTransportConnection::Obfuscated(
                connection
                    .into_obfuscated(secret, dc_id)
                    .map_err(|error| TransportError::Connection(error.to_string()))?,
            )
        }
        monogram_mtproto_transport::TransportMode::Http => {
            if proxy()
                .is_some_and(|config| config.kind == monogram_mtproto_transport::ProxyKind::Mtproto)
            {
                return Err(TransportError::Connection(
                    "Telegram HTTP transport cannot run through an MTProto proxy".into(),
                ));
            }
            NativeTransportConnection::Http(
                connection
                    .into_transport(
                        addr,
                        monogram_mtproto_transport::TransportMode::Http,
                        dc_id,
                        secret,
                    )
                    .map_err(|error| TransportError::Connection(error.to_string()))?,
            )
        }
    };
    let mut connection = ObfuscatedTcp {
        inner,
        stream,
        read_timeout: Duration::from_secs(connect_secs.max(1)),
    };
    connection.set_io_timeout(connect_secs.max(1));
    Ok(connection)
}

fn probe_packet() -> Result<Vec<u8>, TransportError> {
    let mut raw = [0_u8; 16];
    tellers_mtproto_crypto::fill_random(&mut raw)
        .map_err(|error| TransportError::Connection(error.to_string()))?;
    let request = tellers_mtproto::transport::ReqPqMultiRequest {
        nonce: bnum::types::I128::from_le_bytes(raw),
    };
    let mut encoder = tellers_mtproto::codec::Encoder::new();
    tellers_mtproto::codec::Boxed::encode_boxed(&request, &mut encoder)
        .map_err(|error| TransportError::Connection(error.to_string()))?;
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs() as i64)
        .unwrap_or(0);
    let plain =
        tellers_mtproto_engine::encode_plain_message(&tellers_mtproto_engine::PlainMessage {
            message_id: secs << 32,
            body: encoder.into_bytes(),
        })
        .map_err(|error| TransportError::Connection(error.to_string()))?;
    let mut framing = tellers_mtproto_transport::PaddedIntermediate::default();
    tellers_mtproto_transport::Framing::encode(&mut framing, &plain)
        .map_err(|error| TransportError::Connection(error.to_string()))
}

/// Time a throwaway connection through `proxy` to a public DC.
/// Does not read or change the process-wide proxy used by the signed-in session.
/// The clock matches Telegram Desktop: it starts after the proxy/fake-TLS handshake
/// and stops when the datacenter answers `req_pq_multi`.
pub fn probe_proxy(proxy: monogram_mtproto_transport::ProxyConfig) -> Result<i64, TransportError> {
    let addr = crate::rpc::dc_endpoints(2)
        .first()
        .copied()
        .ok_or_else(|| TransportError::Connection("no DC endpoints".into()))?;
    let timeout = Duration::from_secs(10);
    let connection =
        monogram_mtproto_transport::TcpConnection::connect(addr, timeout, Some(&proxy))
            .map_err(|error| TransportError::Connection(format!("connect: {error}")))?;
    let started = std::time::Instant::now();
    let secret = if proxy.kind == monogram_mtproto_transport::ProxyKind::Mtproto {
        proxy.secret
    } else {
        None
    };
    let mut obfuscated = connection
        .into_obfuscated(secret, Some(2))
        .map_err(|error| TransportError::Connection(format!("obfuscate: {error}")))?;
    let _ = obfuscated.set_timeout(timeout);
    let packet = probe_packet()?;
    obfuscated
        .send(&packet)
        .map_err(|error| TransportError::Connection(format!("send: {error}")))?;
    let mut reply = [0_u8; 64];
    let mut filled = 0;
    let deadline = std::time::Instant::now() + timeout;
    while filled == 0 {
        if std::time::Instant::now() >= deadline {
            return Err(TransportError::Connection(format!(
                "handshake timed out after {}ms with {filled} bytes",
                started.elapsed().as_millis()
            )));
        }
        match obfuscated.receive(&mut reply[filled..]) {
            Ok(0) => {
                return Err(TransportError::Connection(
                    "proxy closed during handshake".into(),
                ));
            }
            Ok(count) => filled += count,
            Err(error) => {
                let message = error.to_string();
                let retry = message.contains("Try again")
                    || message.contains("timed out")
                    || message.contains("WouldBlock")
                    || message.contains("os error 11");
                if retry && std::time::Instant::now() < deadline {
                    std::thread::sleep(Duration::from_millis(20));
                    continue;
                }
                return Err(TransportError::Connection(format!(
                    "read after {}ms: {message}",
                    started.elapsed().as_millis()
                )));
            }
        }
    }
    let _ = obfuscated.close();
    Ok(started.elapsed().as_millis().max(1) as i64)
}

pub fn connect_obfuscated_dc(addrs: &[&str]) -> Result<ObfuscatedTcp, TransportError> {
    let mut last_err = None;
    for addr in addrs {
        match connect_obfuscated(addr) {
            Ok(conn) => return Ok(conn),
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or_else(|| TransportError::Connection("no DC endpoints".into())))
}
#[cfg(test)]
#[path = "../tests/unit/tcp_tests.rs"]
mod tests;
