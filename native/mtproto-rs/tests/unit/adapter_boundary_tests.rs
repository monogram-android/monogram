use crate::*;
use mono_proto_client::adapter::runtime as proto_runtime;
use std::sync::{Arc, Mutex};

struct ProgressProbe(Arc<Mutex<Vec<(String, i64, i64)>>>);

impl DownloadProgressListener for ProgressProbe {
    fn on_progress(&self, path: String, downloaded: i64, total: i64) {
        self.0.lock().unwrap().push((path, downloaded, total));
    }
}

#[test]
fn adapter_settings_controls_proxy_and_progress_preserve_boundary_behavior() {
    let id = create_request_control();
    assert_ne!(id, 0);
    let previous = bind_request_control(id);
    assert_eq!(previous, 0);
    cancel_request_control(id);
    release_request_control(id);
    bind_request_control(0);

    set_download_concurrency(3, 7);
    assert_eq!(download_concurrency(), vec![3, 7]);
    set_download_concurrency(-2, 0);
    assert_eq!(download_concurrency(), vec![1, 1]);

    set_download_chunk_kib(128);
    assert_eq!(download_chunk_kib(), 128);
    set_download_chunk_kib(7);
    assert_eq!(download_chunk_kib(), 128);
    set_file_part_kib(512);
    assert_eq!(
        proto_runtime::transfer_policy::current().file_part(),
        512 * 1024
    );
    set_file_part_kib(32);
    assert_eq!(
        proto_runtime::transfer_policy::current().file_part(),
        32 * 1024
    );

    assert!(
        set_proxy(
            "unsupported".into(),
            "localhost".into(),
            1080,
            None,
            None,
            Vec::new(),
        )
        .is_err()
    );

    let events = Arc::new(Mutex::new(Vec::new()));
    set_download_progress_listener(Box::new(ProgressProbe(events.clone())));
    let callback = proto_runtime::transfer_policy::current()
        .progress_callback()
        .expect("listener installed");
    callback("/tmp/file", 12, 34);
    assert_eq!(*events.lock().unwrap(), vec![("/tmp/file".into(), 12, 34)]);
}
