use std::path::Path;

use tellers_mtproto::latest::api::{CdnConfig, CdnPublicKey, FileHash, UploadCdnFile, Vector};
use tellers_mtproto_session::OsRandom;
use tellers_mtproto_transport::PaddedIntermediate;

use crate::MtprotoError;
use crate::api_invoke;
use crate::auth::auth_key::create_auth_key_with_pem;
use crate::dialogs;
use crate::media;
use crate::peers::vector_boxed_items;
use crate::tcp;

use super::*;

#[path = "peer_avatar.rs"]
mod peer_avatar;

use peer_avatar::reindex_peer_avatar;

#[cfg(test)]
#[path = "../../tests/unit/cdn_transfer_tests.rs"]
mod cdn_transfer_tests;

include!("media_lane.rs");


fn home_lease_ok(state: &ClientState, home_session_id: i64) -> bool {
    state.snapshot.session_id == home_session_id || state.permanent.as_ref().is_some_and(|snapshot| snapshot.session_id == home_session_id)
}

fn download_temp_ready(
    io: &super::lanes::SessionIo,
    client: &Client,
    dc: i32,
    home_key: Option<&[u8]>,
) -> bool {
    if !crate::auth::bind_temp::temp_key_valid(&io.snapshot, io.temp_expires_at) || io.snapshot.dc_id != dc {
        return false;
    }
    let Some(lane_key) = io.snapshot.auth_key.as_deref() else {
        return false;
    };
    if Some(lane_key) == home_key {
        return false;
    }
    let Some(perm) = io.perm_key.as_deref().filter(|key| key.len() == 256) else {
        return false;
    };
    let data = client.data.lock();
    crate::auth::bind_temp::dc_permanent_key(&data, dc) == Some(perm)
}

fn stage_for(dest: &Path, offset: Option<i64>) -> Result<media::StagedDownload, MtprotoError> {
    if offset.is_none() {
        media::StagedDownload::resume_or_create(dest)
    } else {
        media::StagedDownload::new(dest)
    }
}

fn finish_staged(
    staged: media::StagedDownload,
    result: Result<String, MtprotoError>,
    client: &Client,
    session_id: i64,
    dest: &Path,
) -> Result<String, MtprotoError> {
    match result {
        Ok(_) => publish_media(client, session_id, staged, dest),
        Err(err) if media::keep_partial(&err) || media::stopped_download(&err) => {
            staged.keep();
            Err(err)
        }
        Err(err) => Err(err),
    }
}

pub(crate) struct MediaLanePreparation {
    request_id: u64,
    worker: std::thread::JoinHandle<bool>,
}

impl MediaLanePreparation {
    pub(crate) fn start(prepare: impl FnOnce() -> bool + Send + 'static) -> Self {
        let request_id = crate::request_control::create();
        let worker = std::thread::spawn(move || {
            struct Release(u64);
            impl Drop for Release {
                fn drop(&mut self) { crate::request_control::release(self.0); }
            }
            let _release = Release(request_id);
            crate::request_control::with_shared_binding(request_id, prepare)
        });
        Self { request_id, worker }
    }

    pub(crate) fn finished(&self) -> bool { self.worker.is_finished() }
}

impl Drop for MediaLanePreparation {
    fn drop(&mut self) { crate::request_control::cancel(self.request_id); }
}

pub fn download_wallpaper(
    handle: u64,
    id: i64,
    access_hash: i64,
    dest_path: String,
) -> Result<String, MtprotoError> {
    let client = get_client(handle)?;
    let (api_id, home, media) = with_client_mut(handle, |state| {
        let media = call_with_migrate(state, |state| {
            crate::media::wallpaper_rpc::wallpaper_media(
                &mut state.snapshot,
                state.api_id,
                id,
                access_hash,
            )
        })?;
        persist(state)?;
        Ok((state.api_id, state.snapshot.clone(), media))
    })?;
    match download_media_on_lane(
        handle,
        &client,
        &home,
        api_id,
        &media,
        Path::new(&dest_path),
    ) {
        Err(error) if media::is_file_reference_error(&error) => {
            let refreshed = with_client_mut(handle, |state| {
                if !home_lease_ok(state, home.session_id) {
                    return Err(expired_session_lease());
                }
                let media = call_with_migrate(state, |state| {
                    crate::media::wallpaper_rpc::wallpaper_media(
                        &mut state.snapshot,
                        state.api_id,
                        id,
                        access_hash,
                    )
                })?;
                persist(state)?;
                Ok(media)
            })?;
            download_media_on_lane(
                handle,
                &client,
                &home,
                api_id,
                &refreshed,
                Path::new(&dest_path),
            )
        }
        result => result,
    }
}

