//! `help.configSimple` from `dc_txt_domain_name` (apv3.stel.com) TXT via DoH.
//! https://core.telegram.org/api/config#updating-the-dc-list
//!
//! Concatenate two TXT parts, RSA public-op, AES-CBC, SHA-256 check, then
//! parse `help.configSimple`. Extra IPs are reconnect fallbacks after same-IP
//! :443; `AUTH_KEY_DUPLICATED` still stops further IP hops.

use std::path::Path;
use std::sync::{LazyLock, Mutex};

use aes::Aes256;
use cipher::{Block, BlockCipherDecrypt, KeyInit};
use sha2::{Digest, Sha256};
use tellers_mtproto::latest::api::{Config, DcOption, HelpGetConfigRequest};
use tellers_mtproto_crypto::{parse_rsa_public_key, rsa_encrypt_raw};
use tellers_mtproto_session::{OsRandom, Snapshot};
use tellers_mtproto_transport::PaddedIntermediate;

use crate::MtprotoError;
use crate::peers::vector_boxed_items;
use crate::rpc::LiveTransport;

/// Public key used to unwrap the signed simple-config blob.
const SIMPLE_CONFIG_RSA_PEM: &str = "-----BEGIN RSA PUBLIC KEY-----
MIIBCgKCAQEAyr+18Rex2ohtVy8sroGPBwXD3DOoKCSpjDqYoXgCqB7ioln4eDCF
fOBUlfXUEvM/fnKCpF46VkAftlb4VuPDeQSS/ZxZYEGqHaywlroVnXHIjgqoxiAd
192xRGreuXIaUKmkwlM9JID9WS2jUsTpzQ91L8MEPLJ/4zrBwZua8W5fECwCCh2c
9G5IzzBm+otMS/YKwmR1olzRCyEkyAEjXWqBI9Ftv5eG8m0VkBzOG655WIYdyV0H
fDK/NWcvGqa0w/nriMD6mDjKOryamw0OP9QuYgMN0C9xMW9y8SmP4h92OAWodTYg
Y1hZCxdv6cs5UnW9+PWvS+WIbkh+GaWYxwIDAQAB
-----END RSA PUBLIC KEY-----
";

const HELP_CONFIG_SIMPLE: u32 = 0x5a59_2a6c;
const ACCESS_POINT_RULE: u32 = 0x4679_b65f;
const IP_PORT: u32 = 0xd433_ad73;
const IP_PORT_SECRET: u32 = 0x3798_2646;
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TxtEndpoint {
    pub dc_id: i32,
    pub addr: String,
    pub secret: Option<[u8; 16]>,
}

static TXT_ENDPOINTS: LazyLock<Mutex<Vec<TxtEndpoint>>> = LazyLock::new(|| Mutex::new(Vec::new()));
static CDN_ENDPOINTS: LazyLock<Mutex<Vec<TxtEndpoint>>> = LazyLock::new(|| Mutex::new(Vec::new()));

pub fn endpoints_for(dc_id: i32) -> Vec<TxtEndpoint> {
    TXT_ENDPOINTS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .filter(|e| e.dc_id == dc_id)
        .cloned()
        .collect()
}

pub fn cdn_endpoints_for(dc_id: i32) -> Vec<TxtEndpoint> {
    CDN_ENDPOINTS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .filter(|e| e.dc_id == dc_id)
        .cloned()
        .collect()
}

/// Obfuscation secret from sidecar / `help.getConfig` for this exact DC address.
/// First hop uses this; other IPs stay unused with the home auth key (`AUTH_KEY_DUPLICATED`).
pub fn secret_for(dc_id: i32, addr: &str) -> Option<[u8; 16]> {
    TXT_ENDPOINTS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .find(|e| e.dc_id == dc_id && e.addr == addr)
        .and_then(|e| e.secret)
}

/// Fresh temp-key handshake target when live `help.getConfig` fails.
/// Prefer a different address so we do not retry the dead home socket.
pub(crate) fn pick_backup_endpoint(
    home_dc: i32,
    home_addr: &str,
    endpoints: &[TxtEndpoint],
) -> Option<TxtEndpoint> {
    endpoints
        .iter()
        .find(|e| e.dc_id == home_dc && e.addr != home_addr)
        .or_else(|| endpoints.iter().find(|e| e.addr != home_addr))
        .or_else(|| endpoints.iter().find(|e| e.secret.is_some()))
        .cloned()
}

