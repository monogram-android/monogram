use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::*;
use crate::media::{DEFAULT_CHUNK, chunk_size, notify_progress, set_chunk_size};
use crate::scheduler::{DEFAULT_MEDIA_LANES, active_media_lanes, set_active_media_lanes};
use crate::transfer_policy::{self, TransferPolicy};
use crate::upload::upload_rpc::{FILE_PART_FAST, FILE_PART_SMALL, file_part, set_file_part_kib};

#[test]
fn flood_deadlines_are_shared_between_threads_of_one_client_only() {
    let first = Arc::new(TransferPolicy::stock());
    let second = Arc::new(TransferPolicy::stock());
    {
        let _guard = transfer_policy::bind(Arc::clone(&first));
        crate::media::note_transfer_flood(
            2,
            crate::media::TransferClass::Download,
            &MtprotoError::Message("RPC 420: FLOOD_PREMIUM_WAIT_120".into()),
        );
    }
    std::thread::spawn(move || {
        let _guard = transfer_policy::bind(first);
        assert!(
            crate::media::check_transfer_flood(2, crate::media::TransferClass::Download).is_err()
        );
        assert!(
            crate::media::check_transfer_flood(4, crate::media::TransferClass::Download).is_ok()
        );
        assert!(crate::media::check_transfer_flood(2, crate::media::TransferClass::Upload).is_ok());
    })
    .join()
    .unwrap();
    let _guard = transfer_policy::bind(second);
    assert!(crate::media::check_transfer_flood(2, crate::media::TransferClass::Download).is_ok());
}

#[test]
fn download_profiles_and_uploads_respect_client_settings() {
    let policy = Arc::new(TransferPolicy::stock());
    policy.own_chunk_size(256 * 1024);
    policy.own_pipeline_parts(2);
    policy.own_file_part_kib(32);
    let _guard = transfer_policy::bind(policy);
    let window = crate::media::configured_download_window(crate::media::DownloadProfile::User);
    assert_eq!(window.chunk, 256 * 1024);
    assert_eq!(window.in_flight, 2);
    let limits = crate::upload::upload_rpc::upload_limits(8 * 1024 * 1024, None);
    assert_eq!(limits.part_size, FILE_PART_SMALL);
    assert_eq!(limits.in_flight, 8);
    let thumb = crate::media::configured_download_window(crate::media::DownloadProfile::Thumb);
    assert_eq!(thumb.in_flight, 1);
}

#[test]
fn ffi_media_dispatch_profiles_match_kotlin_values_and_reset_after_request() {
    for (class, profile) in [
        (3, crate::media::DownloadProfile::Background),
        (5, crate::media::DownloadProfile::Ordinary),
        (6, crate::media::DownloadProfile::Visible),
        (7, crate::media::DownloadProfile::User),
    ] {
        crate::ffi::set_dispatch_class(class);
        assert_eq!(crate::media::request_profile(), Some(profile));
    }
    crate::ffi::set_dispatch_class(0);
    assert_eq!(crate::media::request_profile(), None);
}

#[test]
fn interactive_download_preempts_a_queued_background_download() {
    let policy = Arc::new(TransferPolicy::stock());
    *policy.queue_limits.lock() = (1, 1);
    let held = policy.admit_download(2, false).unwrap();
    let (order_tx, order_rx) = std::sync::mpsc::channel();
    let (release_bg_tx, release_bg_rx) = std::sync::mpsc::channel::<()>();
    let background = policy.clone();
    let bg_order = order_tx.clone();
    let bg = std::thread::spawn(move || {
        crate::scheduler::with_class(crate::scheduler::RequestClass::BackgroundMedia, || {
            let _lease = background.admit_download(2, false).unwrap();
            bg_order.send("background").unwrap();
            let _ = release_bg_rx.recv_timeout(std::time::Duration::from_secs(2));
        });
    });
    std::thread::sleep(std::time::Duration::from_millis(40));
    let (release_fg_tx, release_fg_rx) = std::sync::mpsc::channel::<()>();
    let interactive = policy.clone();
    let fg = std::thread::spawn(move || {
        crate::scheduler::with_class(crate::scheduler::RequestClass::InteractiveMedia, || {
            let _lease = interactive.admit_download(2, false).unwrap();
            order_tx.send("interactive").unwrap();
            let _ = release_fg_rx.recv_timeout(std::time::Duration::from_secs(2));
        });
    });
    std::thread::sleep(std::time::Duration::from_millis(40));
    drop(held);
    assert_eq!(
        order_rx
            .recv_timeout(std::time::Duration::from_secs(1))
            .ok(),
        Some("interactive")
    );
    release_fg_tx.send(()).unwrap();
    assert_eq!(
        order_rx
            .recv_timeout(std::time::Duration::from_secs(1))
            .ok(),
        Some("background")
    );
    release_bg_tx.send(()).unwrap();
    fg.join().unwrap();
    bg.join().unwrap();
}

