use crate::{ProxyConfig, TransportConfig, TransportMode};
use parking_lot::{Condvar, Mutex};
use std::cell::RefCell;
use std::net::TcpStream;
use std::sync::{Arc, LazyLock, Weak};
use std::time::Duration;
use tellers_mtproto_transport::Error as TransportError;

static CONFIG: LazyLock<Mutex<TransportConfig>> =
    LazyLock::new(|| Mutex::new(TransportConfig::default()));

#[derive(Default)]
pub struct ControlState {
    pub closed: bool,
    pub streams: Vec<Weak<TcpStream>>,
}

pub struct ConnectionControl {
    pub config: TransportConfig,
    pub state: Mutex<ControlState>,
    pub persistence: Mutex<()>,
    pub wake: Condvar,
}

impl Default for ConnectionControl {
    fn default() -> Self {
        Self {
            config: CONFIG.lock().clone(),
            state: Mutex::default(),
            persistence: Mutex::default(),
            wake: Condvar::default(),
        }
    }
}

impl ConnectionControl {
    pub fn detach_sockets(&self) {
        self.state.lock().streams.clear();
    }
    pub fn while_open<T>(&self, operation: impl FnOnce() -> T) -> Result<T, TransportError> {
        let _commit = self.persistence.lock();
        if self.state.lock().closed {
            return Err(closed_error());
        }
        Ok(operation())
    }
    pub fn close(&self) {
        let mut state = self.state.lock();
        state.closed = true;
        for stream in state
            .streams
            .drain(..)
            .filter_map(|stream| stream.upgrade())
        {
            let _ = stream.shutdown(std::net::Shutdown::Both);
        }
        self.wake.notify_all();
        drop(state);
        let _commit = self.persistence.lock();
    }
    pub fn register(&self, stream: &Arc<TcpStream>) -> Result<(), TransportError> {
        let mut state = self.state.lock();
        if state.closed {
            let _ = stream.shutdown(std::net::Shutdown::Both);
            return Err(closed_error());
        }
        state.streams.retain(|entry| entry.strong_count() > 0);
        let weak = Arc::downgrade(stream);
        if !state.streams.iter().any(|entry| entry.ptr_eq(&weak)) {
            state.streams.push(weak);
        }
        Ok(())
    }
    pub fn wait(&self, delay: Duration) -> Result<(), TransportError> {
        let deadline = std::time::Instant::now() + delay;
        let mut state = self.state.lock();
        while !state.closed {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
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
thread_local! { static CONTROL: RefCell<Option<Arc<ConnectionControl>>> = const { RefCell::new(None) }; }

pub fn set_proxy(config: Option<ProxyConfig>) -> Result<(), TransportError> {
    if let Some(config) = &config {
        config
            .validate()
            .map_err(|error| TransportError::Connection(error.to_string()))?;
    }
    CONFIG.lock().proxy = config;
    Ok(())
}
pub fn set_transport_mode(mode: TransportMode) {
    CONFIG.lock().mode = mode;
}
pub fn config() -> TransportConfig {
    CONTROL
        .with(|cell| cell.borrow().as_ref().map(|control| control.config.clone()))
        .unwrap_or_else(|| CONFIG.lock().clone())
}
pub fn with_connection_control<T>(control: &Arc<ConnectionControl>, body: impl FnOnce() -> T) -> T {
    struct Restore(Option<Arc<ConnectionControl>>);
    impl Drop for Restore {
        fn drop(&mut self) {
            CONTROL.with(|cell| cell.replace(self.0.take()));
        }
    }
    let _restore = Restore(CONTROL.with(|cell| cell.replace(Some(control.clone()))));
    body()
}
pub fn wait_reconnect(delay: Duration) -> Result<(), TransportError> {
    let deadline = std::time::Instant::now() + delay;
    loop {
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
pub fn while_client_open<T>(operation: impl FnOnce() -> T) -> Result<T, TransportError> {
    match CONTROL.with(|cell| cell.borrow().clone()) {
        Some(control) => control.while_open(operation),
        None => Ok(operation()),
    }
}
