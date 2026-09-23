//! Blocking TCP adapter with client-owned shutdown for Tellers connections.

use parking_lot::{Condvar, Mutex};
use std::cell::RefCell;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, Weak};
use std::time::Duration;

use tellers_mtproto_crypto::fill_random;
use tellers_mtproto_transport::{
    Connection, Error as TransportError, ObfuscatedProtocol, ObfuscatedStream,
};

pub struct TcpConnection {
    stream: Arc<TcpStream>,
    read_timeout: Duration,
}

#[derive(Default)]
struct ControlState {
    closed: bool,
    streams: Vec<Weak<TcpStream>>,
}

#[derive(Default)]
pub(crate) struct ConnectionControl {
    state: Mutex<ControlState>,
    // Saves serialize with shutdown, but never monopolize socket-state checks.
    persistence: Mutex<()>,
    wake: Condvar,
}

impl ConnectionControl {
    pub(crate) fn detach_sockets(&self) {
        self.state.lock().streams.clear();
    }
    pub(crate) fn while_open<T>(&self, operation: impl FnOnce() -> T) -> Result<T, TransportError> {
        let _commit = self.persistence.lock();
        if self.state.lock().closed {
            return Err(closed_error());
        }
        Ok(operation())
    }
    pub(crate) fn close(&self) {
        let mut state = self.state.lock();
        state.closed = true;
        for stream in state.streams.drain(..).filter_map(|s| s.upgrade()) {
            let _ = stream.shutdown(std::net::Shutdown::Both);
        }
        self.wake.notify_all();
        drop(state);
        // Wait for any already-started commit, after closing sockets promptly.
        // Never hold state while joining persistence: saves check state under
        // the persistence lock, and socket I/O must observe closure immediately.
        let _commit = self.persistence.lock();
    }

    pub(crate) fn register(&self, stream: &Arc<TcpStream>) -> Result<(), TransportError> {
        let mut state = self.state.lock();
        if state.closed {
            let _ = stream.shutdown(std::net::Shutdown::Both);
            return Err(closed_error());
        }
        state.streams.retain(|s| s.strong_count() > 0);
        let weak = Arc::downgrade(stream);
        if !state.streams.iter().any(|existing| existing.ptr_eq(&weak)) {
            state.streams.push(weak);
        }
        Ok(())
    }

    fn wait(&self, delay: Duration) -> Result<(), TransportError> {
        let until = std::time::Instant::now() + delay;
        let mut state = self.state.lock();
        while !state.closed {
            let remaining = until.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                return Ok(());
            }
            self.wake.wait_for(&mut state, remaining);
        }
        Err(closed_error())
    }
}

fn closed_error() -> TransportError {
    TransportError::Connection("client closed".into())
}

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
            // The lane is still locked here. A later cancellation must not
            // close this parked socket after another request has acquired it.
            crate::request_control::detach_sockets();
            CONTROL.with(|cell| {
                cell.replace(self.0.take());
            });
        }
    }
    let _restore = Restore(CONTROL.with(|cell| cell.replace(Some(control.clone()))));
    body()
}

pub(crate) fn wait_reconnect(delay: Duration) -> Result<(), TransportError> {
    let deadline = std::time::Instant::now() + delay;
    loop {
        crate::request_control::check()?;
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        let slice = remaining.min(Duration::from_millis(50));
        match CONTROL.with(|cell| cell.borrow().clone()) {
            Some(control) => control.wait(slice)?,
            None => std::thread::sleep(slice),
        }
        if remaining.is_zero() {
            return Ok(());
        }
    }
}

fn check_open() -> Result<(), TransportError> {
    wait_reconnect(Duration::ZERO)
}

/// Serialize persistence commits with client destruction. No snapshot from a
/// completed old call may replace the session after close has returned.
pub(crate) fn while_client_open<T>(operation: impl FnOnce() -> T) -> Result<T, TransportError> {
    match CONTROL.with(|cell| cell.borrow().clone()) {
        Some(control) => control.while_open(operation),
        None => Ok(operation()),
    }
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
        check_open()?;
        let socket_addr: std::net::SocketAddr = addr
            .parse()
            .map_err(|e| TransportError::Connection(format!("bad address {addr}: {e}")))?;
        let stream =
            TcpStream::connect_timeout(&socket_addr, Duration::from_secs(connect_secs.max(1)))
                .map_err(|e| TransportError::Connection(e.to_string()))?;
        stream
            .set_read_timeout(Some(Duration::from_millis(250)))
            .map_err(|e| TransportError::Connection(e.to_string()))?;
        stream
            .set_write_timeout(Some(Duration::from_secs(8)))
            .map_err(|e| TransportError::Connection(e.to_string()))?;
        stream
            .set_nodelay(true)
            .map_err(|e| TransportError::Connection(e.to_string()))?;
        let stream = Arc::new(stream);
        if let Some(control) = CONTROL.with(|cell| cell.borrow().clone()) {
            control.register(&stream)?;
        }
        crate::request_control::register(&stream)?;
        Ok(Self {
            stream,
            read_timeout: Duration::from_secs(8),
        })
    }
}

