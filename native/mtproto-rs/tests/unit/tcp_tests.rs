use super::*;
use std::io::Read;
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
    let (mut peer, _) = listener.accept().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    peer.read_exact(&mut [0_u8; 64]).unwrap();
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
