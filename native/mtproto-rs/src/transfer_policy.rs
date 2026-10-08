//! Per-client transfer limits.
//! https://core.telegram.org/api/files
//! https://core.telegram.org/api/optimisation

use std::cell::RefCell;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};

use crate::HashMapExt;
use parking_lot::RwLock;

use crate::client::{DEFAULT_PIPELINE_PARTS, MAX_PIPELINE_PARTS};
use crate::media::DEFAULT_CHUNK;
use crate::scheduler::{DEFAULT_MEDIA_LANES, MAX_MEDIA_LANES};
use crate::upload::upload_rpc::{FILE_PART_FAST, FILE_PART_SMALL};

pub type ProgressCallback = Arc<dyn Fn(&str, i64, i64) + Send + Sync + 'static>;

pub(crate) struct TransferPolicy {
    media_lanes: AtomicUsize,
    pub(crate) premium: AtomicBool,
    pub(crate) upload_parts: parking_lot::Mutex<(i32, i32)>,
    pipeline_parts: AtomicUsize,
    chunk_size: AtomicI32,
    file_part: AtomicUsize,
    progress: RwLock<Option<ProgressCallback>>,
    pub(crate) floods: parking_lot::Mutex<crate::media::FloodScope>,
    pub(crate) queue_limits: parking_lot::Mutex<(usize, usize)>,
    downloads: parking_lot::Mutex<ActiveDownloads>,
    download_wake: parking_lot::Condvar,
    pub(crate) upload_rtt:
        parking_lot::Mutex<Option<(i32, std::time::Instant, std::time::Duration)>>,
    pub(crate) config_due: std::sync::atomic::AtomicU64,
    media_lanes_owned: AtomicBool,
    pipeline_owned: AtomicBool,
    chunk_owned: AtomicBool,
    file_part_owned: AtomicBool,
    progress_owned: AtomicBool,
}

impl TransferPolicy {
    pub(crate) fn stock() -> Self {
        Self {
            media_lanes: AtomicUsize::new(DEFAULT_MEDIA_LANES),
            premium: AtomicBool::new(false),
            upload_parts: parking_lot::Mutex::new((4_000, 8_000)),
            pipeline_parts: AtomicUsize::new(DEFAULT_PIPELINE_PARTS),
            chunk_size: AtomicI32::new(DEFAULT_CHUNK),
            file_part: AtomicUsize::new(FILE_PART_FAST),
            progress: RwLock::new(None),
            floods: parking_lot::Mutex::new(crate::media::FloodScope::new()),
            queue_limits: parking_lot::Mutex::new((5, 2)),
            downloads: parking_lot::Mutex::new(ActiveDownloads::default()),
            download_wake: parking_lot::Condvar::new(),
            upload_rtt: parking_lot::Mutex::new(None),
            config_due: std::sync::atomic::AtomicU64::new(0),
            media_lanes_owned: AtomicBool::new(false),
            pipeline_owned: AtomicBool::new(false),
            chunk_owned: AtomicBool::new(false),
            file_part_owned: AtomicBool::new(false),
            progress_owned: AtomicBool::new(false),
        }
    }

    pub(crate) fn upload_max_parts(&self) -> i32 {
        let limits = *self.upload_parts.lock();
        if self.premium.load(Ordering::Relaxed) {
            limits.1
        } else {
            limits.0
        }
    }

    pub(crate) fn media_lanes(&self) -> usize {
        self.media_lanes
            .load(Ordering::Relaxed)
            .clamp(1, MAX_MEDIA_LANES)
    }

