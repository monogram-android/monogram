use super::*;
use tellers_mtproto_crypto::{AesCtr, sha256};

fn fake_tls_server_hello(stream: &mut TcpStream, secret: &[u8; 16]) {
    let mut header = [0; 5];
    stream.read_exact(&mut header).unwrap();
    assert_eq!(header[0], 0x16);
    let mut body = vec![0; u16::from_be_bytes([header[3], header[4]]) as usize];
    stream.read_exact(&mut body).unwrap();
    let mut response = vec![0x16, 3, 3, 0, 38, 2, 0, 0, 34, 3, 3];
    response.extend_from_slice(&[0; 32]);
    response.extend_from_slice(&[0x14, 3, 3, 0, 1, 1, 0x17, 3, 3, 0, 1, 0]);
    let mut authenticated = body[6..38].to_vec();
    authenticated.extend_from_slice(&response);
    response[11..43].copy_from_slice(&crate::proxy::faketls::hmac_sha256(secret, &authenticated));
    stream.write_all(&response).unwrap();
}

fn echo_obfuscated(mut stream: ProxyStream, secret: &[u8; 16]) {
    let mut header = [0; 64];
    stream.read_exact(&mut header).unwrap();
    let mut key_material = header[8..40].to_vec();
    key_material.extend_from_slice(secret);
    let mut client_cipher = AesCtr::new(&sha256(&key_material), &header[40..56]).unwrap();
    let mut decoded = header;
    client_cipher.apply(&mut decoded);
    assert_eq!(&decoded[56..60], &0xdddddddd_u32.to_le_bytes());
    assert_eq!(&decoded[60..62], &4_i16.to_le_bytes());
    let mut request = [0; 32];
    stream.read_exact(&mut request).unwrap();
    client_cipher.apply(&mut request);
    assert_eq!(request, [0x45; 32]);

    let mut reverse = header;
    reverse.reverse();
    let mut key_material = reverse[8..40].to_vec();
    key_material.extend_from_slice(secret);
    let mut server_cipher = AesCtr::new(&sha256(&key_material), &reverse[40..56]).unwrap();
    let mut response = [0x67; 16];
    server_cipher.apply(&mut response);
    stream.write_all(&response).unwrap();
    stream.flush().unwrap();
}

#[test]
fn simple_dd_and_ee_proxies_route_and_exchange_obfuscated_bytes() {
    for prefix in [None, Some(0xdd), Some(0xee)] {
        let key = [7; 16];
        let mut encoded = Vec::new();
        if let Some(prefix) = prefix {
            encoded.push(prefix);
        }
        encoded.extend_from_slice(&key);
        if prefix == Some(0xee) {
            encoded.extend_from_slice(b"proxy.example");
        }
        let (secret, domain) = ProxyConfig::decode_mtproto_secret(&encoded)
            .unwrap()
            .unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let stream = if prefix == Some(0xee) {
                fake_tls_server_hello(&mut socket, &key);
                ProxyStream::FakeTls(crate::proxy::faketls::FakeTlsStream::new(socket))
            } else {
                ProxyStream::Plain(socket)
            };
            echo_obfuscated(stream, &key);
        });
        let proxy = ProxyConfig {
            kind: ProxyKind::Mtproto,
            host: "127.0.0.1".into(),
            port: address.port(),
            username: None,
            password: None,
            secret: Some(secret),
            fake_tls_domain: domain,
        };
        let socket =
            TcpConnection::connect("149.154.167.91:443", Duration::from_secs(2), Some(&proxy))
                .unwrap();
        let mut connection = socket.into_obfuscated(Some(secret), Some(4)).unwrap();
        connection.send(&[0x45; 32]).unwrap();
        let mut reply = [0; 16];
        let mut received = 0;
        while received < reply.len() {
            let count = connection.receive(&mut reply[received..]).unwrap();
            assert!(count > 0);
            received += count;
        }
        assert_eq!(reply, [0x67; 16]);
        server.join().unwrap();
    }
}

#[test]
fn ee_proxy_rejects_a_server_with_the_wrong_secret() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        fake_tls_server_hello(&mut socket, &[8; 16]);
    });
    let proxy = ProxyConfig {
        kind: ProxyKind::Mtproto,
        host: "127.0.0.1".into(),
        port: address.port(),
        username: None,
        password: None,
        secret: Some([7; 16]),
        fake_tls_domain: Some("proxy.example".into()),
    };
    assert!(
        TcpConnection::connect("149.154.167.91:443", Duration::from_secs(2), Some(&proxy)).is_err()
    );
    server.join().unwrap();
}
