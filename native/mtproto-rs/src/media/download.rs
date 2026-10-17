//! https://core.telegram.org/method/upload.getFile
//! https://core.telegram.org/api/files

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};

use crate::{HashMapExt, HashSet, HashSetExt};

use tellers_mtproto::latest::api::{UploadFile, UploadGetFileRequest};
use tellers_mtproto_session::Snapshot;

use super::location::{MediaLocation, MediaRef, input_location, media_dc};
use crate::MtprotoError;
use crate::api_invoke;

pub(crate) const DEFAULT_CHUNK: i32 = 128 * 1024;
pub(crate) const CHUNK: i32 = DEFAULT_CHUNK;
pub(crate) const STREAM_WINDOW_CHUNKS: usize = 4;
pub(crate) const FAST_CHUNK: i32 = 512 * 1024;
/// Telegram keeps foreground file sockets up and pings about every 3 minutes.
pub const DOWNLOAD_SESSION_IDLE: std::time::Duration = std::time::Duration::from_secs(180);

/// https://core.telegram.org/api/files
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DownloadProfile {
    Thumb,
    Ordinary,
    Background,
    Visible,
    Playing,
    User,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DownloadWindow {
    pub chunk: i32,
    pub in_flight: usize,
}

/// Visible, playing, and user files use 512 KiB and 8 parts. Other files stay
/// at 128 KiB, and a thumb is one request.
/// https://core.telegram.org/api/files
pub(crate) fn window_candidate(
    chunk: i32,
    in_flight: usize,
    alignment_proven: bool,
) -> Option<DownloadWindow> {
    let aligned = chunk > 0 && chunk % 4_096 == 0;
    match (chunk, in_flight, alignment_proven && aligned) {
        (131_072, 4, _) | (524_288, 8, _) => Some(DownloadWindow { chunk, in_flight }),
        (1_048_576, 12, true) => Some(DownloadWindow { chunk, in_flight }),
        _ => None,
    }
}

pub fn download_window(profile: DownloadProfile) -> DownloadWindow {
    match profile {
        DownloadProfile::Thumb => DownloadWindow {
            chunk: DEFAULT_CHUNK,
            in_flight: 1,
        },
        DownloadProfile::Ordinary | DownloadProfile::Background => DownloadWindow {
            chunk: DEFAULT_CHUNK,
            in_flight: 4,
        },
        DownloadProfile::Visible | DownloadProfile::Playing | DownloadProfile::User => {
            DownloadWindow {
                chunk: FAST_CHUNK,
                in_flight: 8,
            }
        }
    }
}

/// `LIMIT_INVALID` retries a smaller part and fewer of them in flight.
/// https://core.telegram.org/api/files
pub fn limit_invalid_window(window: DownloadWindow) -> Option<DownloadWindow> {
    if window.chunk <= DEFAULT_CHUNK {
        return None;
    }
    let in_flight = (window.in_flight / 2).clamp(1, 4);
    let in_flight = if in_flight < window.in_flight {
        in_flight
    } else {
        1
    };
    Some(DownloadWindow {
        chunk: DEFAULT_CHUNK,
        in_flight,
    })
}

std::thread_local! {
    static BOUND_WINDOW: std::cell::Cell<u8> = const { std::cell::Cell::new(0) };
    static FORCED_CHUNK: std::cell::Cell<i32> = const { std::cell::Cell::new(0) };
    static REQUEST_PROFILE: std::cell::Cell<u8> = const { std::cell::Cell::new(0) };
}

pub(crate) fn set_request_profile(profile: Option<DownloadProfile>) {
    REQUEST_PROFILE.with(|cell| cell.set(profile.map(profile_tag).unwrap_or(0)));
}

pub(crate) fn request_profile() -> Option<DownloadProfile> {
    REQUEST_PROFILE.with(|cell| profile_from_tag(cell.get()))
}

fn profile_tag(profile: DownloadProfile) -> u8 {
    match profile {
        DownloadProfile::Thumb => 1,
        DownloadProfile::Ordinary => 2,
        DownloadProfile::Background => 3,
        DownloadProfile::Visible => 4,
        DownloadProfile::Playing => 5,
        DownloadProfile::User => 6,
    }
}

fn profile_from_tag(tag: u8) -> Option<DownloadProfile> {
    Some(match tag {
        1 => DownloadProfile::Thumb,
        2 => DownloadProfile::Ordinary,
        3 => DownloadProfile::Background,
        4 => DownloadProfile::Visible,
        5 => DownloadProfile::Playing,
        6 => DownloadProfile::User,
        _ => return None,
    })
}

pub struct DownloadWindowGuard {
    previous_profile: u8,
    previous_chunk: i32,
}

impl Drop for DownloadWindowGuard {
    fn drop(&mut self) {
        BOUND_WINDOW.with(|cell| cell.set(self.previous_profile));
        FORCED_CHUNK.with(|cell| cell.set(self.previous_chunk));
    }
}

pub fn bind_download_profile(profile: DownloadProfile) -> DownloadWindowGuard {
    let window = configured_download_window(profile);
    let previous_profile = BOUND_WINDOW.with(|cell| cell.replace(profile_tag(profile)));
    let previous_chunk = FORCED_CHUNK.with(|cell| cell.replace(window.chunk));
    DownloadWindowGuard {
        previous_profile,
        previous_chunk,
    }
}

pub(crate) fn bound_download_window() -> Option<DownloadWindow> {
    BOUND_WINDOW
        .with(|cell| profile_from_tag(cell.get()))
        .map(configured_download_window)
}

pub(crate) fn configured_download_window(profile: DownloadProfile) -> DownloadWindow {
    let maximum = download_window(profile);
    let policy = crate::transfer_policy::current();
    DownloadWindow {
        chunk: if policy.is_chunk_owned() {
            maximum.chunk.min(policy.chunk_size())
        } else if maximum.chunk >= FAST_CHUNK {
            maximum.chunk
        } else {
            maximum.chunk.min(policy.chunk_size())
        },
        in_flight: maximum.in_flight.min(policy.pipeline_parts()),
    }
}

pub(crate) fn download_in_flight() -> usize {
    bound_download_window()
        .map(|window| window.in_flight)
        .unwrap_or_else(crate::client::pipeline_parts)
}

/// Parts before the aligned seek, or past the in-flight window, are not requested.
/// A non-zero seek stays on the origin DC.
/// https://core.telegram.org/api/files
/// https://core.telegram.org/cdn
pub(crate) fn cancel_outside_seek_window(
    seek: i64,
    chunk: i32,
    window_chunks: usize,
    inflight: &[i64],
) -> Vec<i64> {
    let step = i64::from(chunk.max(1));
    let start = seek - seek.rem_euclid(step);
    let end = start.saturating_add(step.saturating_mul(window_chunks.max(1) as i64));
    inflight
        .iter()
        .copied()
        .filter(|off| *off < start || *off >= end)
        .collect()
}

fn cdn_fields_for_seek(seek: i64) -> (u32, Option<Box<tellers_mtproto::latest::api::True>>) {
    if seek == 0 {
        super::cdn::getfile_cdn_fields()
    } else {
        (0, None)
    }
}

pub use crate::transfer_policy::ProgressCallback;

pub fn set_progress_callback(cb: Option<ProgressCallback>) {
    crate::transfer_policy::default_policy().assign_progress(cb.clone());
    crate::client::visit_policies(|policy| policy.follow_progress(&cb));
}

pub fn notify_progress(path: &str, downloaded: i64, total: i64) {
    crate::transfer_policy::current().notify(path, downloaded, total);
}

pub fn set_chunk_size(size: i32) {
    let size = crate::transfer_policy::normalize_chunk(size);
    crate::transfer_policy::default_policy().assign_chunk_size(size);
    crate::client::visit_policies(|policy| policy.follow_chunk_size(size));
}

pub(crate) fn chunk_size() -> i32 {
    crate::transfer_policy::current().chunk_size()
}

fn resolve_chunk(offset: Option<i64>) -> i32 {
    let forced = FORCED_CHUNK.with(|cell| cell.get());
    let desired = if forced > 0 { forced } else { chunk_size() };
    if let Some(off) = offset {
        if off > 0 && off % i64::from(desired) != 0 && off % i64::from(DEFAULT_CHUNK) == 0 {
            return DEFAULT_CHUNK;
        }
    }
    desired
}

fn is_limit_invalid(err: &MtprotoError) -> bool {
    match err {
        MtprotoError::Message(message) => {
            message.contains("LIMIT_INVALID") || message.contains("limit_invalid")
        }
        _ => false,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TransferStep {
    /// `FILE_MIGRATE_X` only. Home-DC errors such as `USER_MIGRATE` stay put.
    Migrate(i32),
    /// Same file, after the lane has been released.
    ResumeFlood,
    Stop,
}

pub(crate) fn file_migrate_dc(error: &MtprotoError, target_dc: i32, migrations: u8) -> Option<i32> {
    let MtprotoError::Message(message) = error else {
        return None;
    };
    if !message.contains("FILE_MIGRATE_") || migrations >= 3 {
        return None;
    }
    let dc = crate::api_invoke::migrate_dc(error)?;
    if dc == 0 || dc == target_dc {
        None
    } else {
        Some(dc)
    }
}

pub(crate) fn transfer_step(
    error: &MtprotoError,
    target_dc: i32,
    migrations: u8,
    flood_resumes: u8,
) -> TransferStep {
    if let Some(dc) = file_migrate_dc(error, target_dc, migrations) {
        return TransferStep::Migrate(dc);
    }
    if flood_resumes < 3 && flood_wait_secs(error).is_some() {
        return TransferStep::ResumeFlood;
    }
    TransferStep::Stop
}

pub(crate) fn flood_wait_secs(err: &MtprotoError) -> Option<u64> {
    let MtprotoError::Message(message) = err else {
        return None;
    };
    ["FLOOD_WAIT_", "FLOOD_PREMIUM_WAIT_"]
        .iter()
        .filter_map(|prefix| message.split_once(prefix))
        .filter_map(|(_, suffix)| {
            suffix
                .split(|c: char| !c.is_ascii_digit())
                .next()?
                .parse::<u64>()
                .ok()
        })
        .max()
}

/// Server `FLOOD_WAIT` seconds, not a 15 second cap.
/// https://core.telegram.org/api/errors
pub(crate) fn flood_wait_duration(secs: u64) -> std::time::Duration {
    std::time::Duration::from_secs(secs.max(1))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TransferClass {
    Download,
    Upload,
}

#[derive(Clone, Debug)]
pub struct FloodScope {
    slow_until: crate::HashMap<(i32, TransferClass), FloodBackoff>,
}

#[derive(Clone, Debug)]
struct FloodBackoff {
    window: DownloadWindow,
    until: std::time::Instant,
    premium: bool,
    recover_after: std::time::Instant,
    successes: usize,
}

impl FloodScope {
    pub fn new() -> Self {
        Self {
            slow_until: crate::HashMap::new(),
        }
    }

    /// Record the server deadline. The caller unwinds its lease on the error.
    pub fn park(
        &mut self,
        dc_id: i32,
        class: TransferClass,
        seconds: u64,
        premium: bool,
        now: std::time::Instant,
        current: DownloadWindow,
    ) {
        let key = (dc_id, class);
        let previous = self.slow_until.get(&key);
        let slowed = if class == TransferClass::Download {
            DownloadWindow {
                chunk: current.chunk,
                in_flight: if premium {
                    1
                } else {
                    (previous.map_or(current.in_flight, |state| state.window.in_flight) / 2)
                        .clamp(1, 2)
                },
            }
        } else if premium {
            DownloadWindow {
                chunk: DEFAULT_CHUNK,
                in_flight: 1,
            }
        } else {
            DownloadWindow {
                chunk: current.chunk.min(DEFAULT_CHUNK),
                in_flight: current.in_flight.min(4),
            }
        };
        let seconds = seconds.max(1);
        let deadline = (now + flood_wait_duration(seconds))
            .max(previous.map_or(now, |state| state.until));
        let premium = previous
            .filter(|state| state.until > now)
            .map_or(premium, |state| state.premium || premium);
        self.slow_until.insert(key, FloodBackoff {
            window: slowed,
            until: deadline,
            premium,
            recover_after: deadline + std::time::Duration::from_secs(30),
            successes: 0,
        });
    }

    pub fn window(
        &mut self,
        dc_id: i32,
        class: TransferClass,
        now: std::time::Instant,
        fallback: DownloadWindow,
    ) -> DownloadWindow {
        let expired = self
            .slow_until
            .get(&(dc_id, class))
            .is_some_and(|state| now >= state.until);
        if expired && class != TransferClass::Download {
            self.slow_until.remove(&(dc_id, class));
            return fallback;
        }
        self.slow_until
            .get(&(dc_id, class))
            .map(|state| DownloadWindow {
                chunk: state.window.chunk.min(fallback.chunk),
                in_flight: state.window.in_flight.min(fallback.in_flight),
            })
            .unwrap_or(fallback)
    }

    pub(crate) fn recovering_download(&self, dc_id: i32) -> bool {
        self.slow_until.contains_key(&(dc_id, TransferClass::Download))
    }

    pub(crate) fn download_succeeded(&mut self, dc_id: i32, now: std::time::Instant) {
        let Some(state) = self.slow_until.get_mut(&(dc_id, TransferClass::Download)) else {
            return;
        };
        if now < state.until {
            return;
        }
        state.successes = state.successes.saturating_add(1);
        if state.successes >= 32 && now >= state.recover_after && state.window.in_flight < 4 {
            state.window.in_flight += 1;
            state.successes = 0;
            state.recover_after = now + std::time::Duration::from_secs(30);
        }
    }

    pub(crate) fn pending_error(
        &self,
        dc_id: i32,
        class: TransferClass,
        now: std::time::Instant,
    ) -> Option<MtprotoError> {
        let state = self.slow_until.get(&(dc_id, class))?;
        let remaining = state.until.checked_duration_since(now)?;
        if remaining.is_zero() {
            return None;
        }
        let seconds = remaining
            .as_secs()
            .saturating_add(u64::from(remaining.subsec_nanos() > 0));
        Some(MtprotoError::Message(format!(
            "RPC 420: {}_{seconds}",
            if state.premium {
                "FLOOD_PREMIUM_WAIT"
            } else {
                "FLOOD_WAIT"
            }
        )))
    }
}

pub(crate) fn download_flood_active(dc_id: i32) -> bool {
    crate::transfer_policy::current()
        .floods
        .lock()
        .recovering_download(dc_id)
}

pub(crate) fn note_transfer_flood(dc_id: i32, class: TransferClass, err: &MtprotoError) {
    let Some(seconds) = flood_wait_secs(err) else {
        return;
    };
    let premium =
        matches!(err, MtprotoError::Message(message) if message.contains("FLOOD_PREMIUM_WAIT_"));
    crate::transfer_policy::current().floods.lock().park(
        dc_id,
        class,
        seconds,
        premium,
        std::time::Instant::now(),
        bound_download_window().unwrap_or_else(|| download_window(DownloadProfile::Ordinary)),
    );
}

pub(crate) fn check_transfer_flood(dc_id: i32, class: TransferClass) -> Result<(), MtprotoError> {
    if let Some(error) = crate::transfer_policy::current()
        .floods
        .lock()
        .pending_error(dc_id, class, std::time::Instant::now())
    {
        return Err(error);
    }
    Ok(())
}

fn is_rpc_timeout(err: &MtprotoError) -> bool {
    match err {
        MtprotoError::Message(message) => message.contains("RPC timeout"),
        _ => false,
    }
}

/// Keep a partial staging file for another attempt.
pub(crate) fn keep_partial(err: &MtprotoError) -> bool {
    let MtprotoError::Message(message) = err else {
        return false;
    };
    let upper = message.to_ascii_uppercase();
    if upper.contains("CANCEL") {
        return false;
    }
    flood_wait_secs(err).is_some()
        || upper.contains("TIMEOUT")
        || upper.contains("FILE_REFERENCE")
        || upper.contains("DOWNLOAD INCOMPLETE")
        || upper.contains("OPTIONAL_PAUSED")
        || upper.contains("AUTH_KEY_UNREGISTERED")
        || upper.contains("AUTH_KEY_INVALID")
        || upper.contains("AUTH_KEY_DUPLICATED")
        || upper.contains("NETWORK")
        || upper.contains("CONNECTION")
        || upper.contains("TRANSPORT")
}

/// Android `cancel(false)`: stop the transfer and leave the partial bytes.
pub(crate) fn stopped_download(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if message.to_ascii_uppercase().contains("CANCEL"))
}

fn first_missing_offset(counted: &HashSet<i64>, start: i64, end: i64, chunk: i32) -> i64 {
    let step = i64::from(chunk.max(1));
    let mut off = start;
    while off < end {
        if !counted.contains(&off) {
            return off;
        }
        off = off.saturating_add(step);
    }
    end
}

pub fn download_media(
    snapshot: &mut Snapshot,
    api_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
) -> Result<String, MtprotoError> {
    download_media_with_fetch(
        snapshot.dc_id,
        media,
        dest_path,
        cancellation_path,
        |request| api_invoke::invoke_api_without_updates(snapshot, api_id, request),
    )
}

pub(crate) fn download_media_with_fetch(
    dc_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
    fetch: impl FnMut(UploadGetFileRequest) -> Result<UploadFile, MtprotoError>,
) -> Result<String, MtprotoError> {
    download_media_range_with_fetch(dc_id, media, dest_path, cancellation_path, None, fetch)
}

pub(crate) fn download_media_range_with_fetch(
    dc_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
    offset: Option<i64>,
    mut fetch: impl FnMut(UploadGetFileRequest) -> Result<UploadFile, MtprotoError>,
) -> Result<String, MtprotoError> {
    let _chunk_guard = DownloadWindowGuard {
        previous_profile: BOUND_WINDOW.with(|cell| cell.get()),
        previous_chunk: FORCED_CHUNK.with(|cell| cell.get()),
    };
    let chunk = resolve_chunk(offset);
    if offset.is_some_and(|value| value < 0 || value % i64::from(chunk) != 0) {
        return Err(MtprotoError::Message("invalid media range offset".into()));
    }
    if download_cancelled(cancellation_path) {
        return Err(MtprotoError::Message("cancelled".into()));
    }
    if let MediaLocation::Inline { bytes, .. } = &media.location {
        if let Some(parent) = dest_path.parent() {
            fs::create_dir_all(parent).map_err(|e| MtprotoError::Message(e.to_string()))?;
        }
        let bytes = if let Some(offset) = offset {
            let start = usize::try_from(offset)
                .unwrap_or(usize::MAX)
                .min(bytes.len());
            &bytes[start..start.saturating_add(chunk as usize).min(bytes.len())]
        } else {
            bytes.as_slice()
        };
        fs::write(dest_path, bytes).map_err(|e| MtprotoError::Message(e.to_string()))?;
        return Ok(dest_path.display().to_string());
    }

    if let Some(dc) = media_dc(&media.location) {
        if dc != 0 && dc != dc_id {
            // File DC only — caller copies authorization onto a media lane.
            // Do not wipe the snapshot auth key here.
            return Err(MtprotoError::Message(format!("FILE_MIGRATE_{dc}")));
        }
    }

    let location = input_location(&media.location)?;
    write_file_or_cleanup(dest_path, |out| {
        let range = offset.is_some();
        let mut offset = offset.unwrap_or(0);
        loop {
            if download_cancelled(cancellation_path) {
                return Err(MtprotoError::Message("cancelled".into()));
            }
            let (flags, cdn_supported) = cdn_fields_for_seek(if range { offset } else { 0 });
            let request = UploadGetFileRequest {
                flags,
                precise: None,
                cdn_supported,
                location: Box::new(location.clone()),
                offset,
                limit: chunk,
            };
            let response = crate::perf::span("getfile_part").with(|| fetch(request))?;
            match response {
                UploadFile::UploadFile(file) => {
                    let n = file.bytes.len();
                    if n > chunk as usize {
                        return Err(MtprotoError::Message("oversized media part".into()));
                    }
                    out.write_all(&file.bytes)
                        .map_err(|e| MtprotoError::Message(e.to_string()))?;
                    offset += n as i64;
                    notify_progress(&cancellation_path.display().to_string(), offset, 0);
                    if range || n < chunk as usize {
                        break;
                    }
                }
                UploadFile::UploadFileCdnRedirect(redirect) => {
                    super::cdn::store_cdn_redirect(&redirect, offset);
                    return Err(super::cdn::cdn_redirect_error());
                }
                _ => {
                    return Err(MtprotoError::Message("unexpected upload.file".into()));
                }
            }
        }
        Ok(())
    })
}

pub(crate) fn cancel_marker(dest_path: &Path) -> PathBuf {
    let mut raw = dest_path.as_os_str().to_os_string();
    raw.push(".cancel");
    PathBuf::from(raw)
}

pub(crate) fn download_cancelled(dest_path: &Path) -> bool {
    cancel_marker(dest_path).exists()
}

/// Byte length that is a whole number of download chunks. A short tail is not resumable.
pub(crate) fn aligned_prefix(len: i64, chunk: i32) -> i64 {
    if len <= 0 || chunk <= 0 {
        return 0;
    }
    let step = i64::from(chunk);
    len - len % step
}

/// `cancel()` calls `cancel(false)`, so the partial and this range file stay.
/// The sidecar is `<staging-without-.part>.ranges.part`: it ends in `.part`, so
/// `FileCache` treats it as staging, and the `<pid>.<sequence>.part` matcher skips it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct MissingRanges {
    ranges: Vec<(i64, i64)>,
}

impl MissingRanges {
    pub(crate) fn span(start: i64, end: i64) -> Self {
        if end > start && start >= 0 {
            Self {
                ranges: vec![(start, end)],
            }
        } else {
            Self { ranges: Vec::new() }
        }
    }

    pub(crate) fn first_start(&self) -> Option<i64> {
        self.ranges.iter().map(|(start, _)| *start).min()
    }

    pub(crate) fn overlaps(&self, start: i64, end: i64) -> bool {
        end > start
            && self
                .ranges
                .iter()
                .any(|(range_start, range_end)| *range_end > start && *range_start < end)
    }

    /// The file ended at `end`. Drop holes at or past that point and clip the rest.
    pub(crate) fn clip_to_end(&mut self, end: i64) {
        if end < 0 {
            self.ranges.clear();
            return;
        }
        let mut next = Vec::new();
        for (start, range_end) in self.ranges.drain(..) {
            if start >= end || range_end <= start {
                continue;
            }
            next.push((start, range_end.min(end)));
        }
        self.ranges = next;
    }

    /// Drop bytes that have been written. `end` is exclusive.
    pub(crate) fn cover(&mut self, start: i64, end: i64) {
        if end <= start {
            return;
        }
        let mut next = Vec::new();
        for (range_start, range_end) in self.ranges.drain(..) {
            if end <= range_start || start >= range_end {
                next.push((range_start, range_end));
                continue;
            }
            if start > range_start {
                next.push((range_start, start));
            }
            if end < range_end {
                next.push((end, range_end));
            }
        }
        self.ranges = next;
    }

    pub(crate) fn load(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < 4 {
            return None;
        }
        let count = i32::from_be_bytes(bytes[0..4].try_into().ok()?);
        if count < 0 {
            return None;
        }
        let count = count as usize;
        let body = count.checked_mul(16)?;
        let total = body.checked_add(4)?;
        if bytes.len() != total {
            return None;
        }
        let mut ranges = Vec::with_capacity(count);
        for index in 0..count {
            let offset = 4 + index * 16;
            let start = i64::from_be_bytes(bytes[offset..offset + 8].try_into().ok()?);
            let end = i64::from_be_bytes(bytes[offset + 8..offset + 16].try_into().ok()?);
            if start < 0 || end < start {
                return None;
            }
            if end > start {
                ranges.push((start, end));
            }
        }
        Some(Self { ranges })
    }

    pub(crate) fn to_bytes(&self) -> Option<Vec<u8>> {
        let count = i32::try_from(self.ranges.len()).ok()?;
        let mut bytes = Vec::with_capacity(4 + self.ranges.len() * 16);
        bytes.extend_from_slice(&count.to_be_bytes());
        for (start, end) in &self.ranges {
            bytes.extend_from_slice(&start.to_be_bytes());
            bytes.extend_from_slice(&end.to_be_bytes());
        }
        Some(bytes)
    }
}

pub(crate) fn ranges_path(staging: &Path) -> PathBuf {
    let text = staging.to_string_lossy();
    if let Some(stripped) = text.strip_suffix(".part") {
        PathBuf::from(format!("{stripped}.ranges.part"))
    } else {
        PathBuf::from(format!("{text}.ranges.part"))
    }
}

fn load_ranges(staging: &Path) -> Option<MissingRanges> {
    let bytes = fs::read(ranges_path(staging)).ok()?;
    MissingRanges::load(&bytes)
}

fn store_ranges(staging: &Path, ranges: &MissingRanges) {
    let Some(bytes) = ranges.to_bytes() else {
        return;
    };
    let _ = fs::write(ranges_path(staging), bytes);
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RangeIdentity {
    pub file_id: i64,
    pub access_hash: i64,
    pub dc_id: i32,
    pub size: i64,
    pub version: i32,
}

impl RangeIdentity {
    fn to_bytes(&self) -> [u8; 32] {
        let mut bytes = [0u8; 32];
        bytes[0..8].copy_from_slice(&self.file_id.to_le_bytes());
        bytes[8..16].copy_from_slice(&self.access_hash.to_le_bytes());
        bytes[16..20].copy_from_slice(&self.dc_id.to_le_bytes());
        bytes[20..28].copy_from_slice(&self.size.to_le_bytes());
        bytes[28..32].copy_from_slice(&self.version.to_le_bytes());
        bytes
    }

    fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != 32 {
            return None;
        }
        Some(Self {
            file_id: i64::from_le_bytes(bytes[0..8].try_into().ok()?),
            access_hash: i64::from_le_bytes(bytes[8..16].try_into().ok()?),
            dc_id: i32::from_le_bytes(bytes[16..20].try_into().ok()?),
            size: i64::from_le_bytes(bytes[20..28].try_into().ok()?),
            version: i32::from_le_bytes(bytes[28..32].try_into().ok()?),
        })
    }
}

pub(crate) fn location_identity(
    location: &crate::media::MediaLocation,
    size: i64,
    dc_fallback: i32,
) -> Option<RangeIdentity> {
    let (file_id, access_hash, dc_id) = match location {
        crate::media::MediaLocation::Document {
            id,
            access_hash,
            dc_id,
            ..
        }
        | crate::media::MediaLocation::Photo {
            id,
            access_hash,
            dc_id,
            ..
        } => (*id, *access_hash, *dc_id),
        crate::media::MediaLocation::PeerPhoto {
            photo_id,
            access_hash,
            dc_id,
            ..
        } => (*photo_id, *access_hash, *dc_id),
        crate::media::MediaLocation::Inline { .. } => return None,
    };
    Some(RangeIdentity {
        file_id,
        access_hash,
        dc_id: if dc_id == 0 { dc_fallback } else { dc_id },
        size,
        version: 0,
    })
}

pub(crate) fn resume_allowed(stored: Option<&RangeIdentity>, current: &RangeIdentity) -> bool {
    stored.is_some_and(|stored| stored == current)
}

fn identity_path(staging: &Path) -> PathBuf {
    let text = staging.to_string_lossy();
    if let Some(stripped) = text.strip_suffix(".part") {
        PathBuf::from(format!("{stripped}.identity.part"))
    } else {
        PathBuf::from(format!("{text}.identity.part"))
    }
}

fn load_identity(staging: &Path) -> Option<RangeIdentity> {
    RangeIdentity::from_bytes(&fs::read(identity_path(staging)).ok()?)
}

fn store_identity(staging: &Path, identity: &RangeIdentity) {
    let _ = fs::write(identity_path(staging), identity.to_bytes());
}

pub(crate) fn dedup_offsets(offsets: &[i64], chunk: i64) -> Vec<i64> {
    if chunk <= 0 {
        return Vec::new();
    }
    let mut ordered: Vec<i64> = offsets.iter().copied().filter(|off| *off >= 0).collect();
    ordered.sort_unstable();
    ordered.dedup();
    let mut kept = Vec::new();
    for offset in ordered {
        if kept.iter().any(|previous: &i64| offset < previous + chunk && offset >= *previous) {
            continue;
        }
        kept.push(offset);
    }
    kept
}

static INFLIGHT_RANGES: std::sync::LazyLock<Mutex<HashSet<(i64, i64)>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashSet::new()));

