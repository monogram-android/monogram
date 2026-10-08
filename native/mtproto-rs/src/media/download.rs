//! https://core.telegram.org/method/upload.getFile
//! https://core.telegram.org/api/files

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

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
        chunk: maximum.chunk.min(policy.chunk_size()),
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
    slow_until: crate::HashMap<(i32, TransferClass), (DownloadWindow, std::time::Instant, bool)>,
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
        let slowed = if premium {
            DownloadWindow {
                chunk: DEFAULT_CHUNK,
                in_flight: 2,
            }
        } else {
            current
        };
        let seconds = seconds.max(1);
        let deadline = now + flood_wait_duration(seconds);
        if let Some((_, existing_deadline, _)) = self.slow_until.get(&(dc_id, class)) {
            if *existing_deadline > deadline {
                return;
            }
        }
        self.slow_until
            .insert((dc_id, class), (slowed, deadline, premium));
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
            .is_some_and(|(_, until, _)| now >= *until);
        if expired {
            self.slow_until.remove(&(dc_id, class));
            return fallback;
        }
        self.slow_until
            .get(&(dc_id, class))
            .map(|(window, _, _)| *window)
            .unwrap_or(fallback)
    }

    pub(crate) fn pending_error(
        &self,
        dc_id: i32,
        class: TransferClass,
        now: std::time::Instant,
    ) -> Option<MtprotoError> {
        let (_, until, premium) = self.slow_until.get(&(dc_id, class))?;
        let remaining = until.checked_duration_since(now)?;
        if remaining.is_zero() {
            return None;
        }
        let seconds = remaining
            .as_secs()
            .saturating_add(u64::from(remaining.subsec_nanos() > 0));
        Some(MtprotoError::Message(format!(
            "RPC 420: {}_{seconds}",
            if *premium {
                "FLOOD_PREMIUM_WAIT"
            } else {
                "FLOOD_WAIT"
            }
        )))
    }
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

/// The caller publishes under its session lease; dropping any failed or stale
/// download removes only its own staging file, never a previously cached file.
pub(crate) struct StagedDownload {
    path: PathBuf,
}

impl StagedDownload {
    pub(crate) fn new(destination: &Path) -> Result<Self, MtprotoError> {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(1);
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent).map_err(|e| MtprotoError::Message(e.to_string()))?;
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
                Ok(_) => return Ok(Self { path }),
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(MtprotoError::Message(e.to_string())),
            }
        }
    }

    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    pub(crate) fn publish(self, destination: &Path) -> Result<String, MtprotoError> {
        if download_cancelled(destination) {
            return Err(MtprotoError::Message("cancelled".into()));
        }
        fs::rename(&self.path, destination).map_err(|e| MtprotoError::Message(e.to_string()))?;
        Ok(destination.display().to_string())
    }
}

impl Drop for StagedDownload {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
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
        let mut out =
            fs::File::create(dest_path).map_err(|e| MtprotoError::Message(e.to_string()))?;
        let window = crate::transfer_policy::current().floods.lock().window(
            dc_id,
            TransferClass::Download,
            std::time::Instant::now(),
            DownloadWindow {
                chunk: resolve_chunk(offset),
                in_flight: parts_in_flight.max(1),
            },
        );
        FORCED_CHUNK.with(|cell| cell.set(window.chunk));
        let mut width = window.in_flight;
        let stream = offset.is_some();
        let mut next = offset.unwrap_or(0);
        let mut retried_limit = false;
        let mut timeout_retries = 0u8;
        let mut written_end = 0i64;
        let mut downloaded_bytes = offset.unwrap_or(0);
        let mut counted_offsets = HashSet::new();
        let mut first_batch = true;
        let target_str = cancellation_path.display().to_string();
        'download: loop {
            let chunk = resolve_chunk(Some(next).filter(|_| next > 0).or(offset));
            let batch = if stream {
                bound_download_window()
                    .map(|window| window.in_flight)
                    .unwrap_or(STREAM_WINDOW_CHUNKS)
                    .min(width.max(1))
            } else {
                width
            };
            loop {
                if download_cancelled(cancellation_path) {
                    return Err(MtprotoError::Message("cancelled".into()));
                }
                let mut offsets = Vec::with_capacity(batch);
                for step in 0..batch {
                    offsets.push(next + (step as i64) * i64::from(chunk));
                }
                if stream {
                    let seek = offset.unwrap_or(next);
                    let cancelled = cancel_outside_seek_window(seek, chunk, batch, &offsets);
                    if !cancelled.is_empty() {
                        offsets.retain(|off| !cancelled.contains(off));
                    }
                }
                let (flags, cdn_supported) = cdn_fields_for_seek(offset.unwrap_or(0));
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
                        let mut hit_short = false;
                        if let Ok(UploadFile::UploadFile(file)) = &res {
                            let bytes = &file.bytes;
                            if !bytes.is_empty() && bytes.len() <= chunk as usize {
                                use std::io::{Seek, SeekFrom, Write};
                                if out.seek(SeekFrom::Start(part_offset as u64)).is_ok()
                                    && out.write_all(bytes).is_ok()
                                {
                                    written_end = written_end.max(part_offset + bytes.len() as i64);
                                    if counted_offsets.insert(part_offset) {
                                        downloaded_bytes += bytes.len() as i64;
                                        notify_progress(&target_str, downloaded_bytes, 0);
                                    }
                                    if index < written_parts.len() {
                                        written_parts[index] = true;
                                    }
                                }
                            }
                            if bytes.len() < chunk as usize || stream {
                                hit_short = true;
                                last_part = true;
                                if index < short_or_empty.len() {
                                    short_or_empty[index] = true;
                                }
                            }
                        }
                        if stream || hit_short {
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
                                offset.unwrap_or(0),
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
                            out.seek(SeekFrom::Start(part_offset as u64))
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            out.write_all(&bytes)
                                .map_err(|e| MtprotoError::Message(e.to_string()))?;
                            written_end = written_end.max(part_offset + bytes.len() as i64);
                            if counted_offsets.insert(part_offset) {
                                downloaded_bytes += bytes.len() as i64;
                                notify_progress(&target_str, downloaded_bytes, 0);
                            }
                            if bytes.len() < chunk as usize || stream {
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
                                    offset.unwrap_or(0),
                                    next_to_request,
                                    chunk,
                                );
                                continue 'download;
                            }
                            return Err(err);
                        }
                    }
                }
                if last_part || stream {
                    break 'download;
                }
                // A caller may ignore the refill request returned by `on_chunk`.
                // Continue at the first hole instead of the cursor those refills advanced.
                next = first_missing_offset(
                    &counted_offsets,
                    offset.unwrap_or(0),
                    next_to_request,
                    chunk,
                );
            }
        } // 'download
        if !stream {
            if let Some(expected) = media.file_size {
                if expected > 0 && written_end < expected {
                    return Err(MtprotoError::Message(format!(
                        "download incomplete: received {written_end} of {expected} bytes"
                    )));
                }
            }
        }
        out.set_len(written_end as u64)
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        out.flush()
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        Ok(())
    })();
    match result {
        Ok(()) => Ok(dest_path.display().to_string()),
        Err(err) => {
            let _ = fs::remove_file(dest_path);
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
