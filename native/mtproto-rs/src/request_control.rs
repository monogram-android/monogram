//! Request cancellation independent of the lifetime of a client or socket lane.
use crate::tcp::ConnectionControl;
use crate::{HashMap, HashMapExt};
use parking_lot::Mutex;
use std::cell::RefCell;
use std::net::TcpStream;
use std::sync::{
    Arc, LazyLock,
    atomic::{AtomicU64, Ordering},
};
use tellers_mtproto_transport::Error;

static NEXT: AtomicU64 = AtomicU64::new(1);
static REQUESTS: LazyLock<Mutex<HashMap<u64, Arc<ConnectionControl>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
thread_local! {
    static CURRENT: RefCell<(u64, Option<Arc<ConnectionControl>>)> = const { RefCell::new((0, None)) };
}

pub fn create() -> u64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    REQUESTS
        .lock()
        .insert(id, Arc::new(ConnectionControl::default()));
    id
}

/// Returns the previous binding for coroutine ThreadContextElement restoration.
pub fn bind(id: u64) -> u64 {
    let control = if id == 0 {
        None
    } else {
        Some(REQUESTS.lock().get(&id).cloned().unwrap_or_else(|| {
            let closed = Arc::new(ConnectionControl::default());
            closed.close();
            closed
        }))
    };
    CURRENT.with(|cell| cell.replace((id, control)).0)
}

pub fn cancel(id: u64) {
    let control = REQUESTS.lock().get(&id).cloned();
    if let Some(control) = control {
        control.close();
    }
}

pub fn release(id: u64) {
    REQUESTS.lock().remove(&id);
}

pub(crate) fn detach_sockets() {
    CURRENT.with(|cell| {
        if let Some(control) = &cell.borrow().1 {
            control.detach_sockets();
        }
    });
}

pub(crate) fn check() -> Result<(), Error> {
    while_active(|| ())
}

pub(crate) fn while_active<T>(operation: impl FnOnce() -> T) -> Result<T, Error> {
    CURRENT
        .with(|cell| match &cell.borrow().1 {
            Some(control) => control.while_open(operation),
            None => Ok(operation()),
        })
        .map_err(|_| Error::Connection("request cancelled".into()))
}

pub(crate) fn register(stream: &Arc<TcpStream>) -> Result<(), Error> {
    CURRENT
        .with(|cell| match &cell.borrow().1 {
            Some(control) => control.register(stream),
            None => Ok(()),
        })
        .map_err(|_| Error::Connection("request cancelled".into()))
}

#[cfg(test)]
#[path = "../tests/unit/request_control_tests.rs"]
mod tests;