const CONFIG_TTL_MAX_SECS: i64 = 24 * 3600;

pub fn load_sidecar(session_path: &Path) {
    let path = sidecar_path(session_path);
    let Ok(text) = std::fs::read_to_string(&path) else {
        return;
    };
    let parts: Vec<String> = text
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(str::to_string)
        .collect();
    match decode_txt_parts(&parts) {
        Ok(found) => {
            eprintln!("monogram.api dns txt decoded endpoints={}", found.len());
            *TXT_ENDPOINTS.lock().unwrap_or_else(|e| e.into_inner()) = found;
        }
        Err(err) => eprintln!("monogram.api dns txt decode failed {err}"),
    }
}

/// Apply a still-fresh `dc_txt.config`. Returns true when the backup handshake
/// / live `help.getConfig` can be skipped.
pub fn apply_fresh_cache(session_path: &Path, home_dc: i32) -> bool {
    let now = unix_now();
    let Some(cached) = load_config_cache(session_path) else {
        return false;
    };
    if cached.expires <= now {
        merge_home(home_dc, cached.endpoints);
        return false;
    }
    merge_home(home_dc, cached.endpoints.clone());
    if let Some(tmp_sessions) = cached.tmp_sessions {
        crate::scheduler::set_main_session_allowance(Some(tmp_sessions));
    }
    write_status(
        session_path,
        &format!(
            "getConfig source=cache home_dc={home_dc} options={} ttl={}s",
            cached.endpoints.len(),
            cached.expires - now
        ),
    );
    true
}

/// `help.getConfig` on the home live socket (existing auth_key). Never puts
/// the home key on a TXT backup DC. Temp-key fallback only if live is down.
pub fn refresh_home_from_backup(
    session_path: &Path,
    home_dc: i32,
    api_id: i32,
    snapshot: &mut Snapshot,
) {
    let now = unix_now();
    let cached = load_config_cache(session_path);
    if let Some(cached) = cached.as_ref() {
        if cached.expires > now {
            merge_home(home_dc, cached.endpoints.clone());
            write_status(
                session_path,
                &format!(
                    "getConfig source=cache home_dc={home_dc} options={} ttl={}s",
                    cached.endpoints.len(),
                    cached.expires - now
                ),
            );
            return;
        }
    }
    match fetch_home_options_live(home_dc, api_id, snapshot) {
        Ok((home, server_expires)) => {
            let expires = clamp_expires(server_expires, now);
            save_config_cache(session_path, expires, &home);
            merge_home(home_dc, home.clone());
            write_status(
                session_path,
                &format!(
                    "getConfig source=rpc home_dc={home_dc} options={} ttl={}s",
                    home.len(),
                    expires - now
                ),
            );
        }
        Err(err) => {
            let home_addr = crate::rpc::dc_endpoints(home_dc)
                .first()
                .copied()
                .unwrap_or("");
            let known = TXT_ENDPOINTS
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            if let Some(backup) = pick_backup_endpoint(home_dc, home_addr, &known) {
                match fetch_home_options(home_dc, api_id, &backup) {
                    Ok((home, server_expires)) => {
                        let expires = clamp_expires(server_expires, now);
                        save_config_cache(session_path, expires, &home);
                        merge_home(home_dc, home.clone());
                        write_status(
                            session_path,
                            &format!(
                                "getConfig source=backup home_dc={home_dc} via={} options={} ttl={}s",
                                backup.addr,
                                home.len(),
                                expires - now
                            ),
                        );
                        return;
                    }
                    Err(backup_err) => {
                        write_status(
                            session_path,
                            &format!(
                                "getConfig source=backup failed live={err} backup={backup_err}"
                            ),
                        );
                    }
                }
            }
            if let Some(cached) = cached {
                merge_home(home_dc, cached.endpoints);
                write_status(
                    session_path,
                    &format!("getConfig source=stale failed {err}"),
                );
            } else {
                write_status(session_path, &format!("getConfig source=rpc failed {err}"));
            }
        }
    }
}