pub(crate) fn claim_offsets(file_id: i64, offsets: &[i64]) -> Vec<i64> {
    let mut held = INFLIGHT_RANGES.lock().unwrap_or_else(|error| error.into_inner());
    offsets
        .iter()
        .copied()
        .filter(|offset| held.insert((file_id, *offset)))
        .collect()
}

pub(crate) fn release_offsets(file_id: i64, offsets: &[i64]) {
    let mut held = INFLIGHT_RANGES.lock().unwrap_or_else(|error| error.into_inner());
    for offset in offsets {
        held.remove(&(file_id, *offset));
    }
}

struct OffsetClaim(Option<(i64, Vec<i64>)>);

impl Drop for OffsetClaim {
    fn drop(&mut self) {
        if let Some((file_id, offsets)) = self.0.take() {
            release_offsets(file_id, &offsets);
        }
    }
}

pub(crate) fn should_pause_optional(profile: Option<DownloadProfile>) -> bool {
    matches!(profile, Some(DownloadProfile::Background))
        && crate::client::lanes::visible_transfer_waiters() > 0
}

pub(crate) fn optional_paused(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if message.contains("OPTIONAL_PAUSED"))
}

/// Where a full-file retry should request next. A range file wins. A missing or
/// corrupt file falls back to the chunk-aligned length. Neither path panics.
pub(crate) fn resume_start(ranges: Option<&MissingRanges>, file_len: i64, chunk: i32) -> i64 {
    if let Some(start) = ranges.and_then(MissingRanges::first_start) {
        return aligned_prefix(start, chunk);
    }
    aligned_prefix(file_len.max(0), chunk)
}

