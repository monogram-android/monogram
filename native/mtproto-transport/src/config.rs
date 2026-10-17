use super::*;

pub(crate) const MAX_PROXY_HEADERS: usize = 16 * 1024;
pub(crate) const MAX_PROXY_CREDENTIAL: usize = 255;
pub(crate) const MAX_HTTP_BODY: usize = 16 * 1024 * 1024;
pub(crate) const MAX_HTTP_QUEUED_REQUESTS: usize = 64;
pub(crate) const MAX_HTTP_QUEUED_BYTES: usize = MAX_HTTP_BODY * 2;
pub(crate) const DNS_CACHE_TTL: Duration = Duration::from_secs(60);
pub(crate) const DNS_MIN_TTL: Duration = Duration::from_secs(10);
pub(crate) const DNS_MAX_TTL: Duration = Duration::from_secs(300);
pub(crate) const MAX_FAKE_TLS_DOMAIN_LENGTH: usize = 182;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProxyKind {
    Socks5,
    Http,
    Https,
    Mtproto,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransportMode {
    PaddedIntermediate,
    Http,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TransportConfig {
    pub proxy: Option<ProxyConfig>,
    pub mode: TransportMode,
}

impl Default for TransportConfig {
    fn default() -> Self {
        Self {
            proxy: None,
            mode: TransportMode::PaddedIntermediate,
        }
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct ProxyConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password: Option<String>,
    pub secret: Option<ProxySecret>,
}

#[derive(Clone, Eq, PartialEq)]
pub struct ProxySecret {
    pub key: [u8; 16],
    pub random_padding: bool,
    pub fake_tls_domain: Option<String>,
}

impl std::fmt::Debug for ProxyConfig {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ProxyConfig")
            .field("kind", &self.kind)
            .field("credentials", &"[REDACTED]")
            .finish_non_exhaustive()
    }
}

impl std::fmt::Debug for ProxySecret {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("ProxySecret([REDACTED])")
    }
}

impl ProxySecret {
    pub fn parse(input: &str) -> Result<Self, ProxyError> {
        if input.len() > (17 + MAX_FAKE_TLS_DOMAIN_LENGTH) * 2 || input.trim() != input {
            return Err(ProxyError::InvalidConfig("invalid proxy secret length"));
        }
        let bytes = if input.len() % 2 == 0 && input.bytes().all(|b| b.is_ascii_hexdigit()) {
            (0..input.len())
                .step_by(2)
                .map(|i| {
                    u8::from_str_radix(&input[i..i + 2], 16)
                        .map_err(|_| ProxyError::InvalidConfig("invalid proxy secret"))
                })
                .collect::<Result<Vec<_>, _>>()?
        } else {
            let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
                .decode(input)
                .map_err(|_| ProxyError::InvalidConfig("invalid proxy secret"))?;
            if base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&decoded) != input {
                return Err(ProxyError::InvalidConfig("invalid proxy secret"));
            }
            decoded
        };
        Self::from_binary(&bytes)
    }

    pub fn from_binary(bytes: &[u8]) -> Result<Self, ProxyError> {
        if bytes.len() > 17 + MAX_FAKE_TLS_DOMAIN_LENGTH {
            return Err(ProxyError::InvalidConfig("invalid proxy secret length"));
        }
        let (prefix, start) = match (bytes.len(), bytes.first().copied()) {
            (17.., Some(0xdd) | Some(0xee)) => (Some(bytes[0]), 1),
            _ => (None, 0),
        };
        let valid_length = match prefix {
            None => bytes.len() == 16,
            Some(0xdd) => bytes.len() == 17,
            Some(0xee) => bytes.len() > 17,
            _ => false,
        };
        if !valid_length {
            return Err(ProxyError::InvalidConfig("invalid proxy secret length"));
        }
        let mut key = [0_u8; 16];
        key.copy_from_slice(&bytes[start..start + 16]);
        let fake_tls_domain = if prefix == Some(0xee) {
            let domain = std::str::from_utf8(&bytes[start + 16..])
                .map_err(|_| ProxyError::InvalidConfig("Fake-TLS domain is invalid"))?;
            if !valid_fake_tls_domain(domain) {
                return Err(ProxyError::InvalidConfig("Fake-TLS domain is invalid"));
            }
            Some(domain.to_owned())
        } else {
            None
        };
        Ok(Self {
            key,
            random_padding: prefix.is_some(),
            fake_tls_domain,
        })
    }
}

impl ProxyConfig {
    pub fn validate(&self) -> Result<(), ProxyError> {
        if self.host.is_empty() || self.host.len() > 255 || self.host.bytes().any(|b| b <= 0x20) {
            return Err(ProxyError::InvalidConfig("invalid proxy host"));
        }
        if self.port == 0 {
            return Err(ProxyError::InvalidConfig("invalid proxy port"));
        }
        match self.kind {
            ProxyKind::Mtproto
                if self.secret.is_none() || self.username.is_some() || self.password.is_some() =>
            {
                Err(ProxyError::InvalidConfig(
                    "invalid MTProto proxy credentials",
                ))
            }
            ProxyKind::Socks5 | ProxyKind::Http | ProxyKind::Https
                if self.username.is_some() != self.password.is_some() || self.secret.is_some() =>
            {
                Err(ProxyError::InvalidConfig("invalid proxy credentials"))
            }
            ProxyKind::Socks5 | ProxyKind::Http | ProxyKind::Https
                if self
                    .username
                    .as_deref()
                    .is_some_and(|value| value.len() > MAX_PROXY_CREDENTIAL)
                    || self
                        .password
                        .as_deref()
                        .is_some_and(|value| value.len() > MAX_PROXY_CREDENTIAL) =>
            {
                Err(ProxyError::InvalidConfig("proxy credentials are too long"))
            }
            ProxyKind::Socks5 | ProxyKind::Http | ProxyKind::Https
                if self
                    .username
                    .as_deref()
                    .is_some_and(contains_control_character)
                    || self
                        .password
                        .as_deref()
                        .is_some_and(contains_control_character) =>
            {
                Err(ProxyError::InvalidConfig(
                    "proxy credentials contain control characters",
                ))
            }
            _ => Ok(()),
        }
    }
}

fn contains_control_character(value: &str) -> bool {
    value.bytes().any(|byte| byte < 0x20 || byte == 0x7f)
}

fn valid_fake_tls_domain(domain: &str) -> bool {
    if domain.is_empty() || domain.len() > MAX_FAKE_TLS_DOMAIN_LENGTH || !domain.is_ascii() {
        return false;
    }
    domain.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && label.as_bytes()[0].is_ascii_alphanumeric()
            && label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
            && label
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    })
}
