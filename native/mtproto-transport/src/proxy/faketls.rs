use crate::ProxyError;
use std::io::{Read, Write};
use std::net::TcpStream;
use tellers_mtproto_crypto::sha256;

const RECORD_HANDSHAKE: u8 = 0x16;
const RECORD_CHANGE_CIPHER_SPEC: u8 = 0x14;
const RECORD_APPLICATION_DATA: u8 = 0x17;
const MAX_RECORD: usize = 16 * 1024 + 256;
const MAX_RESPONSE: usize = 64 * 1024;

pub(crate) struct FakeTlsStream {
    inner: TcpStream,
    pending: Vec<u8>,
    pending_at: usize,
    sent_change_cipher_spec: bool,
}

impl FakeTlsStream {
    pub(crate) fn new(inner: TcpStream) -> Self {
        Self {
            inner,
            pending: Vec::new(),
            pending_at: 0,
            sent_change_cipher_spec: false,
        }
    }

    pub(crate) fn tcp(&self) -> &TcpStream {
        &self.inner
    }
}

pub(crate) fn handshake(
    stream: &mut TcpStream,
    domain: &str,
    secret: &[u8; 16],
) -> Result<(), ProxyError> {
    let mut hello = client_hello(domain)?;
    let mut random = [0_u8; 32];
    random.copy_from_slice(&hello[11..43]);
    sign_client_hello(&mut hello, secret);
    random.copy_from_slice(&hello[11..43]);
    stream
        .write_all(&hello)
        .map_err(|error| ProxyError::Handshake(error.to_string()))?;
    let response = read_server_response(stream)?;
    if response.len() < 43 || response[0] != RECORD_HANDSHAKE {
        return Err(ProxyError::Handshake("proxy authentication failed".into()));
    }
    let mut server_random = [0_u8; 32];
    server_random.copy_from_slice(&response[11..43]);
    let mut signed = response;
    signed[11..43].fill(0);
    let mut input = Vec::with_capacity(32 + signed.len());
    input.extend_from_slice(&random);
    input.extend_from_slice(&signed);
    if hmac_sha256(secret, &input) != server_random {
        return Err(ProxyError::Handshake("proxy authentication failed".into()));
    }
    Ok(())
}

fn read_server_response(stream: &mut TcpStream) -> Result<Vec<u8>, ProxyError> {
    let mut response = Vec::new();
    for _ in 0..8 {
        let mut header = [0_u8; 5];
        stream
            .read_exact(&mut header)
            .map_err(|error| ProxyError::Handshake(error.to_string()))?;
        let length = u16::from_be_bytes([header[3], header[4]]) as usize;
        if length > MAX_RECORD || response.len() + 5 + length > MAX_RESPONSE {
            return Err(ProxyError::Handshake("proxy authentication failed".into()));
        }
        let mut payload = vec![0_u8; length];
        stream
            .read_exact(&mut payload)
            .map_err(|error| ProxyError::Handshake(error.to_string()))?;
        let kind = header[0];
        response.extend_from_slice(&header);
        response.extend_from_slice(&payload);
        match kind {
            RECORD_HANDSHAKE | RECORD_CHANGE_CIPHER_SPEC => {}
            RECORD_APPLICATION_DATA => return Ok(response),
            _ => return Err(ProxyError::Handshake("proxy authentication failed".into())),
        }
    }
    Err(ProxyError::Handshake("proxy authentication failed".into()))
}

fn client_hello(domain: &str) -> Result<Vec<u8>, ProxyError> {
    if domain.is_empty() || domain.len() > 182 || !domain.is_ascii() {
        return Err(ProxyError::InvalidConfig("invalid proxy secret"));
    }
    #[rustfmt::skip]
    const FIXED_EXTENSIONS: &[u8] = &[
        0x00, 0x17, 0x00, 0x00,
        0xff, 0x01, 0x00, 0x01, 0x00,
        0x00, 0x0a, 0x00, 0x0a, 0x00, 0x08,
        0x00, 0x1d, 0x00, 0x17, 0x00, 0x18, 0x00, 0x19,
        0x00, 0x0b, 0x00, 0x02, 0x01, 0x00,
        0x00, 0x23, 0x00, 0x00,
        0x00, 0x0d, 0x00, 0x14, 0x00, 0x12,
        0x04, 0x03, 0x08, 0x04, 0x04, 0x01,
        0x05, 0x03, 0x08, 0x05, 0x05, 0x01,
        0x08, 0x06, 0x06, 0x01, 0x02, 0x01,
    ];
    #[rustfmt::skip]
    const CIPHER_SUITES: &[u8] = &[
        0x13, 0x01, 0x13, 0x02, 0x13, 0x03,
        0xc0, 0x2b, 0xc0, 0x2f, 0xc0, 0x2c, 0xc0, 0x30,
        0xcc, 0xa9, 0xcc, 0xa8,
        0xc0, 0x13, 0xc0, 0x14,
        0x00, 0x9c, 0x00, 0x9d, 0x00, 0x2f, 0x00, 0x35, 0x00, 0x0a,
    ];
    let host = domain.as_bytes();
    let host_len = host.len() as u16;
    let sni_list_len = 3 + host_len;
    let sni_data_len = 2 + sni_list_len;
    let extensions_len = 4 + usize::from(sni_data_len) + FIXED_EXTENSIONS.len();
    let hello_len = 2 + 32 + 1 + 32 + 2 + CIPHER_SUITES.len() + 2 + 2 + extensions_len;
    let handshake_len = 4 + hello_len;
    let mut session_id = [0_u8; 32];
    tellers_mtproto_crypto::fill_random(&mut session_id)
        .map_err(|error| ProxyError::Handshake(error.to_string()))?;
    let mut record = Vec::with_capacity(5 + handshake_len);
    record.push(RECORD_HANDSHAKE);
    record.extend_from_slice(&[0x03, 0x01]);
    record.extend_from_slice(&(handshake_len as u16).to_be_bytes());
    record.push(0x01);
    record.extend_from_slice(&[
        (hello_len >> 16) as u8,
        (hello_len >> 8) as u8,
        hello_len as u8,
    ]);
    record.extend_from_slice(&[0x03, 0x03]);
    record.extend_from_slice(&[0_u8; 32]);
    record.push(session_id.len() as u8);
    record.extend_from_slice(&session_id);
    record.extend_from_slice(&(CIPHER_SUITES.len() as u16).to_be_bytes());
    record.extend_from_slice(CIPHER_SUITES);
    record.extend_from_slice(&[0x01, 0x00]);
    record.extend_from_slice(&(extensions_len as u16).to_be_bytes());
    record.extend_from_slice(&0_u16.to_be_bytes());
    record.extend_from_slice(&sni_data_len.to_be_bytes());
    record.extend_from_slice(&sni_list_len.to_be_bytes());
    record.push(0x00);
    record.extend_from_slice(&host_len.to_be_bytes());
    record.extend_from_slice(host);
    record.extend_from_slice(FIXED_EXTENSIONS);
    if record.len() != 5 + handshake_len {
        return Err(ProxyError::Handshake("proxy authentication failed".into()));
    }
    Ok(record)
}

