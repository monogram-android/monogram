use super::*;

fn proxy_exchange(
    response: &'static [u8],
    connect: impl FnOnce(&mut TcpStream) -> Result<(), ProxyError>,
) -> (Result<(), ProxyError>, Vec<u8>) {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_millis(500)))
            .unwrap();
        let mut request = Vec::new();
        let mut byte = [0];
        while request.len() < 4096 {
            match socket.read(&mut byte) {
                Ok(1) => request.push(byte[0]),
                _ => break,
            }
            if request.ends_with(b"\r\n\r\n") {
                break;
            }
        }
        socket.write_all(response).unwrap();
        request
    });
    let mut socket = TcpStream::connect(address).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let result = connect(&mut socket);
    (result, server.join().unwrap())
}

fn http_proxy() -> ProxyConfig {
    ProxyConfig {
        kind: ProxyKind::Http,
        host: "127.0.0.1".into(),
        port: 8080,
        username: None,
        password: None,
        secret: None,
        fake_tls_domain: None,
    }
}

#[test]
fn http_connect_uses_crlf_and_preserves_tunnel_bytes() {
    let (result, request) = proxy_exchange(
        b"HTTP/1.1 200 Connection established\r\n\r\nTUNNEL",
        |socket| {
            http_connect(socket, "example.com:443", &http_proxy())?;
            let mut payload = [0; 6];
            socket.read_exact(&mut payload)?;
            assert_eq!(&payload, b"TUNNEL");
            Ok(())
        },
    );
    assert!(result.is_ok(), "{result:?}");
    assert_eq!(request, b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nConnection: keep-alive\r\n\r\n");
}

#[test]
fn http_connect_rejects_malformed_or_incomplete_success_headers() {
    for response in [
        b"garbage 200 OK\r\n\r\n".as_slice(),
        b"HTTP/1.1 200 OK\r\n",
        b"HTTP/1.1 407 Authentication required\r\n\r\n",
    ] {
        let (result, _) = proxy_exchange(response, |socket| {
            http_connect(socket, "example.com:443", &http_proxy())
        });
        assert!(result.is_err(), "accepted {response:?}");
    }
}

#[test]
fn controlled_connect_registers_before_proxy_handshake() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let mut proxy = http_proxy();
    proxy.port = listener.local_addr().unwrap().port();
    let (registered_tx, registered_rx) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        TcpConnection::connect_controlled(
            "example.com:443",
            Duration::from_secs(5),
            Some(&proxy),
            |socket| {
                registered_tx.send(socket.clone()).unwrap();
                Ok(())
            },
            || Ok(()),
        )
        .is_err()
    });
    let (mut peer, _) = listener.accept().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    peer.read_exact(&mut [0; 7]).unwrap();
    let registered = registered_rx.recv_timeout(Duration::from_millis(500));
    if let Ok(socket) = &registered {
        socket.shutdown(std::net::Shutdown::Both).unwrap();
    }
    peer.shutdown(std::net::Shutdown::Both).unwrap();
    assert!(worker.join().unwrap());
    assert!(
        registered.is_ok(),
        "proxy negotiation blocked socket registration"
    );
}

#[test]
fn socks5_rejects_unoffered_methods_and_malformed_connect_replies() {
    struct ScriptedProxy(std::io::Cursor<Vec<u8>>);
    impl Read for ScriptedProxy {
        fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
            self.0.read(output)
        }
    }
    impl Write for ScriptedProxy {
        fn write(&mut self, input: &[u8]) -> std::io::Result<usize> {
            Ok(input.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut proxy = http_proxy();
    proxy.kind = ProxyKind::Socks5;
    for reply in [
        vec![5, 3, 5, 0, 0, 1, 127, 0, 0, 1, 0, 0],
        vec![5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 0],
        vec![5, 0, 4, 0, 0, 1, 127, 0, 0, 1, 0, 0],
        vec![5, 0, 5, 0, 1, 1, 127, 0, 0, 1, 0, 0],
        vec![5, 0, 5, 0, 0, 3, 0, 0, 0],
    ] {
        let mut stream = ScriptedProxy(std::io::Cursor::new(reply.clone()));
        assert!(
            socks5_connect(&mut stream, "example.com:443", &proxy).is_err(),
            "accepted {reply:?}"
        );
    }
    let mut stream = ScriptedProxy(std::io::Cursor::new(vec![
        5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 0,
    ]));
    assert!(socks5_connect(&mut stream, "example.com:443", &proxy).is_ok());
}

#[test]
fn socks5_encodes_ip_targets_without_domain_lookup() {
    struct ProxyReply {
        input: std::io::Cursor<Vec<u8>>,
        output: Vec<u8>,
    }
    impl Read for ProxyReply {
        fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
            self.input.read(output)
        }
    }
    impl Write for ProxyReply {
        fn write(&mut self, input: &[u8]) -> std::io::Result<usize> {
            self.output.extend_from_slice(input);
            Ok(input.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut proxy = http_proxy();
    proxy.kind = ProxyKind::Socks5;
    for (target, address) in [
        ("127.0.0.1:443", vec![1, 127, 0, 0, 1]),
        (
            "[::1]:443",
            [vec![4], std::net::Ipv6Addr::LOCALHOST.octets().to_vec()].concat(),
        ),
    ] {
        let mut stream = ProxyReply {
            input: std::io::Cursor::new(vec![5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 0]),
            output: Vec::new(),
        };
        socks5_connect(&mut stream, target, &proxy).unwrap();
        let mut expected = vec![5, 1, 0, 5, 1, 0];
        expected.extend_from_slice(&address);
        expected.extend_from_slice(&443_u16.to_be_bytes());
        assert_eq!(stream.output, expected);
    }
}

#[test]
fn https_proxy_rejects_untrusted_certificate() {
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        socket
            .set_write_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        // The test CA is deliberately absent from platform trust.
        let key =
            PrivateKeyDer::try_from(include_bytes!("../fixtures/untrusted-proxy-key.der").to_vec())
                .unwrap();
        let cert =
            CertificateDer::from(include_bytes!("../fixtures/untrusted-proxy-cert.der").to_vec());
        let config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert], key)
            .unwrap();
        let connection = rustls::ServerConnection::new(std::sync::Arc::new(config)).unwrap();
        let mut stream = rustls::StreamOwned::new(connection, socket);
        let _ = stream.read(&mut [0; 1]);
    });
    let mut proxy = http_proxy();
    proxy.kind = ProxyKind::Https;
    proxy.port = port;
    let result = TcpConnection::connect("example.com:443", Duration::from_secs(2), Some(&proxy));
    server.join().unwrap();
    let error = match result {
        Err(error) => error.to_string(),
        Ok(_) => panic!("untrusted TLS proxy accepted"),
    };
    assert!(
        error.to_ascii_lowercase().contains("certificate"),
        "{error}"
    );
}