    pub(crate) fn admit_download(
        self: &Arc<Self>,
        dc: i32,
        large: bool,
    ) -> Result<DownloadAdmission, crate::MtprotoError> {
        let priority = crate::scheduler::current_class().priority();
        let mut active = self.downloads.lock();
        let ticket = active.next_ticket;
        active.next_ticket = active.next_ticket.wrapping_add(1);
        active.waiters.push(DownloadWaiter {
            ticket,
            priority,
            dc,
            large,
        });
        loop {
            if let Err(error) = crate::request_control::check() {
                active.waiters.retain(|waiter| waiter.ticket != ticket);
                self.download_wake.notify_all();
                return Err(crate::MtprotoError::Message(error.to_string()));
            }
            let limits = *self.queue_limits.lock();
            let limit = if large { limits.1.min(2) } else { limits.0 };
            let count = active.counts.get(&(dc, large)).copied().unwrap_or(0);
            let best = active
                .waiters
                .iter()
                .filter(|waiter| waiter.dc == dc && waiter.large == large)
                .min_by_key(|waiter| (waiter.priority, waiter.ticket))
                .map(|waiter| waiter.ticket);
            if count < limit.max(1) && best == Some(ticket) {
                *active.counts.entry((dc, large)).or_insert(0) += 1;
                active.waiters.retain(|waiter| waiter.ticket != ticket);
                return Ok(DownloadAdmission {
                    policy: self.clone(),
                    dc,
                    large,
                });
            }
            self.download_wake
                .wait_for(&mut active, std::time::Duration::from_millis(50));
        }
    }

    pub(crate) fn pipeline_parts(&self) -> usize {
        self.pipeline_parts
            .load(Ordering::Relaxed)
            .clamp(1, MAX_PIPELINE_PARTS)
    }

    pub(crate) fn chunk_size(&self) -> i32 {
        let size = self.chunk_size.load(Ordering::Relaxed);
        if valid_chunk(size) {
            size
        } else {
            DEFAULT_CHUNK
        }
    }

    pub(crate) fn file_part(&self) -> usize {
        self.file_part
            .load(Ordering::Relaxed)
            .clamp(FILE_PART_SMALL, FILE_PART_FAST)
    }

    pub(crate) fn assign_media_lanes(&self, lanes: usize) {
        self.media_lanes
            .store(lanes.clamp(1, MAX_MEDIA_LANES), Ordering::Relaxed);
    }

    pub(crate) fn assign_pipeline_parts(&self, parts: usize) {
        self.pipeline_parts
            .store(parts.clamp(1, MAX_PIPELINE_PARTS), Ordering::Relaxed);
    }

    pub(crate) fn assign_chunk_size(&self, size: i32) {
        self.chunk_size
            .store(normalize_chunk(size), Ordering::Relaxed);
    }

    pub(crate) fn assign_file_part_kib(&self, kib: i32) {
        self.file_part
            .store(normalize_file_part_kib(kib), Ordering::Relaxed);
    }

    pub(crate) fn assign_progress(&self, callback: Option<ProgressCallback>) {
        *self.progress.write() = callback;
    }

    pub(crate) fn follow_media_lanes(&self, lanes: usize) {
        if !self.media_lanes_owned.load(Ordering::Relaxed) {
            self.assign_media_lanes(lanes);
        }
    }

    pub(crate) fn follow_pipeline_parts(&self, parts: usize) {
        if !self.pipeline_owned.load(Ordering::Relaxed) {
            self.assign_pipeline_parts(parts);
        }
    }

    pub(crate) fn follow_chunk_size(&self, size: i32) {
        if !self.chunk_owned.load(Ordering::Relaxed) {
            self.assign_chunk_size(size);
        }
    }

    pub(crate) fn follow_file_part_kib(&self, kib: i32) {
        if !self.file_part_owned.load(Ordering::Relaxed) {
            self.assign_file_part_kib(kib);
        }
    }

    pub(crate) fn follow_progress(&self, callback: &Option<ProgressCallback>) {
        if !self.progress_owned.load(Ordering::Relaxed) {
            self.assign_progress(callback.clone());
        }
    }

    pub(crate) fn own_media_lanes(&self, lanes: usize) {
        self.media_lanes_owned.store(true, Ordering::Relaxed);
        self.assign_media_lanes(lanes);
    }