#[test]
fn server_queue_limits_block_only_the_matching_dc_and_file_queue() {
    let policy = Arc::new(TransferPolicy::stock());
    *policy.queue_limits.lock() = (1, 1);
    let first = policy.admit_download(2, true).unwrap();
    let small = policy.admit_download(2, false).unwrap();
    let other_dc = policy.admit_download(4, true).unwrap();
    let (ready_tx, ready_rx) = std::sync::mpsc::channel();
    let waiting = policy.clone();
    let thread = std::thread::spawn(move || {
        let _lease = waiting.admit_download(2, true).unwrap();
        ready_tx.send(()).unwrap();
    });
    assert!(
        ready_rx
            .recv_timeout(std::time::Duration::from_millis(50))
            .is_err()
    );
    drop(first);
    ready_rx
        .recv_timeout(std::time::Duration::from_secs(1))
        .unwrap();
    thread.join().unwrap();
    drop((small, other_dc));
}

#[test]
fn app_config_updates_the_active_clients_queue_limits() {
    use tellers_mtproto::latest::api::{
        JsonNumberConstructor, JsonObjectConstructor, JsonObjectValue, JsonObjectValueConstructor,
        JsonValue, Vector, VectorConstructor,
    };
    let entries = [
        ("small_queue_active_operations_max", 3.0),
        ("large_queue_active_operations_max", 1.0),
    ]
    .into_iter()
    .map(|(key, value)| {
        Box::new(JsonObjectValue::JsonObjectValue(
            JsonObjectValueConstructor {
                key: key.into(),
                value: Box::new(JsonValue::JsonNumber(JsonNumberConstructor { value })),
            },
        ))
    })
    .collect::<Vec<_>>();
    let config = JsonValue::JsonObject(JsonObjectConstructor {
        value: Box::new(Vector::Vector(VectorConstructor {
            field_0: entries.len() as u32,
            field_1: entries,
        })),
    });
    let policy = Arc::new(TransferPolicy::stock());
    let _guard = transfer_policy::bind(policy.clone());
    crate::client::extras_rpc::apply_transfer_config(&config);
    assert_eq!(*policy.queue_limits.lock(), (3, 1));
}

#[test]
fn stock_transfer_limits_use_the_reviewed_windows() {
    let stock = TransferPolicy::stock();
    assert_eq!(stock.media_lanes(), DEFAULT_MEDIA_LANES);
    assert_eq!(stock.pipeline_parts(), DEFAULT_PIPELINE_PARTS);
    assert_eq!(stock.chunk_size(), DEFAULT_CHUNK);
    assert_eq!(stock.file_part(), FILE_PART_FAST);
    assert_eq!(DEFAULT_MEDIA_LANES, 2);
    assert_eq!(DEFAULT_PIPELINE_PARTS, 4);
    assert_eq!(DEFAULT_CHUNK, 128 * 1024);
    assert_eq!(FILE_PART_SMALL, 32 * 1024);
}

#[test]
fn boosted_policy_applies_full_interactive_windows_and_caps_parts_at_eight() {
    let policy = Arc::new(TransferPolicy::stock());
    policy.own_pipeline_parts(16);
    policy.own_chunk_size(crate::media::FAST_CHUNK);
    let _guard = transfer_policy::bind(policy);
    for profile in [
        crate::media::DownloadProfile::Visible,
        crate::media::DownloadProfile::Playing,
        crate::media::DownloadProfile::User,
    ] {
        let actual = crate::media::configured_download_window(profile);
        assert_eq!(actual.chunk, 512 * 1024);
        assert_eq!(actual.in_flight, 8);
    }
    let ordinary =
        crate::media::configured_download_window(crate::media::DownloadProfile::Ordinary);
    assert_eq!((ordinary.chunk, ordinary.in_flight), (128 * 1024, 4));
    assert_eq!(
        crate::media::configured_download_window(crate::media::DownloadProfile::Thumb).in_flight,
        1
    );
}

