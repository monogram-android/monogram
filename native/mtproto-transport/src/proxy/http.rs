use crate::*;

pub(crate) fn http_connect<S: Read + Write>(
    stream: &mut S,
    target: &str,
    proxy: &ProxyConfig,
) -> Result<(), ProxyError> {
    for authenticated in [false, true] {
        if authenticated && proxy.username.is_none() {
            break;
        }
        let mut request =
            format!("CONNECT {target} HTTP/1.1\r\nHost: {target}\r\nConnection: keep-alive\r\n");
        if authenticated {
            let (Some(user), Some(pass)) = (&proxy.username, &proxy.password) else {
                return Err(ProxyError::Handshake(
                    "HTTP proxy credentials are incomplete".into(),
                ));
            };
            request.push_str("Proxy-Authorization: Basic ");
            request.push_str(
                &base64::engine::general_purpose::STANDARD.encode(format!("{user}:{pass}")),
            );
            request.push_str("\r\n");
        }
        request.push_str("\r\n");
        stream.write_all(request.as_bytes())?;

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
                "HTTP proxy response headers are too large".into(),
            ));
        }
        let status = parse_http_response_head(&response)?.status;
        if (200..300).contains(&status) {
            return Ok(());
        }
        if status == 407 && !authenticated && proxy.username.is_some() {
            let headers = std::str::from_utf8(&response)
                .map_err(|_| ProxyError::Handshake("HTTP proxy headers are invalid".into()))?;
            let mut content_length = None;
            let mut basic = false;
            for line in headers
                .split("\r\n")
                .skip(1)
                .filter(|line| !line.is_empty())
            {
                let (name, value) = line
                    .split_once(':')
                    .ok_or_else(|| ProxyError::Handshake("HTTP proxy header is invalid".into()))?;
                let value = value.trim();
                if name.eq_ignore_ascii_case("content-length") {
                    let length = value.parse::<usize>().map_err(|_| {
                        ProxyError::Handshake("HTTP proxy response length is invalid".into())
                    })?;
                    if content_length.replace(length).is_some() || length > MAX_PROXY_HEADERS {
                        return Err(ProxyError::Handshake(
                            "HTTP proxy response length is invalid".into(),
                        ));
                    }
                }
                if name.eq_ignore_ascii_case("transfer-encoding")
                    || (name.eq_ignore_ascii_case("connection")
                        && value.eq_ignore_ascii_case("close"))
                {
                    return Err(ProxyError::Handshake(
                        "HTTP proxy challenge requires a new connection".into(),
                    ));
                }
                if name.eq_ignore_ascii_case("proxy-authenticate") {
                    basic |= value
                        .split_whitespace()
                        .next()
                        .is_some_and(|scheme| scheme.eq_ignore_ascii_case("Basic"));
                }
            }
            if !basic || content_length.is_none() {
                return Err(ProxyError::Handshake(
                    "HTTP proxy authentication challenge is unsupported".into(),
                ));
            }
            let mut body = vec![0_u8; content_length.unwrap_or_default()];
            stream.read_exact(&mut body)?;
            continue;
        }
        return Err(ProxyError::Handshake(format!(
            "HTTP proxy returned status {status}"
        )));
    }
    Err(ProxyError::Handshake(
        "HTTP proxy authentication failed".into(),
    ))
}
