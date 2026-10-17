use crate::{ProxyConfig, ProxyError};
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, StreamOwned};
use rustls_platform_verifier::ConfigVerifierExt;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;

const MAX_PROXY_HEADERS: usize = 16 * 1024;
pub(crate) type TlsStream = StreamOwned<ClientConnection, TcpStream>;

pub(crate) fn connect(
    stream: TcpStream,
    proxy: &ProxyConfig,
    timeout: std::time::Duration,
) -> Result<TlsStream, ProxyError> {
    let config = ClientConfig::with_platform_verifier()
        .map_err(|error| ProxyError::Tls(format!("TLS verifier setup failed: {error}")))?;
    let server_name = ServerName::try_from(proxy.host.clone())
        .map_err(|_| ProxyError::Tls("HTTPS proxy host is not a valid TLS name".into()))?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    let connection = ClientConnection::new(Arc::new(config), server_name)
        .map_err(|error| ProxyError::Tls(format!("TLS client setup failed: {error}")))?;
    Ok(StreamOwned::new(connection, stream))
}

pub(crate) fn http_connect(
    stream: &mut TlsStream,
    target: &str,
    proxy: &ProxyConfig,
) -> Result<(), ProxyError> {
    let mut request =
        format!("CONNECT {target} HTTP/1.1\r\nHost: {target}\r\nConnection: keep-alive\r\n");
    if let (Some(username), Some(password)) = (&proxy.username, &proxy.password) {
        use base64::Engine;
        request.push_str("Proxy-Authorization: Basic ");
        request.push_str(
            &base64::engine::general_purpose::STANDARD.encode(format!("{username}:{password}")),
        );
        request.push_str("\r\n");
    }
    request.push_str("\r\n");
    stream.write_all(request.as_bytes())?;
    stream.flush()?;
    let mut response = Vec::new();
    let mut byte = [0_u8; 1];
    while response.len() < MAX_PROXY_HEADERS {
        stream.read_exact(&mut byte)?;
        response.push(byte[0]);
        if response.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    if !response.ends_with(b"\r\n\r\n") {
        return Err(ProxyError::Handshake(
            "HTTPS proxy response headers are too large".into(),
        ));
    }
    let line = response
        .split(|byte| *byte == b'\n')
        .next()
        .ok_or_else(|| ProxyError::Handshake("HTTPS proxy status is missing".into()))?;
    let status = std::str::from_utf8(line)
        .ok()
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|status| status.parse::<u16>().ok())
        .ok_or_else(|| ProxyError::Handshake("HTTPS proxy status is invalid".into()))?;
    if !(200..300).contains(&status) {
        return Err(ProxyError::Handshake(format!(
            "HTTPS proxy returned status {status}"
        )));
    }
    Ok(())
}
