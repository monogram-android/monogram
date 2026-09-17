//! Request cancellation independent of the lifetime of a client or socket lane.
use crate::tcp::ConnectionControl;
use parking_lot::Mutex;
use std::cell::RefCell;
use std::collections::HashMap;
use std::net::TcpStream;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, LazyLock,
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
mod tests {
    use super::*;
    #[test]
    fn cancellation_is_scoped_and_binding_restores() {
        let first = create();
        let second = create();
        let original = bind(first);
        assert!(check().is_ok());
        cancel(first);
        assert!(check().is_err());
        let previous = bind(second);
        assert!(check().is_ok());
        bind(previous);
        assert!(check().is_err());
        bind(original);
        release(first);
        release(second);
    }

    #[test]
    fn cancel_closes_only_registered_socket_and_rejects_reuse() {
        use std::io::Read;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let socket = Arc::new(TcpStream::connect(listener.local_addr().unwrap()).unwrap());
        let (mut server, _) = listener.accept().unwrap();
        server
            .set_read_timeout(Some(std::time::Duration::from_secs(1)))
            .unwrap();
        let id = create();
        let previous = bind(id);
        register(&socket).unwrap();
        cancel(id);
        assert_eq!(server.read(&mut [0; 1]).unwrap(), 0);
        assert!(register(&socket).is_err());
        bind(previous);
        release(id);
    }

    #[test]
    fn finished_request_cannot_close_socket_reused_by_next_request() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let socket = Arc::new(TcpStream::connect(listener.local_addr().unwrap()).unwrap());
        let (mut server, _) = listener.accept().unwrap();
        server
            .set_read_timeout(Some(std::time::Duration::from_secs(1)))
            .unwrap();
        let first = create();
        let second = create();
        let previous = bind(first);
        register(&socket).unwrap();
        detach_sockets();
        bind(second);
        register(&socket).unwrap();
        cancel(first);
        socket.as_ref().write_all(b"ok").unwrap();
        let mut bytes = [0; 2];
        server.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"ok");
        bind(previous);
        release(first);
        release(second);
    }
}