/// Final length after a full-file attempt. A remaining hole is `Err` so the
/// partial stays. Success uses the known size, never the first-hole cursor,
/// so a tail past the hole is not truncated.
pub(crate) fn preserved_end(
    file_len: i64,
    written_end: i64,
    expected: Option<i64>,
    missing: Option<&MissingRanges>,
) -> Result<i64, ()> {
    let known = file_len.max(written_end).max(0);
    if let Some(expected) = expected.filter(|size| *size > 0) {
        if missing.is_some_and(|ranges| ranges.overlaps(0, expected)) {
            return Err(());
        }
        return Ok(expected);
    }
    if missing.is_some_and(|ranges| ranges.overlaps(0, known.max(1))) {
        return Err(());
    }
    Ok(known)
}

fn request_offsets(ranges: &MissingRanges, chunk: i64, limit: usize) -> Vec<i64> {
    if chunk <= 0 || limit == 0 {
        return Vec::new();
    }
    let mut offsets = Vec::new();
    let mut ordered = ranges.ranges.clone();
    ordered.sort_by_key(|(start, _)| *start);
    for (start, end) in ordered {
        if end <= start {
            continue;
        }
        let mut offset = start - start % chunk;
        if offset < 0 {
            offset = 0;
        }
        while offset < end && offsets.len() < limit {
            if offset + chunk > start && !offsets.contains(&offset) {
                offsets.push(offset);
            }
            offset = offset.saturating_add(chunk);
        }
        if offsets.len() >= limit {
            break;
        }
    }
    offsets
}

