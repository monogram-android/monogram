use super::*;

/// One body: the recorder is process-global, so parallel tests would race.
#[test]
fn records_windows_bounded_samples_and_noops_when_disabled() {
    set_enabled(false);
    span("off").with(|| ());
    count("off");
    let disabled = snapshot_json(true);
    assert!(!disabled.contains("\"op\":\"off\""), "{disabled}");

    set_enabled(true);
    span("unit").with(|| std::thread::sleep(std::time::Duration::from_millis(1)));
    count("fallback");
    count("fallback");
    for _ in 0..MAX_SAMPLES + 10 {
        span("unit").with(|| ());
    }
    let snapshot = snapshot_json(true);
    let parsed: serde_json::Value = serde_json::from_str(&snapshot).expect("json");
    let ops = parsed["ops"].as_array().expect("ops");
    let unit = ops
        .iter()
        .find(|entry| entry["op"] == "unit")
        .unwrap_or_else(|| panic!("unit span missing: {snapshot}"));
    assert_eq!(unit["count"].as_u64(), Some(MAX_SAMPLES as u64 + 11));
    let samples = unit["samples"].as_array().expect("samples");
    assert!(samples.len() <= MAX_SAMPLES, "{snapshot}");
    assert!(unit["max_us"].as_u64().unwrap_or(0) >= 1000, "{snapshot}");
    let fallback = parsed["counters"]
        .as_array()
        .expect("counters")
        .iter()
        .find(|entry| entry[0] == "fallback")
        .unwrap_or_else(|| panic!("counter missing: {snapshot}"));
    assert!(fallback[1].as_u64().unwrap_or(0) >= 2, "{snapshot}");

    let emptied = snapshot_json(true);
    assert!(!emptied.contains("\"op\":\"unit\""), "{emptied}");
    span("kept").with(|| ());
    let kept = snapshot_json(false);
    assert!(kept.contains("\"op\":\"kept\""), "{kept}");
    let again = snapshot_json(true);
    assert!(again.contains("\"op\":\"kept\""), "{again}");
    set_enabled(false);
}