pub(crate) fn is_peer_refreshable(err: &MtprotoError) -> bool {
    matches!(
        err,
        MtprotoError::Message(message)
            if message.contains("unknown peer") || message.contains("PEER_ID_INVALID")
    )
}

pub(crate) fn refresh_dialogs(state: &mut ClientState) -> Result<(), MtprotoError> {
    dialogs::get_dialogs(
        &mut state.snapshot,
        state.api_id,
        &mut state.peers,
        &mut state.media,
        &mut state.channel_pts,
        0,
        0,
        0,
        None,
    )
    .map(|_| ())
}

pub(crate) fn refresh_file_source(
    state: &mut ClientState,
    chat_id: i64,
    message_id: i32,
) -> Result<(), MtprotoError> {
    let before = state
        .media
        .get(&(chat_id, message_id))
        .map(|media| media::location_token(&media.location));
    if let Some(url) = crate::instant_view::media_refresh_url(&state.media, chat_id, message_id) {
        let _ = crate::instant_view::get_web_page(
            &mut state.snapshot,
            state.api_id,
            &mut state.peers,
            &mut state.media,
            url,
            0,
        );
        return Ok(());
    }
    if message_id == crate::instant_view::INSTANT_VIEW_MEDIA_MSG {
        return Ok(());
    }
    if message_id == 0 {
        if !state.media.contains_key(&(chat_id, message_id)) {
            reindex_peer_avatar(state, chat_id);
            return Ok(());
        }
        let peer_photo = state
            .media
            .get(&(chat_id, message_id))
            .is_some_and(|media| matches!(media.location, media::MediaLocation::PeerPhoto { .. }));
        if peer_photo {
            let _ = refresh_dialogs(state);
            let _ = crate::profile::get_profile(
                &mut state.snapshot,
                state.api_id,
                &state.peers,
                &mut state.media,
                state.user_id,
                chat_id,
            );
            media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
        } else if state
            .media
            .get(&(chat_id, message_id))
            .is_some_and(|media| matches!(media.location, media::MediaLocation::Document { .. }))
        {
            let sticker = state
                .media
                .get(&(chat_id, message_id))
                .and_then(sticker_set_of);
            if let Some((set_id, access_hash)) = sticker {
                // Pack stickers have no message id. messages.getStickerSet is the
                // constructor that reissues their file_reference.
                // https://core.telegram.org/api/file-references
                let _ = crate::messages::sticker_rpc::get_sticker_set(
                    &mut state.snapshot,
                    state.api_id,
                    &mut state.media,
                    set_id,
                    access_hash,
                );
            } else {
                let _ = crate::client::extras_rpc::get_saved_gifs(
                    &mut state.snapshot,
                    state.api_id,
                    &mut state.media,
                    0,
                );
            }
        } else if state
            .media
            .get(&(chat_id, message_id))
            .is_some_and(|media| matches!(media.location, media::MediaLocation::Photo { .. }))
        {
            refresh_last_inline(state);
        }
        return Ok(());
    }
    dialogs::refresh_message_media(
        &mut state.snapshot,
        state.api_id,
        &state.peers,
        &mut state.media,
        chat_id,
        message_id,
    )?;
    let after = state
        .media
        .get(&(chat_id, message_id))
        .map(|media| media::location_token(&media.location));
    if before.is_none() || before == after {
        let _ = history_with_peer_refresh(state, chat_id, 3, message_id, 0, -1);
    }
    Ok(())
}

