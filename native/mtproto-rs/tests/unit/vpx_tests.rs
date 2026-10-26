use super::{VPX_DECODER_THREADS, VPX_MAX_OUTPUT, fitted_output, lookup_arc};
use crate::{HashMap, HashMapExt};
use parking_lot::Mutex;
use std::sync::Arc;

#[test]
fn lookup_releases_the_instance_map_before_decode() {
    let mut values = HashMap::new();
    values.insert(1u64, Arc::new(5u32));
    let map = Mutex::new(values);
    let value = lookup_arc(&map, 1).expect("present");
    assert!(
        map.try_lock().is_some(),
        "map lock must be free after lookup"
    );
    assert_eq!(*value, 5);
}

#[test]
fn decoder_threads_stay_one() {
    assert_eq!(VPX_DECODER_THREADS, 1);
}

#[test]
fn output_stays_inside_one_megabyte() {
    assert_eq!(fitted_output(512, 512), (512, 512));
    assert_eq!(
        fitted_output(2048, 1024),
        (VPX_MAX_OUTPUT, VPX_MAX_OUTPUT / 2)
    );
    let (w, h) = fitted_output(2048, 2048);
    assert!(w <= VPX_MAX_OUTPUT && h <= VPX_MAX_OUTPUT);
    assert!(w as usize * h as usize * 4 <= 1024 * 1024);
}