#[test]
fn https_connect_verifies_trusted_certificate_and_hostname() {
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};
    for host in ["127.0.0.1", "wrong.example"] {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let cert = CertificateDer::from(
                include_bytes!("../fixtures/untrusted-proxy-cert.der").to_vec(),
            );
            let key = PrivateKeyDer::try_from(
                include_bytes!("../fixtures/untrusted-proxy-key.der").to_vec(),
            )
            .unwrap();
            let config = rustls::ServerConfig::builder()
                .with_no_client_auth()
                .with_single_cert(vec![cert], key)
                .unwrap();
            let connection = rustls::ServerConnection::new(std::sync::Arc::new(config)).unwrap();
            let mut stream = rustls::StreamOwned::new(connection, socket);
            let mut request = Vec::new();
            let mut byte = [0];
            while request.len() < 4096 {
                if stream.read_exact(&mut byte).is_err() {
                    return None;
                }
                request.push(byte[0]);
                if request.ends_with(b"\r\n\r\n") {
                    break;
                }
            }
            stream
                .write_all(b"HTTP/1.1 200 Connection established\r\n\r\nTUNNEL")
                .unwrap();
            stream.flush().unwrap();
            Some(request)
        });
        let mut roots = rustls::RootCertStore::empty();
        roots
            .add(CertificateDer::from(
                include_bytes!("../fixtures/proxy-ca.der").to_vec(),
            ))
            .unwrap();
        let config = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let mut proxy = http_proxy();
        proxy.kind = ProxyKind::Https;
        proxy.host = host.into();
        proxy.port = address.port();
        let mut stream = crate::proxy::tls::connect_with_config(
            TcpStream::connect(address).unwrap(),
            &proxy,
            Duration::from_secs(2),
            config,
        )
        .unwrap();
        let result = crate::proxy::tls::http_connect(&mut stream, "example.com:443", &proxy);
        if host == "127.0.0.1" {
            result.unwrap();
            let mut payload = [0; 6];
            stream.read_exact(&mut payload).unwrap();
            assert_eq!(&payload, b"TUNNEL");
            assert!(
                server
                    .join()
                    .unwrap()
                    .unwrap()
                    .starts_with(b"CONNECT example.com:443 HTTP/1.1\r\n")
            );
        } else {
            assert!(result.is_err(), "certificate with wrong hostname accepted");
            assert!(server.join().unwrap().is_none());
        }
    }
}

#[test]
fn obfuscation_retries_forbidden_nonce_prefixes() {
    let mut calls = 0;
    let stream = make_obfuscated_stream(None, None, |nonce| {
        calls += 1;
        nonce[4] = 1;
        match calls {
            1 => nonce[0] = 0xef,
            2 => nonce[..4].copy_from_slice(b"POST"),
            _ => nonce[0] = 1,
        }
        Ok(())
    })
    .unwrap();
    assert_eq!(calls, 3);
    assert_eq!(stream.header()[0], 1);
}

#[test]
fn obfuscation_does_not_retry_entropy_errors_and_bounds_rejection() {
    let mut calls = 0;
    let result = make_obfuscated_stream(None, None, |_| {
        calls += 1;
        Err(ProxyError::Transport("fixture entropy failure".into()))
    });
    assert!(result.is_err());
    assert_eq!(calls, 1);
    calls = 0;
    let result = make_obfuscated_stream(None, None, |_| {
        calls += 1;
        Ok(())
    });
    assert!(result.is_err());
    assert_eq!(calls, 16);
}

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