pub(crate) fn refresh_last_inline(state: &mut ClientState) {
    let Some(query) = state.last_inline.clone() else {
        return;
    };
    if query
        .last_refresh
        .is_some_and(|at| at.elapsed() < std::time::Duration::from_secs(2))
    {
        return;
    }
    if crate::messages::inline_rpc::get_inline_bot_results(
        &mut state.snapshot,
        state.api_id,
        &mut state.peers,
        &mut state.media,
        query.chat_id,
        query.bot_id,
        &query.query,
        &query.offset,
    )
    .is_ok()
    {
        if let Some(stored) = state.last_inline.as_mut() {
            stored.last_refresh = Some(std::time::Instant::now());
        }
    }
}

pub fn download_message_media(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    dest_path: String,
    kind: media::MediaDownloadKind,
) -> Result<String, MtprotoError> {
    download_message_media_range(handle, chat_id, message_id, dest_path, kind, None)
}

/// Shared-index read of an inline stripped/cached JPEG. No RPC and no main-session lock.
pub fn peek_message_inline_thumb(handle: u64, chat_id: i64, message_id: i32) -> Option<Vec<u8>> {
    let client = get_client(handle).ok()?;
    let data = client.data.lock();
    let indexed = data.media.get(&(chat_id, message_id))?;
    media::inline_thumb_jpeg(indexed)
}

/// Shared-index read of an inline stripped/cached avatar JPEG for a peer (user or chat).
pub fn peek_peer_inline_thumb(handle: u64, peer_id: i64) -> Option<Vec<u8>> {
    peek_message_inline_thumb(handle, peer_id, 0)
}

pub(crate) fn indexed_media_for_download(
    data: &ClientData,
    chat_id: i64,
    message_id: i32,
    kind: media::MediaDownloadKind,
) -> Option<Result<(i32, Snapshot, media::MediaRef), MtprotoError>> {
    let indexed = data.media.get(&(chat_id, message_id))?;
    if media::inline_payload_missing(indexed) {
        return None;
    }
    if data.session_dead
        || data.user_id.is_none()
        || media::peer_photo_needs_hash(indexed)
        || (message_id == 0
            && kind == media::MediaDownloadKind::Full
            && data.peers.contains_key(&chat_id)
            && media::needs_video_avatar_upgrade(indexed))
    {
        return None;
    }
    let home = home_fork_source(data)?;
    Some(media::media_for_download(indexed, kind).map(|media| (data.api_id, home, media)))
}