pub(crate) fn is_native_staging_name(name: &str, destination_name: &str) -> bool {
    let Some(body) = name
        .strip_prefix(destination_name)
        .and_then(|rest| rest.strip_prefix('.'))
        .and_then(|rest| rest.strip_suffix(".part"))
    else {
        return false;
    };
    let Some((pid, sequence)) = body.split_once('.') else {
        return false;
    };
    !pid.is_empty()
        && !sequence.is_empty()
        && pid.bytes().all(|byte| byte.is_ascii_digit())
        && sequence.bytes().all(|byte| byte.is_ascii_digit())
}

const STAGING_DIR_BYTE_CAP: u64 = 32 * 1024 * 1024;
const STAGING_STALE_SECS: u64 = 120;

fn remove_staging_sidecars(staging: &Path) {
    let _ = fs::remove_file(ranges_path(staging));
    let _ = fs::remove_file(identity_path(staging));
}

pub(crate) fn sweep_orphan_sidecars(dir: &Path, protect: &Path) {
    let protect_identity = identity_path(protect);
    let protect_ranges = ranges_path(protect);
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path == protect || path == protect_identity || path == protect_ranges {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let stem = name
            .strip_suffix(".identity.part")
            .or_else(|| name.strip_suffix(".ranges.part"));
        let Some(stem) = stem else {
            continue;
        };
        if !dir.join(format!("{stem}.part")).exists() {
            let _ = fs::remove_file(&path);
        }
    }
}

