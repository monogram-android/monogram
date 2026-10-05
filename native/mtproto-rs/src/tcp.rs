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
        let _ = self
            .stream
            .set_read_timeout(Some(timeout.min(Duration::from_millis(250))));
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
        let _ = match &mut self.inner {
            NativeTransportConnection::Obfuscated(connection) => connection.set_io_timeout_ms(ms),
            NativeTransportConnection::Http(connection) => connection.set_io_timeout_ms(ms),
        };
        let _ = self
            .stream
            .set_read_timeout(Some(self.read_timeout.min(Duration::from_millis(250))));
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
            Some(config.secret.ok_or_else(|| {
                TransportError::Connection("invalid proxy secret".into())
            })?)
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
    let plain = tellers_mtproto_engine::encode_plain_message(
        &tellers_mtproto_engine::PlainMessage {
            message_id: secs << 32,
            body: encoder.into_bytes(),
        },
    )
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
    let connection = monogram_mtproto_transport::TcpConnection::connect(addr, timeout, Some(&proxy))
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
mod tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn persistence_does_not_block_io_and_close_joins_active_save() {
        let control = Arc::new(ConnectionControl::default());
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let saving = control.clone();
        let saver = std::thread::spawn(move || {
            saving
                .while_open(|| {
                    entered_tx.send(()).unwrap();
                    release_rx.recv_timeout(Duration::from_secs(5)).unwrap();
                })
                .unwrap()
        });
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        let checking = control.clone();
        let (checked_tx, checked_rx) = std::sync::mpsc::channel();
        let checker = std::thread::spawn(move || {
            checked_tx
                .send(checking.wait(Duration::ZERO).is_ok())
                .unwrap();
        });
        let checked = checked_rx.recv_timeout(Duration::from_millis(500));
        let closing = control.clone();
        let (closed_tx, closed_rx) = std::sync::mpsc::channel();
        let closer = std::thread::spawn(move || {
            closing.close();
            closed_tx.send(()).unwrap();
        });
        let observing = control.clone();
        let (observed_tx, observed_rx) = std::sync::mpsc::channel();
        let observer = std::thread::spawn(move || {
            observed_tx
                .send(observing.wait(Duration::from_secs(3)).is_err())
                .unwrap();
        });
        let observed = observed_rx.recv_timeout(Duration::from_millis(500));
        let closed_early = closed_rx.try_recv().is_ok();
        release_tx.send(()).unwrap();
        saver.join().unwrap();
        checker.join().unwrap();
        closer.join().unwrap();
        observer.join().unwrap();
        assert_eq!(checked.ok(), Some(true), "save blocked socket state checks");
        assert_eq!(observed.ok(), Some(true), "save delayed I/O shutdown");
        assert!(!closed_early, "close returned before persistence completed");
        assert!(control.while_open(|| panic!("save after close")).is_err());
    }

    #[test]
    fn close_wakes_receive_and_prevents_new_connections() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let control = Arc::new(ConnectionControl::default());
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let addr = listener.local_addr().unwrap().to_string();
        let worker_control = control.clone();
        let worker = std::thread::spawn(move || {
            with_connection_control(&worker_control, || {
                let mut conn = TcpConnection::connect_timeout_secs(&addr, 1).unwrap();
                ready_tx.send(()).unwrap();
                let result = conn.receive(&mut [0; 4]);
                assert!(matches!(result, Ok(0) | Err(_)));
                assert!(TcpConnection::connect_timeout_secs(&addr, 1).is_err());
                done_tx.send(()).unwrap();
            });
        });
        let (_peer, _) = listener.accept().unwrap();
        ready_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        control.close();
        control.close();
        done_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        worker.join().unwrap();
    }

    #[test]
    fn close_wakes_obfuscated_receive() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let control = Arc::new(ConnectionControl::default());
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let addr = listener.local_addr().unwrap().to_string();
        let worker_control = control.clone();
        let worker = std::thread::spawn(move || {
            with_connection_control(&worker_control, || {
                let mut connection = connect_obfuscated_timeout(&addr, 30).unwrap();
                ready_tx.send(()).unwrap();
                let result = connection.receive(&mut [0; 4]);
                assert!(matches!(result, Ok(0) | Err(_)));
                done_tx.send(()).unwrap();
            });
        });
        let (_peer, _) = listener.accept().unwrap();
        ready_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        control.close();
        done_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        worker.join().unwrap();
    }

    #[test]
    fn close_cancels_backoff_without_affecting_other_clients() {
        let control = Arc::new(ConnectionControl::default());
        let worker_control = control.clone();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            with_connection_control(&worker_control, || {
                done_tx
                    .send(wait_reconnect(Duration::from_secs(60)).is_err())
                    .unwrap();
            });
        });
        control.close();
        assert!(done_rx.recv_timeout(Duration::from_secs(2)).unwrap());
        worker.join().unwrap();
        with_connection_control(&Arc::new(ConnectionControl::default()), || {
            assert!(check_open().is_ok());
            with_connection_control(&control, || assert!(check_open().is_err()));
            assert!(check_open().is_ok());
        });
    }

    #[test]
    fn framing_survives_fragmented_tcp_and_eof() {
        use tellers_mtproto_transport::{Framing, PaddedIntermediate};
        let mut framing = PaddedIntermediate::default();
        let wire = framing.encode(&[7; 32]).unwrap();
        for length in 0..wire.len() {
            assert!(matches!(
                framing.decode(&wire[..length]),
                Err(TransportError::Incomplete { .. })
            ));
        }
        assert!(framing.decode(&wire).unwrap().payload.starts_with(&[7; 32]));
    }
}