pub fn download_message_media_range(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    dest_path: String,
    kind: media::MediaDownloadKind,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    let client = get_client(handle)?;
    let _policy = crate::transfer_policy::bind(std::sync::Arc::clone(&client.policy));
    schedule_transfer_config(handle);
    let profile = match kind {
        media::MediaDownloadKind::Thumb => media::DownloadProfile::Thumb,
        media::MediaDownloadKind::Full if offset.is_some() => media::DownloadProfile::Playing,
        _ if media::request_profile().is_some() => media::request_profile().unwrap(),
        media::MediaDownloadKind::Display => media::DownloadProfile::Visible,
        media::MediaDownloadKind::Full
            if crate::scheduler::current_class()
                == crate::scheduler::RequestClass::BackgroundMedia =>
        {
            media::DownloadProfile::Background
        }
        media::MediaDownloadKind::Full => media::DownloadProfile::User,
    };
    let _window = media::bind_download_profile(profile);
    let cached = {
        let _span = crate::perf::span("media_lookup_shared_index");
        let data = client.data.lock();
        indexed_media_for_download(&data, chat_id, message_id, kind)
    };
    let (api_id, home, media) = if let Some(cached) = cached {
        crate::perf::count("media.lookup.shared_index");
        cached?
    } else {
        let _span = crate::perf::span("media_lookup_main_fallback");
        crate::perf::count("media.lookup.main_fallback");
        with_client_mut(handle, |state| {
            ensure_ready(state)?;
            if !state.media.contains_key(&(chat_id, message_id)) {
                if message_id == crate::instant_view::INSTANT_VIEW_MEDIA_MSG {
                    // Indexed only by messages.getWebPage as (media_id, -1).
                    // Never treat a photo/document id as a dialog for history.
                } else if message_id == 0 {
                    // `(document_id, 0)` is also a sticker or GIF. peer_avatar_fetch
                    // skips those ids so get_profile cannot overwrite a user avatar.
                    reindex_peer_avatar(state, chat_id);
                } else {
                    let _ = call_with_migrate(state, |state| {
                        with_peer_refresh(state, chat_id, |state| {
                            refresh_file_source(state, chat_id, message_id)
                        })
                    });
                    if !state.media.contains_key(&(chat_id, message_id)) {
                        let _ = call_with_migrate(state, |state| {
                            history_with_peer_refresh(state, chat_id, 3, message_id, 0, -1)
                        });
                    }
                }
            }
            if state
                .media
                .get(&(chat_id, message_id))
                .is_some_and(media::peer_photo_needs_hash)
            {
                let _ = call_with_migrate(state, refresh_dialogs);
                media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
            }
            if message_id == 0
                && kind == media::MediaDownloadKind::Full
                && state.peers.contains_key(&chat_id)
                && state
                    .media
                    .get(&(chat_id, 0))
                    .is_some_and(media::needs_video_avatar_upgrade)
            {
                let _ = call_with_migrate(state, |state| {
                    with_peer_refresh(state, chat_id, |state| {
                        crate::profile::get_profile(
                            &mut state.snapshot,
                            state.api_id,
                            &state.peers,
                            &mut state.media,
                            state.user_id,
                            chat_id,
                        )
                    })
                });
            }
            let mut indexed = state
                .media
                .get(&(chat_id, message_id))
                .filter(|media| !media::inline_payload_missing(media))
                .cloned();
            if indexed.is_none() {
                // Local index gap (cold history, cleared cache, peer hash expired):
                // re-fetch this message's media instead of failing the download outright.
                let _ = crate::rpc::with_rpc_timeout_secs(20, || {
                    call_with_migrate(state, |state| {
                        with_peer_refresh(state, chat_id, |state| {
                            refresh_file_source(state, chat_id, message_id)
                        })
                    })
                });
                indexed = state.media.get(&(chat_id, message_id)).cloned();
            }
            let indexed = indexed.ok_or_else(|| {
                MtprotoError::Message(format!("no media for chat {chat_id} message {message_id}"))
            })?;
            Ok((
                state.api_id,
                state.snapshot.clone(),
                media::media_for_download(&indexed, kind)?,
            ))
        })?
    };
    let dest = Path::new(&dest_path);
    let first = download_media_range_on_lane(handle, &client, &home, api_id, &media, dest, offset);
    match first {
        Ok(path) => Ok(path),
        Err(err)
            if kind == media::MediaDownloadKind::Display && media::is_file_id_invalid(&err) =>
        {
            Err(MtprotoError::Message("no display size".into()))
        }
        Err(err)
            if media::is_file_reference_error(&err)
                || thumb_file_id_can_refresh(kind, &media, message_id, &err) =>
        {
            let refreshed = with_client_mut(handle, |state| {
                if !home_lease_ok(state, home.session_id) {
                    return Err(expired_session_lease());
                }
                crate::rpc::with_rpc_timeout_secs(20, || {
                    call_with_migrate(state, |state| {
                        refresh_file_source(state, chat_id, message_id)
                    })
                })?;
                persist(state)?;
                let indexed = state.media.get(&(chat_id, message_id)).cloned().ok_or_else(
                    || {
                        MtprotoError::Message(format!(
                            "no media after file-ref refresh for chat {chat_id} message {message_id}"
                        ))
                    },
                )?;
                media::media_for_download(&indexed, kind)
            });
            let Ok(new_media) = refreshed else {
                return Err(err);
            };
            // Always retry getFile once after the source refetch, even if the
            // token looks unchanged (server may have renewed the same bytes).
            // https://core.telegram.org/api/file-references
            match download_thumb_with_size_fallback(
                handle, &client, &home, api_id, chat_id, message_id, kind, &new_media, dest, offset,
            ) {
                Err(retry_err)
                    if kind == media::MediaDownloadKind::Thumb
                        && media::is_file_id_invalid(&retry_err) =>
                {
                    Err(MtprotoError::Message("no downloadable thumb".into()))
                }
                other => other,
            }
        }
        Err(err) if kind == media::MediaDownloadKind::Thumb && media::is_file_id_invalid(&err) => {
            match try_alternate_thumb_sizes(
                handle, &client, &home, api_id, chat_id, message_id, &media, dest, offset,
            ) {
                Err(retry_err) if media::is_file_id_invalid(&retry_err) => {
                    Err(MtprotoError::Message("no downloadable thumb".into()))
                }
                other => other,
            }
        }
        Err(err)
            if kind == media::MediaDownloadKind::Full
                && media::is_video_avatar(&media)
                && media::is_file_id_invalid(&err) =>
        {
            try_alternate_video_avatar_sizes(
                handle, &client, &home, api_id, chat_id, message_id, &media, dest, offset,
            )
        }
        Err(err) => Err(err),
    }
}

