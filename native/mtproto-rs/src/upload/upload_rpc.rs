//! File upload parts and outgoing media.
//! https://core.telegram.org/api/files
//! https://core.telegram.org/method/upload.saveFilePart
//! https://core.telegram.org/method/upload.saveBigFilePart
//! https://core.telegram.org/method/messages.sendMedia
//! https://core.telegram.org/method/messages.sendMultiMedia

use crate::HashMap;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::time::Duration;

use tellers_mtproto::latest::api::{
    Bool, Document, DocumentAttribute, DocumentAttributeFilenameConstructor,
    DocumentAttributeVideoConstructor, InputDocument, InputDocumentConstructor, InputFile,
    InputFileBigConstructor, InputFileConstructor, InputMedia, InputMediaDocumentConstructor,
    InputMediaPhotoConstructor, InputMediaUploadedDocumentConstructor,
    InputMediaUploadedPhotoConstructor, InputPhoto, InputPhotoConstructor, InputSingleMedia,
    InputSingleMediaConstructor, MessageMedia, MessagesSendMediaRequest,
    MessagesSendMultiMediaRequest, MessagesUploadMediaRequest, Photo, True, TrueConstructor,
    Updates, UploadSaveBigFilePartRequest, UploadSaveFilePartRequest, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::media::MediaIndex;
use crate::messages::{self, input_reply_to_thread, random_id};
use crate::peers::{self, CachedPeer, input_peer_from_cached};
use crate::{MessageDto, MtprotoError, UploadItemDto};

pub const FILE_PART_SMALL: usize = 32 * 1024;
pub const FILE_PART_FAST: usize = 512 * 1024;
pub const FILE_PART: usize = FILE_PART_SMALL;

pub fn file_part() -> usize {
    crate::transfer_policy::current().file_part()
}

pub fn set_file_part_kib(kib: i32) {
    let bytes = crate::transfer_policy::normalize_file_part_kib(kib);
    crate::transfer_policy::default_policy().assign_file_part_kib(if bytes == FILE_PART_FAST {
        512
    } else {
        32
    });
    crate::client::visit_policies(|policy| {
        policy.follow_file_part_kib(if bytes == FILE_PART_FAST { 512 } else { 32 });
    });
}
pub const PHOTO_MAX: u64 = 10 * 1024 * 1024;
pub const BIG_FILE_THRESHOLD: u64 = 10 * 1024 * 1024;
pub const ALBUM_MAX: usize = 10;
pub const MAX_PARTS_IN_FLIGHT: usize = 8;

/// Round trip at or above this sends one 32 KiB part.
/// https://core.telegram.org/api/files
pub const SLOW_UPLOAD_RTT: Duration = Duration::from_secs(1);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UploadLimits {
    pub part_size: usize,
    pub in_flight: usize,
}

/// Honor the configured part size with at most eight parts in flight. A tiny
/// file or slow link uses one 32 KiB part at a time.
/// https://core.telegram.org/api/files
pub fn upload_limits(size: u64, rtt: Option<Duration>) -> UploadLimits {
    let slow = rtt.is_some_and(|rtt| rtt >= SLOW_UPLOAD_RTT);
    if slow || size <= FILE_PART_SMALL as u64 {
        UploadLimits {
            part_size: FILE_PART_SMALL,
            in_flight: 1,
        }
    } else {
        UploadLimits {
            part_size: file_part(),
            in_flight: MAX_PARTS_IN_FLIGHT,
        }
    }
}

pub fn is_big_file(size: u64) -> bool {
    size > BIG_FILE_THRESHOLD
}

pub fn part_count(size: u64) -> i32 {
    part_count_for(size, upload_limits(size, None).part_size)
}

fn part_count_for(size: u64, part_size: usize) -> i32 {
    if size == 0 || part_size == 0 {
        0
    } else {
        ((size + part_size as u64 - 1) / part_size as u64) as i32
    }
}

pub fn album_group(kind: &str) -> Option<&'static str> {
    match kind {
        "photo" | "video" => Some("media"),
        "document" => Some("document"),
        _ => None,
    }
}