/// `help.getConfig` on the home session purely to learn `config.tmp_sessions`.
///
/// Best effort: a failure leaves the client on the safe single-session policy and
/// must never mark the session dead (the probe is not an authorization test).
pub fn probe_main_session_allowance(home_dc: i32, api_id: i32, snapshot: &mut Snapshot) {
    match fetch_home_options_live(home_dc, api_id, snapshot) {
        Ok(_) => eprintln!(
            "monogram.api main sessions allowance={} home_dc={home_dc}",
            crate::scheduler::main_session_allowance()
        ),
        Err(err) => eprintln!("monogram.api main sessions probe failed {err}"),
    }
}

fn merge_home(home_dc: i32, home: Vec<TxtEndpoint>) {
    if home.is_empty() {
        return;
    }
    let mut guard = TXT_ENDPOINTS.lock().unwrap_or_else(|e| e.into_inner());
    guard.retain(|e| e.dc_id != home_dc);
    guard.extend(home);
}

fn fetch_home_options_live(
    home_dc: i32,
    api_id: i32,
    snapshot: &mut Snapshot,
) -> Result<(Vec<TxtEndpoint>, i32), MtprotoError> {
    let cfg = crate::rpc::with_rpc_timeout_secs(15, || {
        crate::api_invoke::invoke_api::<_, Config>(snapshot, api_id, HelpGetConfigRequest {})
    })?;
    config_home_options(home_dc, cfg)
}

fn fetch_home_options(
    home_dc: i32,
    api_id: i32,
    backup: &TxtEndpoint,
) -> Result<(Vec<TxtEndpoint>, i32), MtprotoError> {
    let mut last_err = None;
    for _ in 0..3 {
        match fetch_home_options_once(home_dc, api_id, backup) {
            Ok(ok) => return Ok(ok),
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or_else(|| MtprotoError::Message("backup getConfig failed".into())))
}

fn fetch_home_options_once(
    home_dc: i32,
    api_id: i32,
    backup: &TxtEndpoint,
) -> Result<(Vec<TxtEndpoint>, i32), MtprotoError> {
    let mut conn = crate::tcp::connect_obfuscated_timeout_obf(
        &backup.addr,
        15,
        Some(backup.dc_id as i16),
        backup.secret.as_ref().map(|s| s.as_slice()),
    )
    .map_err(|e| MtprotoError::Message(e.to_string()))?;
    conn.set_io_timeout(15);
    let mut framing = PaddedIntermediate::default();
    let mut snap = Snapshot::new(backup.dc_id, &mut OsRandom)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    crate::auth_key::create_auth_key(&mut conn, &mut framing, &mut snap)?;
    let mut slot = Some(LiveTransport {
        ping_sent: None,
        updates: Vec::new(),
        updates_bytes: 0,
        dc_id: backup.dc_id,
        endpoint: backup.addr.clone(),
        conn,
        framing,
        input: Vec::new(),
        last_io: std::time::Instant::now(),
    });
    let cfg = crate::rpc::with_live_transport(&mut slot, || {
        crate::rpc::with_rpc_timeout_secs(15, || {
            crate::api_invoke::invoke_api::<_, Config>(&mut snap, api_id, HelpGetConfigRequest {})
        })
    });
    drop(slot);
    config_home_options(home_dc, cfg?)
}

fn config_home_options(home_dc: i32, cfg: Config) -> Result<(Vec<TxtEndpoint>, i32), MtprotoError> {
    let Config::Config(cfg) = cfg;
    // `tmp_sessions` is the server's permission for parallel main sessions on a
    // non-media DC; opening more than it allows kills the authorization (406).
    crate::scheduler::set_main_session_allowance(cfg.tmp_sessions);
    let expires = cfg.expires;
    let mut out = Vec::new();
    CDN_ENDPOINTS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clear();
    for opt in vector_boxed_items(cfg.dc_options.as_ref()) {
        let DcOption::DcOption(o) = opt else {
            continue;
        };
        if o.ipv6.is_some() {
            continue;
        }
        let endpoint = TxtEndpoint {
            dc_id: o.id,
            addr: format!("{}:{}", o.ip_address, o.port),
            secret: o.secret.as_deref().and_then(normalize_secret),
        };
        if o.cdn.is_some() {
            CDN_ENDPOINTS
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(endpoint);
            continue;
        }
        if o.id != home_dc || o.media_only.is_some() {
            continue;
        }
        out.push(endpoint);
    }
    Ok((out, expires))
}

pub fn sidecar_path(session_path: &Path) -> std::path::PathBuf {
    session_dir(session_path).join("dc_txt.parts")
}

fn config_cache_path(session_path: &Path) -> std::path::PathBuf {
    session_dir(session_path).join("dc_txt.config")
}

fn status_path(session_path: &Path) -> std::path::PathBuf {
    session_dir(session_path).join("dc_txt.status")
}

fn session_dir(session_path: &Path) -> std::path::PathBuf {
    session_path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .to_path_buf()
}

fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub(crate) fn clamp_expires(server_expires: i32, now: i64) -> i64 {
    // No valid server deadline means no reusable permission.
    let ttl = if server_expires <= 0 {
        0
    } else {
        // Cached session permission must never outlive the server config.
        (i64::from(server_expires) - now).clamp(0, CONFIG_TTL_MAX_SECS)
    };
    now + ttl
}

pub(crate) struct CachedConfig {
    expires: i64,
    tmp_sessions: Option<i32>,
    endpoints: Vec<TxtEndpoint>,
}

fn load_config_cache(session_path: &Path) -> Option<CachedConfig> {
    let text = std::fs::read_to_string(config_cache_path(session_path)).ok()?;
    parse_config_cache(&text)
}

pub(crate) fn parse_config_cache(text: &str) -> Option<CachedConfig> {
    let mut expires = 0_i64;
    let mut tmp_sessions = None;
    let mut endpoints = Vec::new();
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some(rest) = line.strip_prefix("expires ") {
            expires = rest.trim().parse().ok()?;
            continue;
        }
        if let Some(rest) = line.strip_prefix("tmp_sessions ") {
            tmp_sessions = Some(rest.trim().parse().ok()?);
            continue;
        }
        let mut bits = line.split_whitespace();
        let dc_id: i32 = bits.next()?.parse().ok()?;
        let addr = bits.next()?.to_string();
        if addr.is_empty() || !addr.contains(':') {
            return None;
        }
        let secret = bits.next().and_then(parse_secret_hex);
        endpoints.push(TxtEndpoint {
            dc_id,
            addr,
            secret,
        });
    }
    if expires <= 0 || endpoints.is_empty() {
        return None;
    }
    Some(CachedConfig {
        expires,
        tmp_sessions,
        endpoints,
    })
}