pub fn download_custom_emoji(
    handle: u64,
    document_id: i64,
    dest_path: String,
) -> Result<String, MtprotoError> {
    let client = get_client(handle)?;
    let (api_id, fallback, media) = with_client_mut(handle, |state| {
        ensure_ready(state)?;
        if let Some(indexed) = state.media.get(&(document_id, 0)).cloned() {
            return Ok((state.api_id, state.snapshot.clone(), indexed));
        }
        let indexed = index_missing_custom_emoji(state, document_id)?;
        Ok((state.api_id, state.snapshot.clone(), indexed))
    })?;
    // A bound temp key leaves snapshot.session_id != home_session_id. Media
    // leases require the permanent id, the same source message downloads use.
    let home = {
        let data = client.data.lock();
        home_fork_source(&data).unwrap_or(fallback)
    };
    match download_media_on_lane(
        handle,
        &client,
        &home,
        api_id,
        &media,
        Path::new(&dest_path),
    ) {
        Err(error) if media::is_file_reference_error(&error) => {
            let refreshed = with_client_mut(handle, |state| {
                if !home_lease_ok(state, home.session_id) {
                    return Err(expired_session_lease());
                }
                refresh_document_file_reference(state, document_id)?;
                persist(state)?;
                state.media.get(&(document_id, 0)).cloned().ok_or_else(|| {
                    MtprotoError::Message(format!(
                        "no media after file-ref refresh for document {document_id}"
                    ))
                })
            })?;
            download_media_on_lane(
                handle,
                &client,
                &home,
                api_id,
                &refreshed,
                Path::new(&dest_path),
            )
        }
        result => result,
    }
}