pub(crate) fn sweep_stale_parts(dir: &Path, protect: &Path) {
    sweep_orphan_sidecars(dir, protect);
    let Some(cutoff) = std::time::SystemTime::now()
        .checked_sub(std::time::Duration::from_secs(STAGING_STALE_SECS))
    else {
        return;
    };
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let mut stale = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if path == protect {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        if !name.ends_with(".part") {
            continue;
        }
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        let Ok(modified) = meta.modified() else {
            continue;
        };
        if modified > cutoff {
            continue;
        }
        stale.push((modified, meta.len(), path));
    }
    let mut total: u64 = stale.iter().map(|(_, len, _)| *len).sum();
    if total <= STAGING_DIR_BYTE_CAP {
        return;
    }
    stale.sort_by_key(|(modified, _, _)| *modified);
    for (_, len, path) in stale {
        if total <= STAGING_DIR_BYTE_CAP {
            break;
        }
        if fs::remove_file(&path).is_ok() {
            total = total.saturating_sub(len);
            remove_staging_sidecars(&path);
        }
    }
}

fn native_staging_files(destination: &Path) -> Vec<PathBuf> {
    let Some(name) = destination.file_name().and_then(|name| name.to_str()) else {
        return Vec::new();
    };
    let parent = destination.parent().filter(|path| !path.as_os_str().is_empty()).unwrap_or_else(|| Path::new("."));
    let Ok(entries) = fs::read_dir(parent) else {
        return Vec::new();
    };
    entries
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|file_name| file_name.to_str())
                .is_some_and(|file_name| is_native_staging_name(file_name, name))
        })
        .collect()
}

fn take_resume_file(destination: &Path) -> Option<PathBuf> {
    let mut files = native_staging_files(destination);
    if files.is_empty() {
        return None;
    }
    files.sort_by_key(|path| fs::metadata(path).map(|meta| meta.len()).unwrap_or(0));
    let best = files.pop()?;
    for extra in files {
        let _ = fs::remove_file(extra);
    }
    if fs::metadata(&best).map(|meta| meta.len()).unwrap_or(0) == 0 {
        let _ = fs::remove_file(&best);
        return None;
    }
    Some(best)
}

struct DestOwner {
    thread: Option<std::thread::ThreadId>,
    depth: usize,
}

struct DestEntry {
    state: Mutex<DestOwner>,
    cv: Condvar,
}

/// One writer per destination. The same thread may lock again when a home-DC
/// download takes over a lane that already staged the same path.
fn destination_locks() -> &'static Mutex<std::collections::HashMap<PathBuf, Arc<DestEntry>>> {
    static LOCKS: std::sync::OnceLock<Mutex<std::collections::HashMap<PathBuf, Arc<DestEntry>>>> =
        std::sync::OnceLock::new();
    LOCKS.get_or_init(|| Mutex::new(std::collections::HashMap::new()))
}

pub(crate) struct DestinationLock {
    path: PathBuf,
    entry: Arc<DestEntry>,
}

impl DestinationLock {
    pub(crate) fn acquire(path: &Path) -> Self {
        let entry = {
            let mut map = destination_locks()
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            map.entry(path.to_path_buf())
                .or_insert_with(|| {
                    Arc::new(DestEntry {
                        state: Mutex::new(DestOwner {
                            thread: None,
                            depth: 0,
                        }),
                        cv: Condvar::new(),
                    })
                })
                .clone()
        };
        let id = std::thread::current().id();
        let mut owner = entry.state.lock().unwrap_or_else(|error| error.into_inner());
        loop {
            if owner.thread == Some(id) {
                owner.depth += 1;
                break;
            }
            if owner.thread.is_none() {
                owner.thread = Some(id);
                owner.depth = 1;
                break;
            }
            owner = entry.cv.wait(owner).unwrap_or_else(|error| error.into_inner());
        }
        drop(owner);
        Self {
            path: path.to_path_buf(),
            entry,
        }
    }
}

