use crate::{ProxyConfig, ProxyError};
use std::io::{Read, Write};

const MAX_PROXY_REPLY: usize = 16 * 1024;

pub(crate) fn http_connect<S: Read + Write>(
    stream: &mut S,
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
    while response.len() < MAX_PROXY_REPLY {
        stream.read_exact(&mut byte)?;
        response.push(byte[0]);
        if response.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    if !response.ends_with(b"\r\n\r\n") {
        return Err(ProxyError::Handshake(
            "HTTP proxy response headers are too large".into(),
        ));
    }
    let line = response
        .split(|byte| *byte == b'\n')
        .next()
        .ok_or_else(|| ProxyError::Handshake("HTTP proxy response missing status".into()))?;
    let text = std::str::from_utf8(line)
        .map_err(|_| ProxyError::Handshake("HTTP proxy response is not UTF-8".into()))?;
    let mut fields = text.split_whitespace();
    if !matches!(fields.next(), Some("HTTP/1.0" | "HTTP/1.1")) {
        return Err(ProxyError::Handshake(
            "HTTP proxy version is invalid".into(),
        ));
    }
    let status = fields
        .next()
        .filter(|value| value.len() == 3 && value.bytes().all(|byte| byte.is_ascii_digit()))
        .and_then(|value| value.parse::<u16>().ok())
        .ok_or_else(|| ProxyError::Handshake("HTTP proxy status is invalid".into()))?;
    if !(200..300).contains(&status) {
        return Err(ProxyError::Handshake(format!(
            "HTTP proxy returned status {status}"
        )));
    }
    Ok(())
}
