use crate::{ProxyConfig, ProxyError};
use std::io::{Read, Write};

pub(crate) fn socks5_connect<S: Read + Write>(
    stream: &mut S,
    target: &str,
    proxy: &ProxyConfig,
) -> Result<(), ProxyError> {
    let authenticated = proxy.username.is_some();
    stream.write_all(if authenticated {
        &[5, 2, 0, 2]
    } else {
        &[5, 1, 0]
    })?;
    let mut response = [0_u8; 2];
    stream.read_exact(&mut response)?;
    if response[0] != 5 || !matches!(response[1], 0 | 2) || (response[1] == 2 && !authenticated) {
        return Err(ProxyError::Handshake(
            "SOCKS5 authentication rejected".into(),
        ));
    }
    if response[1] == 2 {
        let username = proxy.username.as_deref().unwrap_or_default().as_bytes();
        let password = proxy.password.as_deref().unwrap_or_default().as_bytes();
        if username.len() > 255 || password.len() > 255 {
            return Err(ProxyError::InvalidConfig("SOCKS5 credentials are too long"));
        }
        stream.write_all(&[1, username.len() as u8])?;
        stream.write_all(username)?;
        stream.write_all(&[password.len() as u8])?;
        stream.write_all(password)?;
        let mut auth = [0_u8; 2];
        stream.read_exact(&mut auth)?;
        if auth != [1, 0] {
            return Err(ProxyError::Handshake("SOCKS5 credentials rejected".into()));
        }
    }
    let address = target.parse::<std::net::SocketAddr>().ok();
    let target = target
        .rsplit_once(':')
        .ok_or(ProxyError::InvalidConfig("target must contain a port"))?;
    let host = target.0.as_bytes();
    if host.len() > 255 {
        return Err(ProxyError::InvalidConfig("target host is too long"));
    }
    let port: u16 = target
        .1
        .parse()
        .map_err(|_| ProxyError::InvalidConfig("target port is invalid"))?;
    match address.map(|address| address.ip()) {
        Some(std::net::IpAddr::V4(address)) => {
            stream.write_all(&[5, 1, 0, 1])?;
            stream.write_all(&address.octets())?;
        }
        Some(std::net::IpAddr::V6(address)) => {
            stream.write_all(&[5, 1, 0, 4])?;
            stream.write_all(&address.octets())?;
        }
        None => {
            stream.write_all(&[5, 1, 0, 3, host.len() as u8])?;
            stream.write_all(host)?;
        }
    }
    stream.write_all(&port.to_be_bytes())?;
    let mut head = [0_u8; 4];
    stream.read_exact(&mut head)?;
    if head[0] != 5 || head[1] != 0 || head[2] != 0 {
        return Err(ProxyError::Handshake("SOCKS5 CONNECT rejected".into()));
    }
    let length = match head[3] {
        1 => 4,
        4 => 16,
        3 => {
            let mut size = [0_u8; 1];
            stream.read_exact(&mut size)?;
            if size[0] == 0 {
                return Err(ProxyError::Handshake("SOCKS5 address is empty".into()));
            }
            size[0] as usize
        }
        _ => return Err(ProxyError::Handshake("SOCKS5 address type invalid".into())),
    };
    let mut discard = vec![0_u8; length + 2];
    stream.read_exact(&mut discard)?;
    Ok(())
}