impl Drop for DestinationLock {
    fn drop(&mut self) {
        let mut owner = self.entry.state.lock().unwrap_or_else(|error| error.into_inner());
        owner.depth = owner.depth.saturating_sub(1);
        if owner.depth == 0 {
            owner.thread = None;
            self.entry.cv.notify_one();
        }
        let idle = owner.depth == 0;
        drop(owner);
        if !idle {
            return;
        }
        let mut map = destination_locks()
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if Arc::strong_count(&self.entry) == 2
            && map
                .get(&self.path)
                .is_some_and(|existing| Arc::ptr_eq(existing, &self.entry))
        {
            map.remove(&self.path);
        }
    }
}

#[cfg(test)]
pub(crate) fn destination_lock_entries() -> usize {
    destination_locks()
        .lock()
        .map(|map| map.len())
        .unwrap_or(0)
}

#[cfg(test)]
#[path = "../../tests/unit/destination_lock_tests.rs"]
mod destination_lock_tests;

#[cfg(test)]
#[path = "../../tests/unit/staging_sweep_tests.rs"]
mod staging_sweep_tests;

/// The caller publishes under its session lease. A dropped download removes its
/// staging file unless [StagedDownload::keep] preserved a retryable partial.
pub(crate) struct StagedDownload {
    path: PathBuf,
    preserve: bool,
    _lock: DestinationLock,
}

impl StagedDownload {
    pub(crate) fn new(destination: &Path) -> Result<Self, MtprotoError> {
        Self::open(destination, false)
    }

    /// Reopen `<destination>.<pid>.<sequence>.part` when a full-file download is retried.
    pub(crate) fn resume_or_create(destination: &Path) -> Result<Self, MtprotoError> {
        Self::open(destination, true)
    }

