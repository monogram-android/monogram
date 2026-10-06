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
