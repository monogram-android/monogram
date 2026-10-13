use super::*;

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
    }).unwrap();
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
    let result = make_obfuscated_stream(None, None, |_| { calls += 1; Ok(()) });
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
