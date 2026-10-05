use crate::*;

pub(crate) fn split_target(target: &str) -> (&str, u16) {
    if let Some(rest) = target.strip_prefix('[') {
        if let Some(end) = rest.find(']') {
            let host = &rest[..end];
            let port = rest[end + 1..]
                .strip_prefix(':')
                .and_then(|value| value.parse().ok())
                .unwrap_or(443);
            return (host, port);
        }
    }
    target
        .rsplit_once(':')
        .and_then(|(host, port)| port.parse().ok().map(|port| (host, port)))
        .unwrap_or((target, 443))
}

pub(crate) fn socks5_connect<S: Read + Write>(
    stream: &mut S,
    target: &str,
    proxy: &ProxyConfig,
    resolved_target: Option<SocketAddr>,
) -> Result<(), ProxyError> {
    let auth = proxy.username.is_some();
    stream.write_all(if auth { &[5, 2, 0, 2] } else { &[5, 1, 0] })?;
    let mut method = [0_u8; 2];
    stream.read_exact(&mut method)?;
    if method[0] != 5 || !matches!(method[1], 0 | 2) || (method[1] == 2 && !auth) {
        return Err(ProxyError::Handshake(
            "SOCKS5 authentication rejected".into(),
        ));
    }
    if method[1] == 2 {
        let user = proxy.username.as_deref().unwrap_or_default().as_bytes();
        let pass = proxy.password.as_deref().unwrap_or_default().as_bytes();
        if user.len() > 255 || pass.len() > 255 {
            return Err(ProxyError::InvalidConfig("SOCKS5 credentials are too long"));
        }
        stream.write_all(&[1, user.len() as u8])?;
        stream.write_all(user)?;
        stream.write_all(&[pass.len() as u8])?;
        stream.write_all(pass)?;
        let mut result = [0_u8; 2];
        stream.read_exact(&mut result)?;
        if result != [1, 0] {
            return Err(ProxyError::Handshake("SOCKS5 credentials rejected".into()));
        }
    }
    let (host, port) = split_target(target);
    let port_bytes = port.to_be_bytes();
    if let Some(address) = resolved_target.or_else(|| {
        host.parse::<IpAddr>()
            .ok()
            .map(|address| SocketAddr::new(address, port))
    }) {
        match address.ip() {
            IpAddr::V4(address) => {
                stream.write_all(&[5, 1, 0, 1])?;
                stream.write_all(&address.octets())?;
            }
            IpAddr::V6(address) => {
                stream.write_all(&[5, 1, 0, 4])?;
                stream.write_all(&address.octets())?;
            }
        }
    } else {
        if host.len() > 255 {
            return Err(ProxyError::InvalidConfig("target host is too long"));
        }
        stream.write_all(&[5, 1, 0, 3, host.len() as u8])?;
        stream.write_all(host.as_bytes())?;
    }
    stream.write_all(&port_bytes)?;
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