/// Re-fetch the constructor that issued this document's `file_reference`.
/// Stickers come from `messages.getStickerSet`; custom emoji and other
/// documents come from `messages.getCustomEmojiDocuments`.
/// Pack stickers carry the set that can reissue `file_reference`. Saved GIFs do not.
pub(crate) fn sticker_set_of(media: &media::MediaRef) -> Option<(i64, i64)> {
    media.sticker_set_id.zip(media.sticker_set_access_hash)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CustomEmojiDocumentSource {
    StickerSet { set_id: i64, access_hash: i64 },
    CustomEmojiDocuments,
}

pub(crate) fn custom_emoji_document_source(
    index: &media::MediaIndex,
    document_id: i64,
) -> CustomEmojiDocumentSource {
    match sticker_set_for_document(index, document_id) {
        Some((set_id, access_hash)) => {
            CustomEmojiDocumentSource::StickerSet { set_id, access_hash }
        }
        None => CustomEmojiDocumentSource::CustomEmojiDocuments,
    }
}

fn document_location_id(item: &media::MediaRef) -> Option<i64> {
    match item.location {
        media::MediaLocation::Document { id, .. } => Some(id),
        _ => None,
    }
}

fn sticker_set_for_document(
    index: &media::MediaIndex,
    document_id: i64,
) -> Option<(i64, i64)> {
    if let Some(set) = index.get(&(document_id, 0)).and_then(sticker_set_of) {
        return Some(set);
    }
    index.iter().find_map(|(&(key_id, key_msg), item)| {
        let same_document = (key_id == document_id && key_msg == 0)
            || document_location_id(item) == Some(document_id);
        if same_document {
            sticker_set_of(item)
        } else {
            None
        }
    })
}

fn index_missing_custom_emoji(
    state: &mut ClientState,
    document_id: i64,
) -> Result<media::MediaRef, MtprotoError> {
    match custom_emoji_document_source(&state.media, document_id) {
        CustomEmojiDocumentSource::StickerSet { set_id, access_hash } => {
            crate::messages::sticker_rpc::get_sticker_set(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                set_id,
                access_hash,
            )?;
            persist(state)?;
            state.media.get(&(document_id, 0)).cloned().ok_or_else(|| {
                MtprotoError::Message(format!(
                    "no sticker document {document_id} after getStickerSet"
                ))
            })
        }
        CustomEmojiDocumentSource::CustomEmojiDocuments => {
            let indexed = call_with_migrate(state, |state| {
                media::fetch_custom_emoji(&mut state.snapshot, state.api_id, document_id)
            })?;
            media::insert_media(&mut state.media, (document_id, 0), indexed.clone());
            persist(state)?;
            Ok(indexed)
        }
    }
}

fn refresh_document_file_reference(
    state: &mut ClientState,
    document_id: i64,
) -> Result<(), MtprotoError> {
    match custom_emoji_document_source(&state.media, document_id) {
        CustomEmojiDocumentSource::StickerSet { set_id, access_hash } => {
            crate::messages::sticker_rpc::get_sticker_set(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                set_id,
                access_hash,
            )?;
            Ok(())
        }
        CustomEmojiDocumentSource::CustomEmojiDocuments => {
            let indexed = call_with_migrate(state, |state| {
                media::fetch_custom_emoji(&mut state.snapshot, state.api_id, document_id)
            })?;
            media::insert_media(&mut state.media, (document_id, 0), indexed);
            Ok(())
        }
    }
}

pub(crate) fn download_media_on_lane(
    handle: u64,
    client: &Client,
    home: &tellers_mtproto_session::Snapshot,
    api_id: i32,
    media: &media::MediaRef,
    dest: &Path,
) -> Result<String, MtprotoError> {
    download_media_range_on_lane(handle, client, home, api_id, media, dest, None)
}

pub(crate) fn thumb_file_id_can_refresh(
    kind: media::MediaDownloadKind,
    media: &media::MediaRef,
    message_id: i32,
    err: &MtprotoError,
) -> bool {
    kind == media::MediaDownloadKind::Thumb
        && media::is_file_id_invalid(err)
        && (message_id != 0
            || matches!(
                media.location,
                media::MediaLocation::Document { .. } | media::MediaLocation::Photo { .. }
            ))
}

pub(crate) fn download_thumb_with_size_fallback(
    handle: u64,
    client: &Client,
    home: &tellers_mtproto_session::Snapshot,
    api_id: i32,
    chat_id: i64,
    message_id: i32,
    kind: media::MediaDownloadKind,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    match download_media_range_on_lane(handle, client, home, api_id, media, dest, offset) {
        Ok(path) => Ok(path),
        Err(err) if kind == media::MediaDownloadKind::Thumb && media::is_file_id_invalid(&err) => {
            try_alternate_thumb_sizes(
                handle, client, home, api_id, chat_id, message_id, media, dest, offset,
            )
        }
        other => other,
    }
}

pub(crate) fn try_alternate_thumb_sizes(
    handle: u64,
    client: &Client,
    home: &tellers_mtproto_session::Snapshot,
    api_id: i32,
    chat_id: i64,
    message_id: i32,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    let Some(current) = media::location_thumb_size(&media.location) else {
        return Err(MtprotoError::Message("no downloadable thumb".into()));
    };
    let mut last = MtprotoError::Message("no downloadable thumb".into());
    for ty in media::next_getfile_thumb_sizes(current) {
        let Some(loc) = media::with_thumb_size(&media.location, ty) else {
            break;
        };
        let mut alt = media.clone();
        alt.location = loc;
        match download_media_range_on_lane(handle, client, home, api_id, &alt, dest, offset) {
            Ok(path) => {
                let working = alt.location.clone();
                let _ = with_client_mut(handle, |state| {
                    if let Some(indexed) = state.media.get_mut(&(chat_id, message_id)) {
                        media::remember_thumb_location(indexed, working);
                    }
                    Ok(())
                });
                return Ok(path);
            }
            Err(err) if media::is_file_id_invalid(&err) => {
                last = err;
            }
            Err(err) => return Err(err),
        }
    }
    Err(last)
}

pub(crate) fn try_alternate_video_avatar_sizes(
    handle: u64,
    client: &Client,
    home: &tellers_mtproto_session::Snapshot,
    api_id: i32,
    chat_id: i64,
    message_id: i32,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    let Some(current) = media::location_thumb_size(&media.location) else {
        return Err(MtprotoError::Message("no video avatar size".into()));
    };
    let mut last = MtprotoError::Message("no video avatar size".into());
    for ty in media::next_video_avatar_sizes(current) {
        let Some(loc) = media::with_thumb_size(&media.location, ty) else {
            break;
        };
        let mut alt = media.clone();
        alt.location = loc;
        match download_media_range_on_lane(handle, client, home, api_id, &alt, dest, offset) {
            Ok(path) => {
                let working = alt.location.clone();
                let _ = with_client_mut(handle, |state| {
                    if let Some(indexed) = state.media.get_mut(&(chat_id, message_id)) {
                        indexed.location = working;
                    }
                    Ok(())
                });
                return Ok(path);
            }
            Err(err) if media::is_file_id_invalid(&err) => {
                last = err;
            }
            Err(err) => return Err(err),
        }
    }
    Err(last)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MediaLanePrep {
    /// Same DC + usable auth: keep the live TCP, only sync salt/time.
    Reuse,
    /// Home DC after a foreign-DC auth: new forked home session, drop TCP.
    Replaced,
    /// Foreign DC still on home auth (or wrong DC): exportAuthorization.
    NeedExport,
}

/// Home-DC downloads reuse a forked session (same auth, new session_id).
/// Other DCs need a distinct exported auth key. Never use the main RPC socket.
pub(crate) fn prepare_media_lane_snapshot(
    lane: &mut Snapshot,
    home: &Snapshot,
    target_dc: i32,
) -> MediaLanePrep {
    if target_dc == home.dc_id {
        if lane.dc_id != home.dc_id || lane.auth_key != home.auth_key {
            *lane = fork_session(home);
            MediaLanePrep::Replaced
        } else {
            lane.server_salt = home.server_salt;
            lane.time_offset_micros = home.time_offset_micros;
            MediaLanePrep::Reuse
        }
    } else if lane.dc_id != target_dc || lane.auth_key == home.auth_key {
        MediaLanePrep::NeedExport
    } else {
        lane.time_offset_micros = home.time_offset_micros;
        MediaLanePrep::Reuse
    }
}

pub(crate) fn download_media_range_on_lane(
    handle: u64,
    client: &Client,
    home: &tellers_mtproto_session::Snapshot,
    api_id: i32,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    let _policy = crate::transfer_policy::bind(std::sync::Arc::clone(&client.policy));
    let _ordinary = if media::bound_download_window().is_none() {
        Some(media::bind_download_profile(
            media::DownloadProfile::Ordinary,
        ))
    } else {
        None
    };
    if media::location_is_inline(&media.location) {
        return media::download_media_range_with_fetch(
            home.dc_id,
            media,
            dest,
            dest,
            offset,
            |_| {
                Err(MtprotoError::Message(
                    "inline media does not need upload.getFile".into(),
                ))
            },
        );
    }
    let mut target_dc = media::download_dc(home.dc_id, &media.location);
    let large = media.file_size.is_some_and(|size| size >= 20_000_000);
    let mut migrations = 0u8;
    let mut flood_resumes = 0u8;
    let mut routed_media = media.clone();
    let media = &mut routed_media;
    loop {
        if let Err(error) = media::check_transfer_flood(target_dc, media::TransferClass::Download) {
            match media::transfer_step(&error, target_dc, migrations, flood_resumes) {
                media::TransferStep::ResumeFlood => {
                    let seconds = media::flood_wait_secs(&error).unwrap_or(1);
                    if !wait_flood(dest, seconds) {
                        return Err(MtprotoError::Message("cancelled".into()));
                    }
                    flood_resumes += 1;
                    continue;
                }
                _ => return Err(error),
            }
        }
        let admission = client.policy.admit_download(target_dc, large)?;
        let first = download_media_range_on_lane_dc(
            handle, client, home, api_id, media, dest, offset, target_dc,
        );
        if let Err(ref error) = first {
            match media::transfer_step(error, target_dc, migrations, flood_resumes) {
                media::TransferStep::Migrate(dc) => {
                    match &mut media.location {
                        media::MediaLocation::Photo { dc_id, .. }
                        | media::MediaLocation::Document { dc_id, .. }
                        | media::MediaLocation::PeerPhoto { dc_id, .. } => *dc_id = dc,
                        media::MediaLocation::Inline { .. } => {}
                    }
                    target_dc = dc;
                    migrations += 1;
                    drop(admission);
                    continue;
                }
                media::TransferStep::ResumeFlood => {
                    let seconds = media::flood_wait_secs(error).unwrap_or(1);
                    drop(admission);
                    if !wait_flood(dest, seconds) {
                        return Err(MtprotoError::Message("cancelled".into()));
                    }
                    flood_resumes += 1;
                    continue;
                }
                media::TransferStep::Stop => {}
            }
        }
        return match first {
            Err(err) if media::is_cdn_redirect(&err) => {
                let job = media::take_cdn_redirect().ok_or(err)?;
                drop(admission);
                cdn_or_origin(|| {
                    let _cdn_admission = client.policy.admit_download(job.dc_id, large)?;
                    follow_cdn_redirect(handle, api_id, dest, &job)
                }, || {
                    let _admission = client.policy.admit_download(target_dc, large)?;
                    download_media_range_on_lane_dc(
                        handle, client, home, api_id, media, dest, offset, target_dc,
                    )
                })
            }
            other => other,
        };
    }
}

/// Flood wait is off the media lane: the admission guard is already dropped.
fn wait_flood(dest: &Path, seconds: u64) -> bool {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(seconds.max(1));
    while std::time::Instant::now() < deadline {
        if media::download_cancelled(dest) || crate::request_control::check().is_err() {
            return false;
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    !media::download_cancelled(dest) && crate::request_control::check().is_ok()
}

/// Home-DC download on the main socket. Used only when a temp key cannot be bound,
/// so the permanent key is not opened on a second TCP.
pub(crate) fn download_media_range_on_home_session(
    handle: u64,
    client: &Client,
    expected_session_id: i64,
    api_id: i32,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    loop {
    let _span = crate::perf::span("media_file_home");
    let (session_id, dc) = {
        let data = client.data.lock();
        if !session_lease_valid(&data, expected_session_id) {
            return Err(expired_session_lease());
        }
        (expected_session_id, data.home_dc)
    };
    let staged = stage_for(dest, offset)?;
    // Cap refills to one window so `with_client_mut` returns and updates can drain.
    // Sliding refill still keeps the in-flight window full inside that lease.
    let downloaded = media::download_media_range_batched_streaming_capped(
        dc,
        media,
        staged.path(),
        dest,
        offset,
        media::download_in_flight(),
        Some(media::download_in_flight()),
        |_init_first, requests, on_chunk| {
            media::check_transfer_flood(dc, media::TransferClass::Download)?;
            with_client_mut(handle, |state| {
                if !home_lease_ok(state, session_id) {
                    return Err(expired_session_lease());
                }
                ensure_ready(state)?;
                crate::rpc::with_rpc_timeout_secs(45, || {
                    crate::api_invoke::invoke_api_batch_without_updates_streaming::<
                        _,
                        tellers_mtproto::latest::api::UploadFile,
                    >(&mut state.snapshot, api_id, requests, on_chunk)
                })
            })
        },
    );
    if downloaded.as_ref().err().is_some_and(media::optional_paused) {
        staged.keep();
        while media::should_pause_optional(Some(media::DownloadProfile::Background)) {
            if media::download_cancelled(dest) || crate::request_control::check().is_err() {
                return Err(MtprotoError::Message("cancelled".into()));
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        continue;
    }
    return finish_staged(staged, downloaded, client, session_id, dest);
    }
}

pub(crate) fn publish_media(
    client: &Client,
    session_id: i64,
    staged: media::StagedDownload,
    dest: &Path,
) -> Result<String, MtprotoError> {
    if !session_lease_valid(&client.data.lock(), session_id) {
        return Err(expired_session_lease());
    }
    tcp::with_connection_control(&client.connections, || {
        tcp::while_client_open(|| {
            crate::request_control::while_active(|| staged.publish(dest))
                .map_err(|e| MtprotoError::Message(e.to_string()))?
        })
        .map_err(|e| MtprotoError::Message(e.to_string()))?
    })
}
