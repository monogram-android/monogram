//! https://core.telegram.org/api/datacenter
//! https://core.telegram.org/api/obtaining_api_id#test-accounts

use std::sync::atomic::{AtomicBool, Ordering};

use tellers_mtproto_crypto::fill_random;

static USE_TEST_DC: AtomicBool = AtomicBool::new(false);

/// Test DCs (99966XYYYY). Separate IPs from production; same dc_id numbers.
/// https://core.telegram.org/api/obtaining_api_id#test-accounts
pub fn set_use_test_dc(test: bool) {
    USE_TEST_DC.store(test, Ordering::SeqCst);
}

pub fn use_test_dc() -> bool {
    USE_TEST_DC.load(Ordering::SeqCst)
}

/// Direct-DC endpoints. 443 first: 5222/80 often accept TCP then stay silent (DPI).
pub fn dc_endpoints(dc_id: i32) -> &'static [&'static str] {
    if use_test_dc() {
        return test_dc_endpoints(dc_id);
    }
    match dc_id {
        1 => &[
            "149.154.175.53:443",
            "149.154.175.53:5222",
            "149.154.175.53:80",
        ],
        2 => &[
            "149.154.167.51:443",
            "149.154.167.50:443",
            "149.154.167.51:5222",
            "149.154.167.50:5222",
            "149.154.167.51:80",
            "149.154.167.50:80",
        ],
        3 => &[
            "149.154.175.100:443",
            "149.154.175.100:5222",
            "149.154.175.100:80",
        ],
        4 => &[
            "149.154.167.91:443",
            "149.154.167.91:5222",
            "149.154.167.91:80",
        ],
        5 => &[
            "91.108.56.165:443",
            "91.108.56.165:5222",
            "91.108.56.165:80",
        ],
        _ => dc_endpoints(2),
    }
}

pub(crate) fn test_dc_endpoints(dc_id: i32) -> &'static [&'static str] {
    match dc_id {
        1 => &["149.154.175.10:443", "149.154.175.10:80"],
        2 => &["149.154.167.40:443", "149.154.167.40:80"],
        3 => &["149.154.175.117:443", "149.154.175.117:80"],
        _ => test_dc_endpoints(2),
    }
}

pub(crate) fn endpoint_host(addr: &str) -> &str {
    addr.rsplit_once(':').map(|(host, _)| host).unwrap_or(addr)
}

/// Ports on the first IP only, for stable endpoint retry ordering.
pub fn same_ip_endpoints(dc_id: i32) -> Vec<&'static str> {
    let all = dc_endpoints(dc_id);
    let Some(first) = all.first() else {
        return Vec::new();
    };
    let host = endpoint_host(first);
    all.iter()
        .copied()
        .filter(|addr| endpoint_host(addr) == host)
        .collect()
}

pub(crate) fn reconnect_backoff(attempt: usize) -> std::time::Duration {
    let mut random = [0_u8; 8];
    let _ = fill_random(&mut random);
    reconnect_delay(attempt, u64::from_le_bytes(random))
}

pub(crate) fn reconnect_delay(attempt: usize, random: u64) -> std::time::Duration {
    if attempt == 0 {
        return std::time::Duration::ZERO;
    }
    let shift = (attempt - 1).min(3) as u32;
    let ceiling = (100_u64 << shift).min(800);
    std::time::Duration::from_millis(ceiling / 2 + random % (ceiling / 2 + 1))
}

/// Rotate so a silent 5222 (TCP up, no MTProto) does not starve 80/443 retries.
pub fn rotated_endpoints(dc_id: i32, skip: usize) -> Vec<&'static str> {
    let all = dc_endpoints(dc_id);
    let n = all.len();
    if n == 0 {
        return Vec::new();
    }
    (0..n).map(|i| all[(i + skip) % n]).collect()
}

pub(crate) fn rotated_same_ip(dc_id: i32, skip: usize) -> Vec<&'static str> {
    let all = same_ip_endpoints(dc_id);
    let n = all.len();
    if n == 0 {
        return Vec::new();
    }
    (0..n).map(|i| all[(i + skip) % n]).collect()
}

pub(crate) fn extra_reconnect_same_host(pinned: &[&str], extra_addr: &str) -> bool {
    let home_host = pinned
        .first()
        .and_then(|addr| addr.rsplit_once(':').map(|(host, _)| host));
    let host = extra_addr.rsplit_once(':').map(|(h, _)| h);
    extra_addr.ends_with(":443") && host == home_host && !pinned.iter().any(|p| *p == extra_addr)
}
