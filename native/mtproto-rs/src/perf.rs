//! Opt-in netcode timing spans: op names and durations only, never payloads.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use std::time::Instant;

use parking_lot::Mutex;
use serde::Serialize;

/// Ring size per op.
const MAX_SAMPLES: usize = 64;

static ENABLED: AtomicBool = AtomicBool::new(false);

fn epoch() -> &'static Instant {
    static EPOCH: OnceLock<Instant> = OnceLock::new();
    EPOCH.get_or_init(Instant::now)
}

#[derive(Serialize, Clone, Copy)]
pub struct PerfSample {
    pub at_us: u64,
    pub us: u64,
}

#[derive(Serialize, Clone)]
pub struct PerfOp {
    pub op: String,
    pub count: u64,
    pub sum_us: u64,
    pub max_us: u64,
    pub samples: Vec<PerfSample>,
}

static OPS: Mutex<Vec<PerfOp>> = Mutex::new(Vec::new());
static COUNTERS: Mutex<Vec<(String, u64)>> = Mutex::new(Vec::new());

pub fn set_enabled(enabled: bool) {
    ENABLED.store(enabled, Ordering::Relaxed);
}

pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

pub fn span(op: &'static str) -> Span {
    if !enabled() {
        return Span {
            op: None,
            start: None,
        };
    }
    Span {
        op: Some(op.to_owned()),
        start: Some(Instant::now()),
    }
}

pub fn span_dyn(op: impl FnOnce() -> String) -> Span {
    if !enabled() {
        return Span {
            op: None,
            start: None,
        };
    }
    Span {
        op: Some(op()),
        start: Some(Instant::now()),
    }
}

pub fn count(op: &'static str) {
    if !enabled() {
        return;
    }
    bump(op);
}

fn bump(op: &str) {
    let mut counters = COUNTERS.lock();
    match counters.iter_mut().find(|(name, _)| name == op) {
        Some((_, value)) => *value += 1,
        None => counters.push((op.to_owned(), 1)),
    }
}

pub fn record(op: &str, micros: u64) {
    let at_us = epoch().elapsed().as_micros() as u64;
    let mut ops = OPS.lock();
    let entry = match ops.iter_mut().find(|entry| entry.op == op) {
        Some(entry) => entry,
        None => {
            ops.push(PerfOp {
                op: op.to_owned(),
                count: 0,
                sum_us: 0,
                max_us: 0,
                samples: Vec::new(),
            });
            ops.last_mut().expect("just pushed")
        }
    };
    entry.count += 1;
    entry.sum_us += micros;
    if micros > entry.max_us {
        entry.max_us = micros;
    }
    if entry.samples.len() == MAX_SAMPLES {
        entry.samples.remove(0);
    }
    entry.samples.push(PerfSample { at_us, us: micros });
}

#[derive(Serialize)]
struct Snapshot<'a> {
    at_us: u64,
    ops: &'a [PerfOp],
    counters: &'a [(String, u64)],
}

/// `reset` clears the table so one dump describes one measurement window.
pub fn snapshot_json(reset: bool) -> String {
    let now_us = epoch().elapsed().as_micros() as u64;
    let ops = std::mem::take(&mut *OPS.lock());
    let counters = std::mem::take(&mut *COUNTERS.lock());
    let snapshot = Snapshot {
        at_us: now_us,
        ops: &ops,
        counters: &counters,
    };
    let json = serde_json::to_string(&snapshot).unwrap_or_else(|_| "{}".into());
    if !reset {
        let mut guard = OPS.lock();
        for op in ops {
            match guard.iter_mut().find(|entry| entry.op == op.op) {
                Some(entry) => {
                    entry.count += op.count;
                    entry.sum_us += op.sum_us;
                    if op.max_us > entry.max_us {
                        entry.max_us = op.max_us;
                    }
                    for sample in op.samples {
                        if entry.samples.len() == MAX_SAMPLES {
                            entry.samples.remove(0);
                        }
                        entry.samples.push(sample);
                    }
                }
                None => guard.push(op),
            }
        }
        drop(guard);
        let mut counter_guard = COUNTERS.lock();
        for (name, value) in counters {
            match counter_guard.iter_mut().find(|(name_, _)| *name_ == name) {
                Some((_, existing)) => *existing += value,
                None => counter_guard.push((name, value)),
            }
        }
    }
    json
}

#[cfg(test)]
mod tests {
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
}

pub struct Span {
    op: Option<String>,
    start: Option<Instant>,
}

impl Span {
    pub fn with<T>(self, body: impl FnOnce() -> T) -> T {
        let value = body();
        drop(self);
        value
    }
}

impl Drop for Span {
    fn drop(&mut self) {
        let (Some(op), Some(start)) = (self.op.as_deref(), self.start) else {
            return;
        };
        record(op, start.elapsed().as_micros() as u64);
    }
}