impl Connection for TcpConnection {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        check_open()?;
        crate::request_control::register(&self.stream)?;
        self.stream
            .as_ref()
            .write_all(packet)
            .map_err(|e| TransportError::Connection(e.to_string()))
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        crate::request_control::register(&self.stream)?;
        let deadline = std::time::Instant::now() + self.read_timeout;
        loop {
            check_open()?;
            match self.stream.as_ref().read(output) {
                Err(e)
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::Interrupted
                            | std::io::ErrorKind::WouldBlock
                            | std::io::ErrorKind::TimedOut
                    ) && std::time::Instant::now() < deadline =>
                {
                    continue;
                }
                result => return result.map_err(|e| TransportError::Connection(e.to_string())),
            }
        }
    }

    fn close(&mut self) -> Result<(), TransportError> {
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
        Ok(())
    }
}

/// AES-CTR obfuscated padded-intermediate transport (not fake-TLS).
pub struct ObfuscatedTcp {
    inner: TcpConnection,
    obf: ObfuscatedStream,
}

/// Connect to a DC and send the 64-byte obfuscation header (no raw `eeeeeeee` preamble).
pub fn connect_obfuscated(addr: &str) -> Result<ObfuscatedTcp, TransportError> {
    connect_obfuscated_timeout(addr, 5)
}

pub fn connect_obfuscated_timeout(
    addr: &str,
    connect_secs: u64,
) -> Result<ObfuscatedTcp, TransportError> {
    connect_obfuscated_timeout_obf(addr, connect_secs, None, None)
}

/// Direct DC with optional `dcOption.secret` (same AES-CTR wrap as MTProxy).
pub fn connect_obfuscated_timeout_obf(
    addr: &str,
    connect_secs: u64,
    dc_id: Option<i16>,
    secret: Option<&[u8]>,
) -> Result<ObfuscatedTcp, TransportError> {
    let inner = TcpConnection::connect_timeout_secs(addr, connect_secs)?;
    let mut last_err: Option<TransportError> = None;
    for _ in 0..8 {
        let mut nonce = [0_u8; 64];
        fill_random(&mut nonce).map_err(|e| TransportError::Connection(e.to_string()))?;
        match ObfuscatedStream::new_client(
            nonce,
            ObfuscatedProtocol::PaddedIntermediate,
            dc_id,
            secret,
        ) {
            Ok(obf) => {
                let mut conn = ObfuscatedTcp { inner, obf };
                let header = *conn.obf.header();
                conn.inner.send(&header)?;
                return Ok(conn);
            }
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or(TransportError::InvalidObfuscationNonce))
}

/// Try DC endpoints in order (5222/80 before 443).
pub fn connect_obfuscated_dc(addrs: &[&str]) -> Result<ObfuscatedTcp, TransportError> {
    let mut last_err: Option<TransportError> = None;
    for addr in addrs {
        match connect_obfuscated(addr) {
            Ok(conn) => return Ok(conn),
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or_else(|| TransportError::Connection("no DC endpoints".into())))
}

impl ObfuscatedTcp {
    pub fn set_io_timeout(&mut self, secs: u64) {
        self.inner.set_io_timeout(secs);
    }

    pub fn set_io_timeout_ms(&mut self, ms: u64) {
        self.inner.set_io_timeout_ms(ms);
    }
}

impl Connection for ObfuscatedTcp {
    fn send(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        let mut buf = packet.to_vec();
        self.obf.encrypt(&mut buf);
        self.inner.send(&buf)
    }

    fn receive(&mut self, output: &mut [u8]) -> Result<usize, TransportError> {
        let n = self.inner.receive(output)?;
        self.obf.decrypt(&mut output[..n]);
        Ok(n)
    }

    fn close(&mut self) -> Result<(), TransportError> {
        self.inner.close()
    }
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