    pub(crate) fn own_pipeline_parts(&self, parts: usize) {
        self.pipeline_owned.store(true, Ordering::Relaxed);
        self.assign_pipeline_parts(parts);
    }

    pub(crate) fn own_chunk_size(&self, size: i32) {
        self.chunk_owned.store(true, Ordering::Relaxed);
        self.assign_chunk_size(size);
    }

    pub(crate) fn own_file_part_kib(&self, kib: i32) {
        self.file_part_owned.store(true, Ordering::Relaxed);
        self.assign_file_part_kib(kib);
    }

    pub(crate) fn own_progress(&self, callback: Option<ProgressCallback>) {
        self.progress_owned.store(true, Ordering::Relaxed);
        self.assign_progress(callback);
    }

    pub(crate) fn notify(&self, path: &str, downloaded: i64, total: i64) {
        if let Some(callback) = self.progress.read().as_ref() {
            callback(path, downloaded, total);
        }
    }
}

pub(crate) struct DownloadAdmission {
    policy: Arc<TransferPolicy>,
    dc: i32,
    large: bool,
}

impl Drop for DownloadAdmission {
    fn drop(&mut self) {
        let mut active = self.policy.downloads.lock();
        if let Some(count) = active.counts.get_mut(&(self.dc, self.large)) {
            *count = count.saturating_sub(1);
        }
        self.policy.download_wake.notify_all();
    }
}

#[derive(Default)]
struct ActiveDownloads {
    counts: crate::HashMap<(i32, bool), usize>,
    waiters: Vec<DownloadWaiter>,
    next_ticket: u64,
}

struct DownloadWaiter {
    ticket: u64,
    priority: u8,
    dc: i32,
    large: bool,
}

fn valid_chunk(size: i32) -> bool {
    matches!(size, 131_072 | 262_144 | 524_288)
}

pub(crate) fn normalize_chunk(size: i32) -> i32 {
    if valid_chunk(size) {
        size
    } else {
        DEFAULT_CHUNK
    }
}

pub(crate) fn normalize_file_part_kib(kib: i32) -> usize {
    if kib >= 512 {
        FILE_PART_FAST
    } else {
        FILE_PART_SMALL
    }
}

pub(crate) fn default_policy() -> Arc<TransferPolicy> {
    static POLICY: OnceLock<Arc<TransferPolicy>> = OnceLock::new();
    POLICY
        .get_or_init(|| Arc::new(TransferPolicy::stock()))
        .clone()
}

pub(crate) fn snapshot() -> Arc<TransferPolicy> {
    let defaults = default_policy();
    let policy = TransferPolicy::stock();
    policy.assign_media_lanes(defaults.media_lanes());
    policy.assign_pipeline_parts(defaults.pipeline_parts());
    policy.assign_chunk_size(defaults.chunk_size());
    policy
        .file_part
        .store(defaults.file_part(), Ordering::Relaxed);
    policy.assign_progress(defaults.progress.read().clone());
    Arc::new(policy)
}

thread_local! {
    static BOUND: RefCell<Option<Arc<TransferPolicy>>> = RefCell::new(None);
}

pub(crate) struct PolicyGuard {
    previous: Option<Arc<TransferPolicy>>,
}

impl Drop for PolicyGuard {
    fn drop(&mut self) {
        let previous = self.previous.take();
        BOUND.with(|slot| *slot.borrow_mut() = previous);
    }
}

pub(crate) fn bind(policy: Arc<TransferPolicy>) -> PolicyGuard {
    BOUND.with(|slot| PolicyGuard {
        previous: slot.borrow_mut().replace(policy),
    })
}

pub(crate) fn current() -> Arc<TransferPolicy> {
    BOUND
        .with(|slot| slot.borrow().clone())
        .unwrap_or_else(default_policy)
}