/// Mixed photo/video + document albums are invalid (`MEDIA_GROUPED_INVALID`).
/// More than 10 items is `MULTI_MEDIA_TOO_LONG`.
pub fn validate_album(kinds: &[&str]) -> Result<(), MtprotoError> {
    if kinds.is_empty() {
        return Err(MtprotoError::Message("empty album".into()));
    }
    if kinds.len() > ALBUM_MAX {
        return Err(MtprotoError::Message("MULTI_MEDIA_TOO_LONG".into()));
    }
    let mut group: Option<&str> = None;
    for kind in kinds {
        let next = album_group(kind)
            .ok_or_else(|| MtprotoError::Message("MEDIA_GROUPED_INVALID".into()))?;
        match group {
            None => group = Some(next),
            Some(existing) if existing != next => {
                return Err(MtprotoError::Message("MEDIA_GROUPED_INVALID".into()));
            }
            Some(_) => {}
        }
    }
    Ok(())
}

pub fn send_uploaded(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    item: &UploadItemDto,
    input: InputFile,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    entities_json: Option<&str>,
) -> Result<MessageDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let media = input_media(item, input)?;
    let random = if item.random_id != 0 {
        item.random_id
    } else {
        random_id()
    };
    let (reply_flag, reply_to) = input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let (_, entities) = messages::entities_from_json(entities_json)?;
    let entities_flag = if entities.is_some() {
        MessagesSendMediaRequest::ENTITIES_FLAG
    } else {
        0
    };
    let request = MessagesSendMediaRequest {
        flags: reply_flag | entities_flag,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(cached)),
        reply_to,
        media: Box::new(media),
        message: item.caption.clone(),
        random_id: random,
        reply_markup: None,
        entities,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
        suggested_post: None,
    };
    let updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(messages::message_from_updates(
        updates,
        chat_id,
        &item.caption,
        media_index,
    ))
}