    fn open(destination: &Path, resume: bool) -> Result<Self, MtprotoError> {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let lock = DestinationLock::acquire(destination);
        if let Some(parent) = destination.parent().filter(|path| !path.as_os_str().is_empty()) {
            fs::create_dir_all(parent).map_err(|e| MtprotoError::Message(e.to_string()))?;
        }
        if download_cancelled(destination) {
            return Err(MtprotoError::Message("cancelled".into()));
        }
        if resume {
            if let Some(path) = take_resume_file(destination) {
                return Ok(Self {
                    path,
                    preserve: false,
                    _lock: lock,
                });
            }
        }
        loop {
            let mut name = destination.as_os_str().to_os_string();
            name.push(format!(
                ".{}.{}.part",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let path = PathBuf::from(name);
            match fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
            {
                Ok(_) => {
                    return Ok(Self {
                        path,
                        preserve: false,
                        _lock: lock,
                    })
                }
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(MtprotoError::Message(e.to_string())),
            }
        }
    }

    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    /// Leave the staging file on disk. Used for retryable failures and process death.
    pub(crate) fn keep(mut self) {
        self.preserve = true;
        if let Some(parent) = self.path.parent() {
            sweep_stale_parts(parent, &self.path);
        }
    }

    pub(crate) fn publish(mut self, destination: &Path) -> Result<String, MtprotoError> {
        if download_cancelled(destination) {
            self.preserve = true;
            return Err(MtprotoError::Message("cancelled".into()));
        }
        remove_staging_sidecars(&self.path);
        fs::rename(&self.path, destination).map_err(|e| MtprotoError::Message(e.to_string()))?;
        if let Some(parent) = destination.parent() {
            sweep_orphan_sidecars(parent, destination);
        }
        Ok(destination.display().to_string())
    }
}

impl Drop for StagedDownload {
    fn drop(&mut self) {
        if !self.preserve {
            let _ = fs::remove_file(&self.path);
            remove_staging_sidecars(&self.path);
        }
    }
}

/// Requests `parts_in_flight` parts on one session and refills the window as each
/// part returns. EOF is a short or empty part.
pub(crate) fn download_media_range_batched(
    dc_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
    offset: Option<i64>,
    parts_in_flight: usize,
    mut fetch_batch: impl FnMut(
        bool,
        Vec<UploadGetFileRequest>,
    ) -> Result<Vec<Result<UploadFile, MtprotoError>>, MtprotoError>,
) -> Result<String, MtprotoError> {
    download_media_range_batched_streaming(
        dc_id,
        media,
        dest_path,
        cancellation_path,
        offset,
        parts_in_flight,
        |first, requests, on_chunk| {
            let results = fetch_batch(first, requests)?;
            for (index, result) in results.iter().enumerate() {
                match result {
                    Ok(file) => {
                        let _ = on_chunk(index, Ok(file.clone()));
                    }
                    Err(err) => {
                        let _ = on_chunk(index, Err(err.clone()));
                    }
                }
            }
            Ok(results)
        },
    )
}

pub(crate) fn download_media_range_batched_streaming(
    dc_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
    offset: Option<i64>,
    parts_in_flight: usize,
    fetch_batch: impl FnMut(
        bool,
        Vec<UploadGetFileRequest>,
        &mut dyn FnMut(usize, Result<UploadFile, MtprotoError>) -> Option<UploadGetFileRequest>,
    ) -> Result<Vec<Result<UploadFile, MtprotoError>>, MtprotoError>,
) -> Result<String, MtprotoError> {
    download_media_range_batched_streaming_capped(
        dc_id,
        media,
        dest_path,
        cancellation_path,
        offset,
        parts_in_flight,
        None,
        fetch_batch,
    )
}

/// `refill_limit` caps sliding refills per `fetch_batch` so the caller can drop
/// a home-DC main-session lock between windows. `None` keeps the window full
/// until EOF (media-DC file sessions).
pub(crate) fn download_media_range_batched_streaming_capped(
    dc_id: i32,
    media: &MediaRef,
    dest_path: &Path,
    cancellation_path: &Path,
    offset: Option<i64>,
    parts_in_flight: usize,
    refill_limit: Option<usize>,
    mut fetch_batch: impl FnMut(
        bool,
        Vec<UploadGetFileRequest>,
        &mut dyn FnMut(usize, Result<UploadFile, MtprotoError>) -> Option<UploadGetFileRequest>,
    ) -> Result<Vec<Result<UploadFile, MtprotoError>>, MtprotoError>,
) -> Result<String, MtprotoError> {
    let _chunk_guard = DownloadWindowGuard {
        previous_profile: BOUND_WINDOW.with(|cell| cell.get()),
        previous_chunk: FORCED_CHUNK.with(|cell| cell.get()),
    };
    let chunk = resolve_chunk(offset);
    if offset.is_some_and(|value| value < 0 || value % i64::from(chunk) != 0) {
        return Err(MtprotoError::Message("invalid media range offset".into()));
    }
    if download_cancelled(cancellation_path) {
        return Err(MtprotoError::Message("cancelled".into()));
    }
    if let MediaLocation::Inline { .. } = &media.location {
        return download_media_range_with_fetch(
            dc_id,
            media,
            dest_path,
            cancellation_path,
            offset,
            |request| {
                let mut results = fetch_batch(false, vec![request], &mut |_, _| None)?;
                results
                    .pop()
                    .unwrap_or_else(|| Err(MtprotoError::Message("missing media part".into())))
            },
        );
    }
    if let Some(dc) = media_dc(&media.location) {
        if dc != 0 && dc != dc_id {
            return Err(MtprotoError::Message(format!("FILE_MIGRATE_{dc}")));
        }
    }

    let location = input_location(&media.location)?;
    if let Some(parent) = dest_path.parent() {
        fs::create_dir_all(parent).map_err(|e| MtprotoError::Message(e.to_string()))?;
    }
    let result = (|| -> Result<(), MtprotoError> {
        let stream = offset.is_some();
        let mut file_len = fs::metadata(dest_path).map(|meta| meta.len() as i64).unwrap_or(0);
        let identity = location_identity(&media.location, media.file_size.unwrap_or(0), dc_id);
        let mut loaded_ranges = if stream { None } else { load_ranges(dest_path) };
        if !stream {
            if let Some(identity) = identity.clone() {
                if file_len > 0 && !resume_allowed(load_identity(dest_path).as_ref(), &identity) {
                    loaded_ranges = None;
                    file_len = 0;
                    let _ = fs::remove_file(ranges_path(dest_path));
                }
                store_identity(dest_path, &identity);
            }
        }
        let chunk_now = resolve_chunk(offset);
        let origin = if stream {
            offset.unwrap_or(0)
        } else {
            resume_start(loaded_ranges.as_ref(), file_len, chunk_now)
        };
        let mut missing = if stream {
            None
        } else if let Some(ranges) = loaded_ranges.clone() {
            Some(ranges)
        } else {
            let end = media.file_size.filter(|size| *size > 0).unwrap_or(i64::MAX);
            Some(MissingRanges::span(origin, end))
        };
        if !stream {
            if let Some(ranges) = missing.as_ref() {
                store_ranges(dest_path, ranges);
            }
        }
        let mut out = if !stream && file_len > 0 {
            use std::io::{Seek, SeekFrom};
            let mut file = fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(dest_path)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            if loaded_ranges.is_none() && origin < file_len {
                file.set_len(origin as u64)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
            }
            file.seek(SeekFrom::Start(origin as u64))
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            file
        } else {
            fs::File::create(dest_path).map_err(|e| MtprotoError::Message(e.to_string()))?
        };
        let window = crate::transfer_policy::current().floods.lock().window(
            dc_id,
            TransferClass::Download,
            std::time::Instant::now(),
            DownloadWindow {
                chunk: resolve_chunk(if origin > 0 { Some(origin) } else { offset }),
                in_flight: parts_in_flight.max(1),
            },
        );
        FORCED_CHUNK.with(|cell| cell.set(window.chunk));
        let mut width = window.in_flight;
        let mut next = origin;
        let mut retried_limit = false;
        let mut timeout_retries = 0u8;
        let mut written_end = origin;
        let mut downloaded_bytes = origin;
        let already_complete = !stream && media.file_size.is_some_and(|expected| {
            expected > 0 && missing.as_ref().is_none_or(|ranges| !ranges.overlaps(0, expected))
        });
        if already_complete {
            let expected = media.file_size.unwrap_or(origin);
            out.set_len(expected as u64)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            written_end = expected;
            downloaded_bytes = expected;
        } else if origin > 0 {
            notify_progress(
                &cancellation_path.display().to_string(),
                origin,
                media.file_size.unwrap_or(0),
            );
        }
        let mut counted_offsets = HashSet::new();
        let mut first_batch = true;
        let target_str = cancellation_path.display().to_string();
        'download: loop {
            if already_complete {
                break 'download;
            }
            width = crate::transfer_policy::current().floods.lock().window(
                dc_id,
                TransferClass::Download,
                std::time::Instant::now(),
                DownloadWindow { chunk: resolve_chunk(offset), in_flight: parts_in_flight.max(1) },
            ).in_flight.min(if retried_limit { width } else { parts_in_flight.max(1) });
            let chunk = resolve_chunk(Some(next).filter(|_| next > 0).or(offset));
            let batch = if stream {
                bound_download_window()
                    .map(|window| window.in_flight)
                    .unwrap_or(STREAM_WINDOW_CHUNKS)
                    .min(width.max(1)).min((FAST_CHUNK / chunk).max(1) as usize)
            } else {
                width
            };
            loop {
                if download_cancelled(cancellation_path) {
                    return Err(MtprotoError::Message("cancelled".into()));
                }
                if should_pause_optional(request_profile()) {
                    return Err(MtprotoError::Message("OPTIONAL_PAUSED".into()));
                }
                let mut offsets = if let Some(ranges) = missing.as_ref().filter(|_| !stream) {
                    let planned = request_offsets(ranges, i64::from(chunk.max(1)), batch);
                    if planned.is_empty() {
                        break 'download;
                    }
                    next = planned[0];
                    planned
                } else {
                    let mut sequential = Vec::with_capacity(batch);
                    for step in 0..batch {
                        sequential.push(next + (step as i64) * i64::from(chunk));
                    }
                    sequential
                };
                if stream {
                    let seek = offset.unwrap_or(next);
                    let cancelled = cancel_outside_seek_window(seek, chunk, batch, &offsets);
                    if !cancelled.is_empty() {
                        offsets.retain(|off| !cancelled.contains(off));
                    }
                }
                offsets = dedup_offsets(&offsets, i64::from(chunk.max(1)));
                if let Some(identity) = identity.clone() {
                    offsets = claim_offsets(identity.file_id, &offsets);
                }
                if offsets.is_empty() {
                    std::thread::sleep(std::time::Duration::from_millis(5));
                    continue;
                }
                let _release_claim = OffsetClaim(
                    identity.clone().map(|identity| (identity.file_id, offsets.clone())),
                );
                let (flags, cdn_supported) = cdn_fields_for_seek(origin);
                let requests: Vec<UploadGetFileRequest> = offsets
                    .iter()
                    .map(|offset| UploadGetFileRequest {
                        flags,
                        precise: None,
                        cdn_supported: cdn_supported.clone(),
                        location: Box::new(location.clone()),
                        offset: *offset,
                        limit: chunk,
                    })
                    .collect();
                let mut written_parts = vec![false; offsets.len()];
                let mut short_or_empty = vec![false; offsets.len()];
                let mut last_part = false;
                let mut stop_refill = false;

                let mut offset_by_index = offsets.clone();
                let mut next_to_request = next + (batch as i64) * i64::from(chunk);
                let mut refills_used = 0usize;
                let responses = {
                    let mut chunk_cb = |index: usize,
                                        res: Result<UploadFile, MtprotoError>|
                     -> Option<UploadGetFileRequest> {
                        if download_cancelled(cancellation_path) {
                            return None;
                        }
                        let part_offset = *offset_by_index.get(index)?;
                        if !matches!(&res, Ok(UploadFile::UploadFile(_))) {
                            stop_refill = true;
                        }
                        let mut hit_short = false;
                        if let Ok(UploadFile::UploadFile(file)) = &res {
                            let bytes = &file.bytes;
                            if !bytes.is_empty() && bytes.len() <= chunk as usize {
                                use std::io::{Seek, SeekFrom, Write};
                                if out.seek(SeekFrom::Start((if stream { part_offset - origin } else { part_offset }) as u64)).is_ok()
                                    && out.write_all(bytes).is_ok()
                                {
                                    written_end = written_end.max(part_offset + bytes.len() as i64);
                                    if let Some(ranges) = missing.as_mut() {
                                        ranges.cover(part_offset, part_offset + bytes.len() as i64);
                                        store_ranges(dest_path, ranges);
                                    }
                                    if counted_offsets.insert(part_offset) {
                                        downloaded_bytes += bytes.len() as i64;
                                        crate::transfer_policy::current().floods.lock()
                                            .download_succeeded(dc_id, std::time::Instant::now());
                                        if crate::transfer_policy::current().floods.lock().window(
                                            dc_id, TransferClass::Download, std::time::Instant::now(),
                                            DownloadWindow { chunk, in_flight: parts_in_flight.max(1) },
                                        ).in_flight != width {
                                            stop_refill = true;
                                        }
                                        notify_progress(&target_str, downloaded_bytes, 0);
                                    }
                                    if index < written_parts.len() {
                                        written_parts[index] = true;
                                    }
                                }
                            }
                            if bytes.len() < chunk as usize {
                                hit_short = true;
                                last_part = true;
                                if index < short_or_empty.len() {
                                    short_or_empty[index] = true;
                                }
                            }
                        }
                        if stream || hit_short || last_part || stop_refill {
                            return None;
                        }
                        if refill_limit.is_some_and(|limit| refills_used >= limit) {
                            return None;
                        }
                        while counted_offsets.contains(&next_to_request) {
                            next_to_request += i64::from(chunk);
                        }
                        refills_used += 1;
                        let off = next_to_request;
                        next_to_request += i64::from(chunk);
                        offset_by_index.push(off);
                        crate::perf::count("media.pipeline.refill");
                        Some(UploadGetFileRequest {
                            flags,
                            precise: None,
                            cdn_supported: cdn_supported.clone(),
                            location: Box::new(location.clone()),
                            offset: off,
                            limit: chunk,
                        })
                    };
                    match fetch_batch(first_batch, requests, &mut chunk_cb) {
                        Err(err)
                            if !retried_limit
                                && is_limit_invalid(&err)
                                && limit_invalid_window(DownloadWindow {
                                    chunk,
                                    in_flight: width,
                                })
                                .is_some() =>
                        {
                            let next = limit_invalid_window(DownloadWindow {
                                chunk,
                                in_flight: width,
                            })
                            .expect("window");
                            FORCED_CHUNK.with(|cell| cell.set(next.chunk));
                            width = next.in_flight.max(1);
                            retried_limit = true;
                            continue 'download;
                        }
                        Err(err) if flood_wait_secs(&err).is_some() => {
                            note_transfer_flood(dc_id, TransferClass::Download, &err);
                            return Err(err);
                        }
                        Err(err) if timeout_retries < 3 && is_rpc_timeout(&err) => {
                            timeout_retries += 1;
                            next = first_missing_offset(
                                &counted_offsets,
                                origin,
                                next_to_request,
                                chunk,
                            );
                            continue 'download;
                        }
                        other => other?,
                    }
                };

                first_batch = false;
                timeout_retries = 0;
                for (index, response) in responses.into_iter().enumerate() {
                    if download_cancelled(cancellation_path) {
                        return Err(MtprotoError::Message("cancelled".into()));
                    }
                    if last_part {
                        // Reached EOF at an earlier part; ignore any trailing parts/errors past EOF.
                        break;
                    }
                    if index >= written_parts.len() {
                        continue;
                    }
                    if written_parts[index] {
                        if short_or_empty[index] {
                            last_part = true;
                        }
                        continue;
                    }
                    let part_offset = match offsets.get(index) {
                        Some(value) => *value,
                        None => continue,
                    };
                    match response {
                        Ok(UploadFile::UploadFile(file)) => {
                            let bytes = file.bytes;
                            if bytes.len() > chunk as usize {
                                return Err(MtprotoError::Message("oversized media part".into()));
                            }
                            if bytes.is_empty() {
                                last_part = true;
                                break;
                            }
                            use std::io::{Seek, SeekFrom, Write};
                            out.seek(SeekFrom::Start((if stream { part_offset - origin } else { part_offset }) as u64))
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            out.write_all(&bytes)
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            written_end = written_end.max(part_offset + bytes.len() as i64);
                            if counted_offsets.insert(part_offset) {
                                downloaded_bytes += bytes.len() as i64;
                                notify_progress(&target_str, downloaded_bytes, 0);
                            }
                            if bytes.len() < chunk as usize {
                                last_part = true;
                            }
                        }
                        Ok(UploadFile::UploadFileCdnRedirect(redirect)) => {
                            super::cdn::store_cdn_redirect(&redirect, part_offset);
                            return Err(super::cdn::cdn_redirect_error());
                        }
                        Ok(_) => {
                            return Err(MtprotoError::Message("unexpected media response".into()));
                        }
                        Err(err) => {
                            if !retried_limit && is_limit_invalid(&err) {
                                if let Some(next) = limit_invalid_window(DownloadWindow {
                                    chunk,
                                    in_flight: width,
                                }) {
                                    FORCED_CHUNK.with(|cell| cell.set(next.chunk));
                                    width = next.in_flight.max(1);
                                    retried_limit = true;
                                    continue 'download;
                                }
                            }
                            if flood_wait_secs(&err).is_some() {
                                note_transfer_flood(dc_id, TransferClass::Download, &err);
                                return Err(err);
                            }
                            if timeout_retries < 3 && is_rpc_timeout(&err) {
                                timeout_retries += 1;
                                next = first_missing_offset(
                                    &counted_offsets,
                                    origin,
                                    next_to_request,
                                    chunk,
                                );
                                continue 'download;
                            }
                            return Err(err);
                        }
                    }
                }
                if stream {
                    break 'download;
                }
                if last_part {
                    if let Some(ranges) = missing.as_mut() {
                        ranges.clip_to_end(written_end);
                        store_ranges(dest_path, ranges);
                    }
                    let still_open = missing
                        .as_ref()
                        .is_some_and(|ranges| ranges.overlaps(0, written_end.max(1)));
                    if !still_open {
                        break 'download;
                    }
                }
                // A caller may ignore the refill request returned by `on_chunk`.
                // Continue at the first hole instead of the cursor those refills advanced.
                next = first_missing_offset(
                    &counted_offsets,
                    origin,
                    next_to_request,
                    chunk,
                );
            }
        } // 'download
        if !stream {
            match preserved_end(file_len, written_end, media.file_size, missing.as_ref()) {
                Err(()) => {
                    let expected = media.file_size.unwrap_or(0);
                    return Err(MtprotoError::Message(format!(
                        "download incomplete: received {written_end} of {expected} bytes"
                    )));
                }
                Ok(end) => {
                    out.set_len(end as u64)
                        .map_err(|e| MtprotoError::Message(e.to_string()))?;
                }
            }
        } else {
            out.set_len((written_end - origin).max(0) as u64)
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
        }
        out.flush()
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        Ok(())
    })();
    match result {
        Ok(()) => Ok(dest_path.display().to_string()),
        Err(err) => {
            if !keep_partial(&err) && !stopped_download(&err) {
                let _ = fs::remove_file(dest_path);
                let _ = fs::remove_file(ranges_path(dest_path));
            }
            Err(err)
        }
    }
}

pub(crate) fn write_file_or_cleanup<F>(dest_path: &Path, write: F) -> Result<String, MtprotoError>
where
    F: FnOnce(&mut fs::File) -> Result<(), MtprotoError>,
{
    if let Some(parent) = dest_path.parent() {
        fs::create_dir_all(parent).map_err(|e| MtprotoError::Message(e.to_string()))?;
    }
    let mut file = fs::File::create(dest_path).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let written = write(&mut file);
    if written.is_ok() {
        if let Err(e) = file.flush() {
            drop(file);
            let _ = fs::remove_file(dest_path);
            return Err(MtprotoError::Message(e.to_string()));
        }
    }
    drop(file);
    match written {
        Ok(()) => Ok(dest_path.display().to_string()),
        Err(err) => {
            let _ = fs::remove_file(dest_path);
            Err(err)
        }
    }
}

#[cfg(test)]
#[path = "../../tests/unit/media_download_profile_tests.rs"]
mod profile_tests;