fn save_config_cache(session_path: &Path, expires: i64, endpoints: &[TxtEndpoint]) {
    if endpoints.is_empty() {
        return;
    }
    let tmp_sessions = crate::scheduler::main_session_allowance();
    let mut body = format!(
        "# dc_txt.config v2\nexpires {expires}\ntmp_sessions {tmp_sessions}\n"
    );
    for e in endpoints {
        match e.secret {
            Some(secret) => {
                body.push_str(&format!("{} {} {}\n", e.dc_id, e.addr, hex_encode(&secret)));
            }
            None => body.push_str(&format!("{} {}\n", e.dc_id, e.addr)),
        }
    }
    let path = config_cache_path(session_path);
    let tmp = path.with_extension("config.tmp");
    if std::fs::write(&tmp, body).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

fn write_status(session_path: &Path, line: &str) {
    let _ = std::fs::write(status_path(session_path), line);
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0xf) as usize] as char);
    }
    out
}

fn parse_secret_hex(text: &str) -> Option<[u8; 16]> {
    if text.len() != 32 {
        return None;
    }
    let mut out = [0_u8; 16];
    for i in 0..16 {
        out[i] = u8::from_str_radix(&text[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}

/// Official DoH client concatenates the two TXT `data` strings, longer-first
/// only when the first part is not already the longer one.
pub fn concat_txt_parts(parts: &[String]) -> String {
    match parts {
        [] => String::new(),
        [only] => only.clone(),
        [a, b, ..] if a.len() < b.len() => format!("{b}{a}"),
        [a, b, ..] => format!("{a}{b}"),
    }
}

fn base64_filter(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '+' || *c == '/' || *c == '=')
        .collect()
}

fn aes_cbc_decrypt(key: &[u8], iv: &[u8], data: &[u8]) -> Result<Vec<u8>, MtprotoError> {
    if key.len() != 32 || iv.len() != 16 || data.len() % 16 != 0 {
        return Err(MtprotoError::Message("simple config AES length".into()));
    }
    let cipher = Aes256::new_from_slice(key)
        .map_err(|_| MtprotoError::Message("simple config AES key".into()))?;
    let mut prev = [0_u8; 16];
    prev.copy_from_slice(iv);
    let mut out = Vec::with_capacity(data.len());
    for chunk in data.chunks_exact(16) {
        let mut raw = [0_u8; 16];
        raw.copy_from_slice(chunk);
        let mut block = Block::<Aes256>::from(raw);
        cipher.decrypt_block(&mut block);
        for i in 0..16 {
            block[i] ^= prev[i];
        }
        out.extend_from_slice(block.as_slice());
        prev.copy_from_slice(chunk);
    }
    Ok(out)
}

pub fn decode_txt_parts(parts: &[String]) -> Result<Vec<TxtEndpoint>, MtprotoError> {
    let concat = concat_txt_parts(parts);
    let filtered = base64_filter(&concat);
    if filtered.len() != 344 {
        return Err(MtprotoError::Message(format!(
            "simple config b64 len {}",
            filtered.len()
        )));
    }
    let rsa_bytes = base64_decode(&filtered)?;
    if rsa_bytes.len() != 256 {
        return Err(MtprotoError::Message("simple config rsa len".into()));
    }
    let key = parse_rsa_public_key(SIMPLE_CONFIG_RSA_PEM)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let decrypted =
        rsa_encrypt_raw(&key, &rsa_bytes).map_err(|e| MtprotoError::Message(e.to_string()))?;
    if decrypted.len() != 256 {
        return Err(MtprotoError::Message("simple config rsa out".into()));
    }
    let aes_key = &decrypted[..32];
    let iv = &decrypted[16..32];
    let cbc = aes_cbc_decrypt(aes_key, iv, &decrypted[32..])?;
    if cbc.len() != 224 {
        return Err(MtprotoError::Message("simple config cbc len".into()));
    }
    let hash = Sha256::digest(&cbc[..208]);
    if cbc[208..] != hash[..16] {
        return Err(MtprotoError::Message("simple config sha256".into()));
    }
    let len = i32::from_le_bytes(cbc[0..4].try_into().expect("len")) as usize;
    if !(8..=208).contains(&len) {
        return Err(MtprotoError::Message("simple config inner len".into()));
    }
    let ctor = u32::from_le_bytes(cbc[4..8].try_into().expect("ctor"));
    if ctor != HELP_CONFIG_SIMPLE {
        return Err(MtprotoError::Message(format!(
            "simple config ctor {ctor:#x}"
        )));
    }
    parse_config_simple(&cbc[8..len])
}

fn base64_decode(input: &str) -> Result<Vec<u8>, MtprotoError> {
    fn val(c: u8) -> Option<u8> {
        match c {
            b'A'..=b'Z' => Some(c - b'A'),
            b'a'..=b'z' => Some(c - b'a' + 26),
            b'0'..=b'9' => Some(c - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    }
    let bytes: Vec<u8> = input.bytes().filter(|b| *b != b'=').collect();
    if bytes.len() % 4 == 1 {
        return Err(MtprotoError::Message("simple config b64".into()));
    }
    let mut out = Vec::with_capacity(bytes.len() * 3 / 4);
    for chunk in bytes.chunks(4) {
        let a = val(chunk[0]).ok_or_else(|| MtprotoError::Message("simple config b64".into()))?;
        let b = val(chunk[1]).ok_or_else(|| MtprotoError::Message("simple config b64".into()))?;
        out.push((a << 2) | (b >> 4));
        if chunk.len() > 2 {
            let c =
                val(chunk[2]).ok_or_else(|| MtprotoError::Message("simple config b64".into()))?;
            out.push((b << 4) | (c >> 2));
            if chunk.len() > 3 {
                let d = val(chunk[3])
                    .ok_or_else(|| MtprotoError::Message("simple config b64".into()))?;
                out.push((c << 6) | d);
            }
        }
    }
    Ok(out)
}

fn parse_config_simple(body: &[u8]) -> Result<Vec<TxtEndpoint>, MtprotoError> {
    let mut off = 0usize;
    let _date = read_i32(body, &mut off)?;
    let _expires = read_i32(body, &mut off)?;
    // Bare `vector<T>` is count + items (no Vector constructor).
    let count = read_i32(body, &mut off)?;
    let mut out = Vec::new();
    for _ in 0..count.max(0) {
        let ctor = read_u32(body, &mut off)?;
        if ctor != ACCESS_POINT_RULE {
            return Err(MtprotoError::Message("simple config rule ctor".into()));
        }
        let _phone_rules = read_tl_string(body, &mut off)?;
        let dc_id = read_i32(body, &mut off)?;
        let ip_count = read_i32(body, &mut off)?;
        for _ in 0..ip_count.max(0) {
            out.extend(parse_ip_port(body, &mut off, dc_id)?);
        }
    }
    Ok(out)
}

fn parse_ip_port(
    buf: &[u8],
    off: &mut usize,
    dc_id: i32,
) -> Result<Vec<TxtEndpoint>, MtprotoError> {
    let ctor = read_u32(buf, off)?;
    let ipv4 = read_u32(buf, off)?;
    let port = read_i32(buf, off)?;
    let ip = format!(
        "{}.{}.{}.{}",
        (ipv4 >> 24) & 0xff,
        (ipv4 >> 16) & 0xff,
        (ipv4 >> 8) & 0xff,
        ipv4 & 0xff
    );
    let addr = format!("{ip}:{port}");
    match ctor {
        IP_PORT => Ok(vec![TxtEndpoint {
            dc_id,
            addr,
            secret: None,
        }]),
        IP_PORT_SECRET => {
            let raw = read_tl_bytes(buf, off)?;
            let secret = normalize_secret(&raw);
            Ok(vec![TxtEndpoint {
                dc_id,
                addr,
                secret,
            }])
        }
        _ => Err(MtprotoError::Message("simple config ip ctor".into())),
    }
}

fn normalize_secret(raw: &[u8]) -> Option<[u8; 16]> {
    let slice = if raw.len() == 17 {
        &raw[1..]
    } else if raw.len() == 16 {
        raw
    } else {
        return None;
    };
    let mut out = [0_u8; 16];
    out.copy_from_slice(slice);
    Some(out)
}

fn read_i32(buf: &[u8], off: &mut usize) -> Result<i32, MtprotoError> {
    Ok(read_u32(buf, off)? as i32)
}

fn read_u32(buf: &[u8], off: &mut usize) -> Result<u32, MtprotoError> {
    if *off + 4 > buf.len() {
        return Err(MtprotoError::Message("simple config truncated".into()));
    }
    let v = u32::from_le_bytes(buf[*off..*off + 4].try_into().expect("u32"));
    *off += 4;
    Ok(v)
}

fn read_tl_string(buf: &[u8], off: &mut usize) -> Result<String, MtprotoError> {
    let bytes = read_tl_bytes(buf, off)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn read_tl_bytes(buf: &[u8], off: &mut usize) -> Result<Vec<u8>, MtprotoError> {
    if *off >= buf.len() {
        return Err(MtprotoError::Message("simple config truncated".into()));
    }
    let first = buf[*off];
    *off += 1;
    let (len, header) = if first == 0xfe {
        if *off + 3 > buf.len() {
            return Err(MtprotoError::Message("simple config truncated".into()));
        }
        let len = u32::from_le_bytes([buf[*off], buf[*off + 1], buf[*off + 2], 0]) as usize;
        *off += 3;
        (len, 4usize)
    } else {
        (usize::from(first), 1usize)
    };
    if *off + len > buf.len() {
        return Err(MtprotoError::Message("simple config truncated".into()));
    }
    let data = buf[*off..*off + len].to_vec();
    *off += len;
    let total = header + len;
    let pad = (4 - (total % 4)) % 4;
    if *off + pad > buf.len() {
        return Err(MtprotoError::Message("simple config truncated".into()));
    }
    *off += pad;
    Ok(data)
}

#[cfg(test)]
#[path = "dns_txt_tests.rs"]
mod tests;
