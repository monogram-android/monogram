//! https://core.telegram.org/method/upload.getFile
//! https://core.telegram.org/api/files

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::{HashSet, HashSetExt};

use tellers_mtproto::latest::api::{UploadFile, UploadGetFileRequest};
use tellers_mtproto_session::Snapshot;

use super::location::{MediaLocation, MediaRef, input_location, media_dc};
use crate::MtprotoError;
use crate::api_invoke;

pub(crate) const DEFAULT_CHUNK: i32 = 128 * 1024;
pub(crate) const CHUNK: i32 = DEFAULT_CHUNK;
pub(crate) const STREAM_WINDOW_CHUNKS: usize = 4;

pub type ProgressCallback = std::sync::Arc<dyn Fn(&str, i64, i64) + Send + Sync + 'static>;
static PROGRESS_CALLBACK: parking_lot::RwLock<Option<ProgressCallback>> =
    parking_lot::RwLock::new(None);

pub fn set_progress_callback(cb: Option<ProgressCallback>) {
    *PROGRESS_CALLBACK.write() = cb;
}

pub fn notify_progress(path: &str, downloaded: i64, total: i64) {
    if let Some(cb) = PROGRESS_CALLBACK.read().as_ref() {
        cb(path, downloaded, total);
    }
}

static CHUNK_SIZE: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(DEFAULT_CHUNK);

pub fn set_chunk_size(size: i32) {
    let valid = matches!(size, 131072 | 262144 | 524288);
    CHUNK_SIZE.store(
        if valid { size } else { DEFAULT_CHUNK },
        std::sync::atomic::Ordering::Relaxed,
    );
}

pub(crate) fn chunk_size() -> i32 {
    CHUNK_SIZE.load(std::sync::atomic::Ordering::Relaxed)
}

fn resolve_chunk(offset: Option<i64>) -> i32 {
    let desired = chunk_size();
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

fn fallback_chunk_after_limit_invalid(chunk: i32) -> Option<i32> {
    if chunk > DEFAULT_CHUNK {
        Some(DEFAULT_CHUNK)
    } else {
        None
    }
}

fn flood_wait_secs(err: &MtprotoError) -> Option<u64> {
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

fn sleep_flood_wait(secs: u64) {
    let dur = if cfg!(test) {
        std::time::Duration::from_millis(1)
    } else {
        std::time::Duration::from_secs(secs.clamp(1, 15))
    };
    std::thread::sleep(dur);
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
            let (flags, cdn_supported) = super::cdn::getfile_cdn_fields();
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
        let width = parts_in_flight.max(1);
        let stream = offset.is_some();
        let mut next = offset.unwrap_or(0);
        let mut retried_limit = false;
        let mut flood_retries = 0u8;
        let mut timeout_retries = 0u8;
        let mut written_end = 0i64;
        let mut downloaded_bytes = offset.unwrap_or(0);
        let mut counted_offsets = HashSet::new();
        let mut first_batch = true;
        let target_str = cancellation_path.display().to_string();
        'download: loop {
            let chunk = resolve_chunk(Some(next).filter(|_| next > 0).or(offset));
            let batch = if stream { STREAM_WINDOW_CHUNKS } else { width };
            loop {
                if download_cancelled(cancellation_path) {
                    return Err(MtprotoError::Message("cancelled".into()));
                }
                let mut offsets = Vec::with_capacity(batch);
                for step in 0..batch {
                    offsets.push(next + (step as i64) * i64::from(chunk));
                }
                let (flags, cdn_supported) = super::cdn::getfile_cdn_fields();
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
                                    let _ = out.flush();
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
                                && fallback_chunk_after_limit_invalid(chunk).is_some() =>
                        {
                            set_chunk_size(DEFAULT_CHUNK);
                            retried_limit = true;
                            continue 'download;
                        }
                        Err(err) if flood_retries < 3 && flood_wait_secs(&err).is_some() => {
                            flood_retries += 1;
                            sleep_flood_wait(flood_wait_secs(&err).unwrap_or(1));
                            continue 'download;
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
                            let _ = out.flush();
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
                            if !retried_limit
                                && is_limit_invalid(&err)
                                && fallback_chunk_after_limit_invalid(chunk).is_some()
                            {
                                set_chunk_size(DEFAULT_CHUNK);
                                retried_limit = true;
                                continue 'download;
                            }
                            if flood_retries < 3 {
                                if let Some(secs) = flood_wait_secs(&err) {
                                    flood_retries += 1;
                                    sleep_flood_wait(secs);
                                    continue 'download;
                                }
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
                next = next_to_request;
            }
        } // 'download
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
