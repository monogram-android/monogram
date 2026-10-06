use super::*;

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
