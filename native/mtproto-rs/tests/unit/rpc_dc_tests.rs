use super::*;

#[test]
fn test_dc_uses_test_ips() {
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            set_use_test_dc(false);
        }
    }
    let _reset = Reset;
    set_use_test_dc(true);
    assert!(dc_endpoints(2)[0].starts_with("149.154.167.40"));
    set_use_test_dc(false);
    assert!(dc_endpoints(2)[0].starts_with("149.154.167.51"));
}

#[test]
fn rotated_endpoints_moves_5222_off_first() {
    let first = rotated_endpoints(2, 0);
    let second = rotated_endpoints(2, 1);
    assert_eq!(first[0], "149.154.167.51:443");
    assert_eq!(second[0], "149.154.167.50:443");
    assert_eq!(first.len(), second.len());
    assert_eq!(first[1], second[0]);
}

#[test]
fn authenticated_retries_stay_on_one_dc2_ip() {
    let addrs = same_ip_endpoints(2);
    assert_eq!(addrs[0], "149.154.167.51:443");
    assert!(addrs.iter().all(|addr| addr.starts_with("149.154.167.51:")));
    assert!(!addrs.iter().any(|addr| addr.contains("149.154.167.50")));
    assert_eq!(rotated_same_ip(2, 1)[0], "149.154.167.51:5222");
    assert_eq!(rotated_same_ip(2, 0)[0], "149.154.167.51:443");
    assert_ne!(rotated_same_ip(2, 1)[0], rotated_same_ip(2, 0)[0]);
}

#[test]
fn extras_stay_on_pinned_dc2_host() {
    let pinned = ["149.154.167.51:443"];
    assert!(!extra_reconnect_same_host(&pinned, "149.154.167.41:443"));
    assert!(!extra_reconnect_same_host(&pinned, "149.154.167.51:443"));
    assert!(!extra_reconnect_same_host(&pinned, "149.154.167.51:5222"));
}
