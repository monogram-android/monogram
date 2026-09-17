use super::*;

#[test]
fn concat_puts_longer_first_when_needed() {
    assert_eq!(concat_txt_parts(&["ab".into(), "cdef".into()]), "cdefab");
    assert_eq!(concat_txt_parts(&["abcd".into(), "ef".into()]), "abcdef");
    assert_eq!(concat_txt_parts(&["aa".into(), "bb".into()]), "aabb");
}

#[test]
fn parse_config_simple_ip_port() {
    let mut body = Vec::new();
    body.extend_from_slice(&1i32.to_le_bytes());
    body.extend_from_slice(&2i32.to_le_bytes());
    body.extend_from_slice(&1i32.to_le_bytes());
    body.extend_from_slice(&ACCESS_POINT_RULE.to_le_bytes());
    body.extend_from_slice(&[0, 0, 0, 0]);
    body.extend_from_slice(&2i32.to_le_bytes());
    body.extend_from_slice(&1i32.to_le_bytes());
    body.extend_from_slice(&IP_PORT.to_le_bytes());
    let ip: u32 = (149 << 24) | (154 << 16) | (167 << 8) | 51;
    body.extend_from_slice(&ip.to_le_bytes());
    body.extend_from_slice(&443i32.to_le_bytes());
    let got = parse_config_simple(&body).expect("parse");
    assert_eq!(got.len(), 1);
    assert_eq!(got[0].dc_id, 2);
    assert_eq!(got[0].addr, "149.154.167.51:443");
    assert!(got[0].secret.is_none());
}

#[test]
fn decode_emulator_sidecar_if_present() {
    let path = std::env::var("TEMP")
        .ok()
        .map(|t| std::path::PathBuf::from(t).join("dc_txt.parts"));
    let Some(path) = path.filter(|p| p.is_file()) else {
        return;
    };
    let text = std::fs::read_to_string(&path).expect("sidecar");
    let parts: Vec<String> = text
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(str::to_string)
        .collect();
    match decode_txt_parts(&parts) {
        Ok(eps) => {
            let summary: Vec<String> = eps
                .iter()
                .map(|e| {
                    format!(
                        "dc={} addr={} secret={}",
                        e.dc_id,
                        e.addr,
                        e.secret.is_some()
                    )
                })
                .collect();
            eprintln!("decoded {}", summary.join("; "));
            assert!(!eps.is_empty());
        }
        Err(e) => panic!("{e:?}"),
    }
}

#[test]
fn normalize_dd_prefixed_secret() {
    let mut raw = vec![0xdd];
    raw.extend_from_slice(&[7u8; 16]);
    assert_eq!(normalize_secret(&raw), Some([7u8; 16]));
    assert_eq!(normalize_secret(&[7u8; 16]), Some([7u8; 16]));
    assert_eq!(normalize_secret(&[1, 2, 3]), None);
}

#[test]
fn config_cache_roundtrip_and_freshness() {
    let secret = [7u8; 16];
    let text = format!(
        "# dc_txt.config v1\nexpires 2000\n2 149.154.167.51:443 {}\n",
        hex_encode(&secret)
    );
    let cached = parse_config_cache(&text).expect("cache");
    assert_eq!(cached.expires, 2000);
    assert_eq!(cached.endpoints.len(), 1);
    assert_eq!(cached.endpoints[0].addr, "149.154.167.51:443");
    assert_eq!(cached.endpoints[0].secret, Some(secret));
    assert!(cached.expires > 1000);
    assert!(parse_config_cache("expires 1\n").is_none());
}

#[test]
fn secret_for_matches_exact_dc_addr() {
    let previous = TXT_ENDPOINTS
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    *TXT_ENDPOINTS.lock().unwrap_or_else(|e| e.into_inner()) = vec![TxtEndpoint {
        dc_id: 2,
        addr: "149.154.167.51:443".into(),
        secret: Some([9u8; 16]),
    }];
    let found = secret_for(2, "149.154.167.51:443");
    let miss_port = secret_for(2, "149.154.167.51:5222");
    let miss_dc = secret_for(1, "149.154.167.51:443");
    *TXT_ENDPOINTS.lock().unwrap_or_else(|e| e.into_inner()) = previous;
    assert_eq!(found, Some([9u8; 16]));
    assert_eq!(miss_port, None);
    assert_eq!(miss_dc, None);
}

#[test]
fn pick_backup_prefers_other_addr_then_secret() {
    let home = "149.154.167.51:443";
    let same = TxtEndpoint {
        dc_id: 2,
        addr: home.into(),
        secret: Some([1u8; 16]),
    };
    let other = TxtEndpoint {
        dc_id: 2,
        addr: "149.154.167.91:443".into(),
        secret: None,
    };
    let foreign = TxtEndpoint {
        dc_id: 4,
        addr: "149.154.167.91:443".into(),
        secret: None,
    };
    assert_eq!(
        pick_backup_endpoint(2, home, &[same.clone(), other.clone()]).map(|e| e.addr),
        Some(other.addr.clone())
    );
    assert_eq!(
        pick_backup_endpoint(2, home, &[foreign.clone()]).map(|e| e.addr),
        Some(foreign.addr)
    );
    assert_eq!(
        pick_backup_endpoint(2, home, &[same.clone()]).and_then(|e| e.secret),
        Some([1u8; 16])
    );
    assert!(pick_backup_endpoint(2, home, &[]).is_none());
}

#[test]
fn clamp_expires_uses_server_then_bounds() {
    let now = 1_700_000_000;
    assert_eq!(clamp_expires(0, now), now + CONFIG_TTL_DEFAULT_SECS);
    assert_eq!(
        clamp_expires(now as i32 - 10, now),
        now + CONFIG_TTL_MIN_SECS
    );
    assert_eq!(
        clamp_expires(now as i32 + 100_000, now),
        now + CONFIG_TTL_MAX_SECS
    );
    assert_eq!(clamp_expires(now as i32 + 3_600, now), now + 3_600);
}
