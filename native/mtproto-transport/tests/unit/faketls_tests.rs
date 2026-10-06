use super::*;

#[test]
fn handshake_accepts_a_signed_server_hello() {
    use std::net::TcpListener;
    use std::thread;
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut header = [0_u8; 5];
        stream.read_exact(&mut header).unwrap();
        let mut body = vec![0_u8; u16::from_be_bytes([header[3], header[4]]) as usize];
        stream.read_exact(&mut body).unwrap();
        let mut response = vec![0x16, 3, 3, 0, 38, 2, 0, 0, 34, 3, 3];
        response.extend_from_slice(&[0_u8; 32]);
        response.extend_from_slice(&[0x14, 3, 3, 0, 1, 1, 0x17, 3, 3, 0, 1, 0]);
        let mut authenticated = body[6..38].to_vec();
        authenticated.extend_from_slice(&response);
        response[11..43].copy_from_slice(&hmac_sha256(&[7; 16], &authenticated));
        stream.write_all(&response).unwrap();
        let mut prefix = [0_u8; 6];
        stream.read_exact(&mut prefix).unwrap();
        assert_eq!(prefix, [0x14, 3, 3, 0, 1, 1]);
    });
    let mut stream = TcpStream::connect(address).unwrap();
    handshake(&mut stream, "example.com", &[7; 16]).unwrap();
    stream.write_all(&[0x14, 3, 3, 0, 1, 1]).unwrap();
    server.join().unwrap();
}

fn hex(value: &str) -> Vec<u8> {
    (0..value.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&value[index..index + 2], 16).unwrap())
        .collect()
}