fn sign_client_hello(record: &mut [u8], secret: &[u8]) {
    record[11..43].fill(0);
    let digest = hmac_sha256(secret, record);
    record[11..39].copy_from_slice(&digest[..28]);
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs() as u32)
        .unwrap_or(0);
    let stamped = timestamp.to_le_bytes();
    for index in 0..4 {
        record[39 + index] = digest[28 + index] ^ stamped[index];
    }
}

pub(crate) fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut key_block = [0_u8; BLOCK];
    if key.len() > BLOCK {
        key_block[..32].copy_from_slice(&sha256(key));
    } else {
        key_block[..key.len()].copy_from_slice(key);
    }
    let mut inner_key = [0x36_u8; BLOCK];
    let mut outer_key = [0x5c_u8; BLOCK];
    for index in 0..BLOCK {
        inner_key[index] ^= key_block[index];
        outer_key[index] ^= key_block[index];
    }
    let mut inner = Vec::with_capacity(BLOCK + data.len());
    inner.extend_from_slice(&inner_key);
    inner.extend_from_slice(data);
    let inner_hash = sha256(&inner);
    let mut outer = Vec::with_capacity(BLOCK + 32);
    outer.extend_from_slice(&outer_key);
    outer.extend_from_slice(&inner_hash);
    sha256(&outer)
}

impl Read for FakeTlsStream {
    fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
        if output.is_empty() {
            return Ok(0);
        }
        if self.pending_at >= self.pending.len() {
            self.pending.clear();
            self.pending_at = 0;
            self.read_application_data()?;
        }
        let count = (self.pending.len() - self.pending_at).min(output.len());
        output[..count].copy_from_slice(&self.pending[self.pending_at..self.pending_at + count]);
        self.pending_at += count;
        Ok(count)
    }
}

impl Write for FakeTlsStream {
    fn write(&mut self, input: &[u8]) -> std::io::Result<usize> {
        if !self.sent_change_cipher_spec {
            self.inner
                .write_all(&[RECORD_CHANGE_CIPHER_SPEC, 3, 3, 0, 1, 1])?;
            self.sent_change_cipher_spec = true;
        }
        let count = input.len().min(16 * 1024);
        let header = [
            RECORD_APPLICATION_DATA,
            3,
            3,
            (count >> 8) as u8,
            count as u8,
        ];
        self.inner.write_all(&header)?;
        self.inner.write_all(&input[..count])?;
        Ok(count)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

impl FakeTlsStream {
    fn read_application_data(&mut self) -> std::io::Result<()> {
        for _ in 0..8 {
            let mut header = [0_u8; 5];
            self.inner.read_exact(&mut header)?;
            let length = u16::from_be_bytes([header[3], header[4]]) as usize;
            if length > MAX_RECORD {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "TLS record is too large",
                ));
            }
            let mut payload = vec![0_u8; length];
            self.inner.read_exact(&mut payload)?;
            match header[0] {
                RECORD_CHANGE_CIPHER_SPEC => {}
                RECORD_APPLICATION_DATA => {
                    self.pending = payload;
                    return Ok(());
                }
                _ => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "unexpected TLS record",
                    ));
                }
            }
        }
        Err(std::io::Error::new(
            std::io::ErrorKind::UnexpectedEof,
            "TLS application data missing",
        ))
    }
}

#[cfg(test)]
#[path = "../../tests/unit/faketls_tests.rs"]
mod faketls_tests;