#[test]
fn two_clients_keep_independent_transfer_limits() {
    let path_a = std::env::temp_dir().join(format!(
        "monogram-policy-a-{}-{}.json",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    ));
    let path_b = std::env::temp_dir().join(format!(
        "monogram-policy-b-{}-{}.json",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    ));
    let handle_a = create_client(1, "hash".into(), path_a.to_string_lossy().into());
    let handle_b = create_client(1, "hash".into(), path_b.to_string_lossy().into());
    let client_a = get_client(handle_a).expect("client a");
    let client_b = get_client(handle_b).expect("client b");
    let hits_a = Arc::new(AtomicUsize::new(0));
    let hits_b = Arc::new(AtomicUsize::new(0));
    let hits_a_callback = Arc::clone(&hits_a);
    let hits_b_callback = Arc::clone(&hits_b);

    client_a.policy.own_media_lanes(3);
    client_b.policy.own_media_lanes(5);
    client_a.policy.own_pipeline_parts(4);
    client_b.policy.own_pipeline_parts(7);
    client_a.policy.own_chunk_size(512 * 1024);
    client_b.policy.own_chunk_size(256 * 1024);
    client_a.policy.own_file_part_kib(512);
    client_b.policy.own_file_part_kib(32);
    client_a.policy.own_progress(Some(Arc::new(move |_, _, _| {
        hits_a_callback.fetch_add(1, Ordering::Relaxed);
    })));
    client_b.policy.own_progress(Some(Arc::new(move |_, _, _| {
        hits_b_callback.fetch_add(1, Ordering::Relaxed);
    })));

    let previous_chunk = chunk_size();
    let previous_lanes = active_media_lanes();
    let previous_parts = pipeline_parts();
    let previous_file_part = file_part();
    set_chunk_size(DEFAULT_CHUNK);
    set_active_media_lanes(DEFAULT_MEDIA_LANES);
    set_pipeline_parts(DEFAULT_PIPELINE_PARTS);
    set_file_part_kib(32);

    assert_eq!(client_a.policy.media_lanes(), 3);
    assert_eq!(client_b.policy.media_lanes(), 5);
    assert_eq!(client_a.policy.pipeline_parts(), 4);
    assert_eq!(client_b.policy.pipeline_parts(), 7);
    assert_eq!(client_a.policy.chunk_size(), 512 * 1024);
    assert_eq!(client_b.policy.chunk_size(), 256 * 1024);
    assert_eq!(client_a.policy.file_part(), FILE_PART_FAST);
    assert_eq!(client_b.policy.file_part(), FILE_PART_SMALL);

    {
        let _guard = transfer_policy::bind(Arc::clone(&client_a.policy));
        assert_eq!(active_media_lanes(), 3);
        assert_eq!(pipeline_parts(), 4);
        assert_eq!(chunk_size(), 512 * 1024);
        assert_eq!(file_part(), FILE_PART_FAST);
        notify_progress("a", 1, 2);
    }
    {
        let _guard = transfer_policy::bind(Arc::clone(&client_b.policy));
        assert_eq!(active_media_lanes(), 5);
        assert_eq!(pipeline_parts(), 7);
        assert_eq!(chunk_size(), 256 * 1024);
        assert_eq!(file_part(), FILE_PART_SMALL);
        notify_progress("b", 1, 2);
    }
    with_client_mut(handle_a, |_| {
        assert_eq!(chunk_size(), 512 * 1024);
        assert_eq!(pipeline_parts(), 4);
        Ok(())
    })
    .expect("bound client");

    assert_eq!(hits_a.load(Ordering::Relaxed), 1);
    assert_eq!(hits_b.load(Ordering::Relaxed), 1);

    set_chunk_size(previous_chunk);
    set_active_media_lanes(previous_lanes);
    set_pipeline_parts(previous_parts);
    if previous_file_part == FILE_PART_FAST {
        set_file_part_kib(512);
    } else {
        set_file_part_kib(32);
    }
    destroy_client(handle_a);
    destroy_client(handle_b);
    let _ = std::fs::remove_file(path_a);
    let _ = std::fs::remove_file(path_b);
}

#[test]
fn configured_upload_part_limits_follow_account_premium_status() {
    use tellers_mtproto::latest::api::{
        JsonNumberConstructor, JsonObjectConstructor, JsonObjectValue, JsonObjectValueConstructor,
        JsonValue, Vector, VectorConstructor,
    };
    let policy = Arc::new(TransferPolicy::stock());
    let _guard = transfer_policy::bind(policy.clone());
    let config = JsonValue::JsonObject(JsonObjectConstructor {
        value: Box::new(Vector::Vector(VectorConstructor {
            field_0: 2,
            field_1: [
                ("upload_max_fileparts_default", 3500.0),
                ("upload_max_fileparts_premium", 7500.0),
            ]
            .into_iter()
            .map(|(key, value)| {
                Box::new(JsonObjectValue::JsonObjectValue(
                    JsonObjectValueConstructor {
                        key: key.into(),
                        value: Box::new(JsonValue::JsonNumber(JsonNumberConstructor { value })),
                    },
                ))
            })
            .collect(),
        })),
    });
    crate::client::extras_rpc::apply_transfer_config(&config);
    assert_eq!(policy.upload_max_parts(), 3500);
    policy.premium.store(true, Ordering::Relaxed);
    assert_eq!(policy.upload_max_parts(), 7500);
    policy.premium.store(false, Ordering::Relaxed);
    assert_eq!(policy.upload_max_parts(), 3500);
}