pub fn send_album(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    items: &[UploadItemDto],
    inputs: &[InputFile],
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    let kinds: Vec<&str> = items.iter().map(|item| item.kind.as_str()).collect();
    validate_album(&kinds)?;
    if inputs.len() != items.len() {
        return Err(MtprotoError::Message("upload parts missing".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let mut multi = Vec::with_capacity(items.len());
    for (item, input) in items.iter().zip(inputs) {
        let uploaded = input_media(item, input.clone())?;
        let media = commit_album_media(snapshot, api_id, cached, uploaded)?;
        let random = if item.random_id != 0 {
            item.random_id
        } else {
            random_id()
        };
        multi.push(Box::new(InputSingleMedia::InputSingleMedia(
            InputSingleMediaConstructor {
                flags: 0,
                media: Box::new(media),
                random_id: random,
                message: item.caption.clone(),
                entities: None,
            },
        )));
    }
    let (reply_flag, reply_to) = input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let request = MessagesSendMultiMediaRequest {
        flags: reply_flag,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(cached)),
        reply_to,
        multi_media: Box::new(Vector::Vector(VectorConstructor {
            field_0: multi.len() as u32,
            field_1: multi,
        })),
        schedule_date: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
    };
    let updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    let collected = match &updates {
        Updates::Updates(u) => messages::all_new_messages(&u.updates, media_index),
        Updates::UpdatesCombined(u) => messages::all_new_messages(&u.updates, media_index),
        _ => Vec::new(),
    };
    if collected.is_empty() {
        Ok(vec![messages::message_from_updates(
            updates,
            chat_id,
            items
                .first()
                .map(|item| item.caption.as_str())
                .unwrap_or(""),
            media_index,
        )])
    } else {
        Ok(collected)
    }
}

pub struct UploadBatch {
    pub file_id: i64,
    pub parts_total: i32,
    pub big: bool,
    pub offset: i32,
    pub bytes: Vec<Vec<u8>>,
}

pub struct UploadStaging {
    file: File,
    file_id: i64,
    big: bool,
    parts_total: i32,
    next: i32,
    part_size: usize,
    in_flight: usize,
}

pub fn open_staging(item: &UploadItemDto) -> Result<UploadStaging, MtprotoError> {
    open_staging_with_rtt(item, None)
}

pub fn open_staging_with_rtt(
    item: &UploadItemDto,
    rtt: Option<Duration>,
) -> Result<UploadStaging, MtprotoError> {
    let path = Path::new(&item.path);
    let size = std::fs::metadata(path)
        .map_err(|e| MtprotoError::Message(e.to_string()))?
        .len();
    if size == 0 {
        return Err(MtprotoError::Message("empty file".into()));
    }
    if item.kind == "photo" && size > PHOTO_MAX {
        return Err(MtprotoError::Message("photo too large".into()));
    }
    let mut limits = upload_limits(size, rtt);
    let max_parts = crate::transfer_policy::current().upload_max_parts();
    // Slow-network chunks must still fit the account's server part-count limit.
    if part_count_for(size, limits.part_size) > max_parts {
        limits.part_size = FILE_PART_FAST;
    }
    let parts_total = part_count_for(size, limits.part_size);
    if parts_total <= 0 || parts_total > max_parts {
        return Err(MtprotoError::Message("file too large".into()));
    }
    Ok(UploadStaging {
        file: File::open(path).map_err(|e| MtprotoError::Message(e.to_string()))?,
        file_id: random_id(),
        big: is_big_file(size),
        parts_total,
        next: 0,
        part_size: limits.part_size,
        in_flight: limits.in_flight,
    })
}

impl UploadStaging {
    pub fn is_complete(&self) -> bool {
        self.next >= self.parts_total
    }

    pub fn in_flight(&self) -> usize {
        self.in_flight.max(1)
    }

    pub fn next_batch(&mut self, width: usize) -> Option<Result<UploadBatch, MtprotoError>> {
        if self.is_complete() {
            return None;
        }
        let end = (self.next + width.max(1) as i32).min(self.parts_total);
        let mut bytes = Vec::with_capacity((end - self.next) as usize);
        for _ in self.next..end {
            match read_part(&mut self.file, self.part_size) {
                Ok(part) => bytes.push(part),
                Err(err) => return Some(Err(err)),
            }
        }
        let batch = UploadBatch {
            file_id: self.file_id,
            parts_total: self.parts_total,
            big: self.big,
            offset: self.next,
            bytes,
        };
        self.next = end;
        Some(Ok(batch))
    }
}

pub fn save_batch(
    snapshot: &mut Snapshot,
    api_id: i32,
    batch: &UploadBatch,
) -> Result<(), MtprotoError> {
    crate::request_control::check()
        .map_err(|_| MtprotoError::Message("request cancelled".into()))?;
    let accepted = if batch.big {
        let requests: Vec<UploadSaveBigFilePartRequest> = batch
            .bytes
            .iter()
            .enumerate()
            .map(|(index, bytes)| UploadSaveBigFilePartRequest {
                file_id: batch.file_id,
                file_part: batch.offset + index as i32,
                file_total_parts: batch.parts_total,
                bytes: bytes.clone(),
            })
            .collect();
        api_invoke::invoke_api_batch_without_updates::<_, Bool>(snapshot, api_id, requests)?
    } else {
        let requests: Vec<UploadSaveFilePartRequest> = batch
            .bytes
            .iter()
            .enumerate()
            .map(|(index, bytes)| UploadSaveFilePartRequest {
                file_id: batch.file_id,
                file_part: batch.offset + index as i32,
                bytes: bytes.clone(),
            })
            .collect();
        api_invoke::invoke_api_batch_without_updates::<_, Bool>(snapshot, api_id, requests)?
    };
    for result in accepted {
        match result {
            Ok(Bool::BoolTrue(_)) => {}
            Ok(_) => return Err(MtprotoError::Message("upload part rejected".into())),
            Err(err) => return Err(err),
        }
    }
    Ok(())
}

pub fn staged_input_file(item: &UploadItemDto, staging: &UploadStaging) -> InputFile {
    let name = file_name(item);
    if staging.big {
        InputFile::InputFileBig(InputFileBigConstructor {
            id: staging.file_id,
            parts: staging.parts_total,
            name,
        })
    } else {
        InputFile::InputFile(InputFileConstructor {
            id: staging.file_id,
            parts: staging.parts_total,
            name,
            md5_checksum: String::new(),
        })
    }
}

pub fn is_missing_file_part(err: &MtprotoError) -> bool {
    let MtprotoError::Message(msg) = err else {
        return false;
    };
    msg.contains("FILE_PART")
}

fn read_part(file: &mut File, part_size: usize) -> Result<Vec<u8>, MtprotoError> {
    let mut buf = vec![0_u8; part_size];
    let mut filled = 0usize;
    while filled < buf.len() {
        let n = file
            .read(&mut buf[filled..])
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
        if n == 0 {
            break;
        }
        filled += n;
    }
    if filled == 0 {
        return Err(MtprotoError::Message("upload truncated".into()));
    }
    buf.truncate(filled);
    Ok(buf)
}

fn file_name(item: &UploadItemDto) -> String {
    if !item.file_name.trim().is_empty() {
        return item.file_name.clone();
    }
    Path::new(&item.path)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string()
}

fn mime_type(item: &UploadItemDto) -> String {
    if !item.mime_type.trim().is_empty() {
        return item.mime_type.clone();
    }
    match item.kind.as_str() {
        "photo" => "image/jpeg".into(),
        "video" => "video/mp4".into(),
        _ => "application/octet-stream".into(),
    }
}

fn commit_album_media(
    snapshot: &mut Snapshot,
    api_id: i32,
    cached: &CachedPeer,
    uploaded: InputMedia,
) -> Result<InputMedia, MtprotoError> {
    let media: MessageMedia = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesUploadMediaRequest {
            flags: 0,
            business_connection_id: None,
            peer: Box::new(input_peer_from_cached(cached)),
            media: Box::new(uploaded),
        },
    )?;
    match &media {
        MessageMedia::MessageMediaPhoto(p) => {
            let Some(Photo::Photo(ph)) = p.photo.as_ref().map(|inner| inner.as_ref()) else {
                return Err(MtprotoError::Message("MEDIA_INVALID".into()));
            };
            Ok(InputMedia::InputMediaPhoto(InputMediaPhotoConstructor {
                flags: 0,
                spoiler: None,
                live_photo: None,
                id: Box::new(InputPhoto::InputPhoto(InputPhotoConstructor {
                    id: ph.id,
                    access_hash: ph.access_hash,
                    file_reference: ph.file_reference.clone(),
                })),
                ttl_seconds: None,
                video: None,
            }))
        }
        MessageMedia::MessageMediaDocument(d) => {
            let Some(Document::Document(doc)) = d.document.as_ref().map(|inner| inner.as_ref())
            else {
                return Err(MtprotoError::Message("MEDIA_INVALID".into()));
            };
            Ok(InputMedia::InputMediaDocument(
                InputMediaDocumentConstructor {
                    flags: 0,
                    spoiler: None,
                    id: Box::new(InputDocument::InputDocument(InputDocumentConstructor {
                        id: doc.id,
                        access_hash: doc.access_hash,
                        file_reference: doc.file_reference.clone(),
                    })),
                    video_cover: None,
                    video_timestamp: None,
                    ttl_seconds: None,
                    query: None,
                },
            ))
        }
        _ => Err(MtprotoError::Message("MEDIA_INVALID".into())),
    }
}

fn input_media(item: &UploadItemDto, file: InputFile) -> Result<InputMedia, MtprotoError> {
    match item.kind.as_str() {
        "photo" => Ok(InputMedia::InputMediaUploadedPhoto(
            InputMediaUploadedPhotoConstructor {
                flags: 0,
                spoiler: None,
                live_photo: None,
                file: Box::new(file),
                stickers: None,
                ttl_seconds: None,
                video: None,
            },
        )),
        "video" => Ok(uploaded_document(item, file, false)),
        "document" => Ok(uploaded_document(item, file, true)),
        other => Err(MtprotoError::Message(format!(
            "unsupported upload kind {other}"
        ))),
    }
}

fn uploaded_document(item: &UploadItemDto, file: InputFile, force_file: bool) -> InputMedia {
    let mut attributes = vec![Box::new(DocumentAttribute::DocumentAttributeFilename(
        DocumentAttributeFilenameConstructor {
            file_name: file_name(item),
        },
    ))];
    if item.kind == "video" {
        let streaming = Box::new(True::True(TrueConstructor {}));
        attributes.push(Box::new(DocumentAttribute::DocumentAttributeVideo(
            DocumentAttributeVideoConstructor {
                flags: DocumentAttributeVideoConstructor::SUPPORTS_STREAMING_FLAG,
                round_message: None,
                supports_streaming: Some(streaming),
                nosound: None,
                duration: f64::from(item.duration.max(0)),
                w: item.width.max(0),
                h: item.height.max(0),
                preload_prefix_size: None,
                video_start_ts: None,
                video_codec: None,
            },
        )));
    }
    let force = if force_file {
        Some(Box::new(True::True(TrueConstructor {})))
    } else {
        None
    };
    InputMedia::InputMediaUploadedDocument(InputMediaUploadedDocumentConstructor {
        flags: if force_file {
            InputMediaUploadedDocumentConstructor::FORCE_FILE_FLAG
        } else {
            0
        },
        nosound_video: None,
        force_file: force,
        spoiler: None,
        file: Box::new(file),
        thumb: None,
        mime_type: mime_type(item),
        attributes: Box::new(Vector::Vector(VectorConstructor {
            field_0: attributes.len() as u32,
            field_1: attributes,
        })),
        stickers: None,
        video_cover: None,
        video_timestamp: None,
        ttl_seconds: None,
    })
}

#[cfg(test)]
#[path = "../../tests/unit/upload_upload_rpc_tests.rs"]
mod tests;
