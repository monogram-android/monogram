use std::path::Path;

use tellers_mtproto::latest::api::{CdnConfig, CdnPublicKey, FileHash, UploadCdnFile, Vector};
use tellers_mtproto_session::OsRandom;
use tellers_mtproto_transport::PaddedIntermediate;

use crate::api_invoke;
use crate::auth_key::create_auth_key_with_pem;
use crate::dialogs;
use crate::media;
use crate::peers::vector_boxed_items;
use crate::tcp;
use crate::MtprotoError;

use super::*;

pub fn download_wallpaper(
    handle: u64,
    id: i64,
    access_hash: i64,
    dest_path: String,
) -> Result<String, MtprotoError> {
    let client = get_client(handle)?;
    let (api_id, home, media) = with_client_mut(handle, |state| {
        let media = call_with_migrate(state, |state| {
            crate::wallpaper_rpc::wallpaper_media(
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
                if state.snapshot.session_id != home.session_id {
                    return Err(expired_session_lease());
                }
                let media = call_with_migrate(state, |state| {
                    crate::wallpaper_rpc::wallpaper_media(
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

pub(crate) fn reindex_peer_avatar(state: &mut ClientState, chat_id: i64) {
    media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
    if state.media.contains_key(&(chat_id, 0)) {
        return;
    }
    let _ = call_with_migrate(state, refresh_dialogs);
    media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
    if state.media.contains_key(&(chat_id, 0)) {
        return;
    }
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
    media::fill_zero_peer_photo_hashes(&mut state.media, &state.peers);
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
    if let Some(url) = crate::instant_view_rpc::media_refresh_url(&state.media, chat_id, message_id)
    {
        let _ = crate::instant_view_rpc::get_web_page(
            &mut state.snapshot,
            state.api_id,
            &mut state.peers,
            &mut state.media,
            url,
            0,
        );
        return Ok(());
    }
    if message_id == crate::instant_view_rpc::INSTANT_VIEW_MEDIA_MSG {
        return Ok(());
    }
    if message_id == 0 {
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
            let _ = crate::extras_rpc::get_saved_gifs(
                &mut state.snapshot,
                state.api_id,
                &mut state.media,
                0,
            );
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
    if crate::inline_rpc::get_inline_bot_results(
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

pub(crate) fn indexed_media_for_download(
    data: &ClientData,
    chat_id: i64,
    message_id: i32,
    kind: media::MediaDownloadKind,
) -> Option<Result<(i32, Snapshot, media::MediaRef), MtprotoError>> {
    let indexed = data.media.get(&(chat_id, message_id))?;
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
    Some(media::media_for_download(indexed, kind)
        .map(|media| (data.api_id, home, media)))
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
            if message_id == crate::instant_view_rpc::INSTANT_VIEW_MEDIA_MSG {
                // Indexed only by messages.getWebPage as (media_id, -1).
                // Never treat a photo/document id as a dialog for history.
            } else if message_id == 0 {
                // Saved GIFs / inline docs are also keyed as `(document_id, 0)`.
                // Only known dialog peers should fall back to avatar reindex.
                if state.peers.contains_key(&chat_id) {
                    reindex_peer_avatar(state, chat_id);
                }
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
        let mut indexed = state.media.get(&(chat_id, message_id)).cloned();
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
                if state.snapshot.session_id != home.session_id {
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
    let (api_id, home, media) = with_client_mut(handle, |state| {
        ensure_ready(state)?;
        if let Some(indexed) = state.media.get(&(document_id, 0)).cloned() {
            return Ok((state.api_id, state.snapshot.clone(), indexed));
        }
        let indexed = call_with_migrate(state, |state| {
            media::fetch_custom_emoji(&mut state.snapshot, state.api_id, document_id)
        })?;
        state.media.insert((document_id, 0), indexed.clone());
        persist(state)?;
        Ok((state.api_id, state.snapshot.clone(), indexed))
    })?;
    download_media_on_lane(
        handle,
        &client,
        &home,
        api_id,
        &media,
        Path::new(&dest_path),
    )
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
    let target_dc = media::download_dc(home.dc_id, &media.location);
    let first = if target_dc == home.dc_id {
        // File transfer sessions are exempt from the parallel-session rule only on
        // *media* DCs. A file stored on the home DC rides the single main session,
        // otherwise the extra home-DC session kills the authorization (406).
        match download_media_range_on_home_session(handle, client, home.session_id, api_id, media, dest, offset) {
            Err(err) => match api_invoke::migrate_dc(&err) {
                Some(dc) => download_media_range_on_lane_dc(
                    handle, client, home, api_id, media, dest, offset, dc,
                ),
                None => Err(err),
            },
            ok => ok,
        }
    } else {
        download_media_range_on_lane_dc(
            handle, client, home, api_id, media, dest, offset, target_dc,
        )
    };
    match first {
        Ok(path) => Ok(path),
        Err(err) if media::is_cdn_redirect(&err) => {
            let job = media::take_cdn_redirect().ok_or(err)?;
            match follow_cdn_redirect(handle, api_id, dest, &job) {
                Ok(path) => Ok(path),
                Err(_) => media::with_cdn_supported(false, || {
                    if target_dc == home.dc_id {
                        download_media_range_on_home_session(
                            handle, client, home.session_id, api_id, media, dest, offset,
                        )
                    } else {
                        download_media_range_on_lane_dc(
                            handle, client, home, api_id, media, dest, offset, target_dc,
                        )
                    }
                }),
            }
        }
        other => other,
    }
}

/// Home-DC file transfer over the single main session.
///
/// Uploads already work this way; the pipelined window keeps throughput while the
/// updates subscriber shares the same sequence-number space.
pub(crate) fn download_media_range_on_home_session(
    handle: u64,
    client: &Client,
    expected_session_id: i64,
    api_id: i32,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
) -> Result<String, MtprotoError> {
    let _span = crate::perf::span("media_file_home");
    let (session_id, dc) = {
        let data = client.data.lock();
        if !session_lease_valid(&data, expected_session_id) {
            return Err(expired_session_lease());
        }
        (expected_session_id, data.home_dc)
    };
    let staged = media::StagedDownload::new(dest)?;
    // `download_media_range_batched` invokes its callback once per pipelined
    // window. Acquiring the main lane there lets the updates drain run between
    // windows while preserving the one permitted home-DC session.
    media::download_media_range_batched(
        dc,
        media,
        staged.path(),
        dest,
        offset,
        pipeline_parts(),
        |_init_first, requests| {
            with_client_mut(handle, |state| {
                if state.snapshot.session_id != session_id {
                    return Err(expired_session_lease());
                }
                ensure_ready(state)?;
                crate::rpc::with_rpc_timeout_secs(45, || {
                    crate::api_invoke::invoke_api_batch_without_updates::<
                        _,
                        tellers_mtproto::latest::api::UploadFile,
                    >(&mut state.snapshot, api_id, requests)
                })
            })
        },
    )?;
    publish_media(client, session_id, staged, dest)
}

pub(crate) fn download_media_range_on_lane_dc(
    handle: u64,
    client: &Client,
    home: &tellers_mtproto_session::Snapshot,
    api_id: i32,
    media: &media::MediaRef,
    dest: &Path,
    offset: Option<i64>,
    target_dc: i32,
) -> Result<String, MtprotoError> {
    let _span = crate::perf::span("media_file");
    let staged = media::StagedDownload::new(dest)?;
    let mut io = lock_media_lane(client)?;
    if !session_lease_valid(&client.data.lock(), home.session_id) {
        return Err(expired_session_lease());
    }
    match prepare_media_lane_snapshot(&mut io.snapshot, home, target_dc) {
        MediaLanePrep::NeedExport => {
            io.snapshot = with_client_mut(handle, |state| {
                if state.snapshot.session_id != home.session_id {
                    return Err(expired_session_lease());
                }
                copy_authorization_to_dc(&mut state.snapshot, api_id, target_dc)
            })?;
            io.transport = None;
        }
        MediaLanePrep::Replaced => io.transport = None,
        MediaLanePrep::Reuse => {}
    }
    let mut media_snap = io.snapshot.clone();
    let mut slot = io.transport.take();
    crate::rpc::clear_new_session_metadata();
    let path = with_client_transport(client, &mut slot, || {
        crate::rpc::with_rpc_timeout_secs(45, || {
            // Pipelined window: several parts in flight on this one session.
            let lane_dc = media_snap.dc_id;
            match media::download_media_range_batched(
                lane_dc,
                media,
                staged.path(),
                dest,
                offset,
                pipeline_parts(),
                |_init_first, requests| {
                    crate::api_invoke::invoke_api_batch_without_updates::<
                        _,
                        tellers_mtproto::latest::api::UploadFile,
                    >(&mut media_snap, api_id, requests)
                },
            ) {
                Ok(path) => Ok(path),
                Err(err) => {
                    if let Some(dc) = crate::api_invoke::migrate_dc(&err) {
                        crate::rpc::drop_live_transport();
                        media_snap = if dc == home.dc_id {
                            fork_session(home)
                        } else {
                            with_client_mut(handle, |state| {
                                if state.snapshot.session_id != home.session_id {
                                    return Err(expired_session_lease());
                                }
                                let destination = call_with_migrate(state, |state| {
                                    copy_authorization_to_dc(&mut state.snapshot, api_id, dc)
                                })?;
                                persist(state)?;
                                Ok(destination)
                            })?
                        };
                        crate::rpc::drop_live_transport();
                        let retry_dc = media_snap.dc_id;
                        media::download_media_range_batched(
                            retry_dc,
                            media,
                            staged.path(),
                            dest,
                            offset,
                            pipeline_parts(),
                            |_init_first, requests| {
                                crate::api_invoke::invoke_api_batch_without_updates::<
                                    _,
                                    tellers_mtproto::latest::api::UploadFile,
                                >(&mut media_snap, api_id, requests)
                            },
                        )
                    } else {
                        Err(err)
                    }
                }
            }
        })
    });
    io.snapshot = media_snap.clone();
    // FILE_REFERENCE / transport errors must not park this TCP for the next file.
    io.transport = if path.is_ok() { slot } else { None };
    drop(io);
    let new_session = crate::rpc::take_new_session_metadata();
    if media_snap.dc_id == home.dc_id && media_snap.auth_key == home.auth_key {
        let mut d = client.data.lock();
        if !session_lease_valid(&d, home.session_id) {
            return Err(expired_session_lease());
        }
        d.home_salt = media_snap.server_salt;
        d.home_time_offset = media_snap.time_offset_micros;
        if new_session.is_some() {
            d.new_session = new_session.clone();
        }
    }
    if new_session.is_some() {
        persist_updates_data(client, home.session_id)?;
    }
    if let Err(ref err) = path {
        if media_snap.dc_id == home.dc_id
            && media_snap.auth_key == home.auth_key
            && is_unrecoverable_session(err)
        {
            let _ = with_client_mut(handle, |state| {
                if state.snapshot.session_id != home.session_id {
                    return Err(expired_session_lease());
                }
                mark_session_dead(state, err);
                Ok(())
            });
        }
    }
    path?;
    publish_media(client, home.session_id, staged, dest)
}

fn follow_cdn_redirect(
    handle: u64,
    api_id: i32,
    dest: &Path,
    job: &media::CdnRedirect,
) -> Result<String, MtprotoError> {
    let pem = with_client_mut(handle, |state| {
        let cfg: CdnConfig =
            api_invoke::invoke_api(&mut state.snapshot, api_id, media::get_cdn_config_request())?;
        let CdnConfig::CdnConfig(cfg) = cfg;
        let pem = vector_boxed_items(cfg.public_keys.as_ref()).find_map(|key| match key {
            CdnPublicKey::CdnPublicKey(body) if body.dc_id == job.dc_id => {
                Some(body.public_key.clone())
            }
            _ => None,
        });
        pem.ok_or_else(|| MtprotoError::Message("cdn public key".into()))
    })?;
    let endpoint = crate::dns_txt::cdn_endpoints_for(job.dc_id)
        .into_iter()
        .next()
        .ok_or_else(|| MtprotoError::Message("cdn endpoint".into()))?;
    let mut snap = Snapshot::new(job.dc_id, &mut OsRandom)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut conn = tcp::connect_obfuscated(&endpoint.addr)
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let mut framing = PaddedIntermediate::default();
    create_auth_key_with_pem(&mut conn, &mut framing, &mut snap, &pem)?;
    let mut slot = Some(crate::rpc::LiveTransport {
        ping_sent: None,
        updates: Vec::new(),
        updates_bytes: 0,
        dc_id: job.dc_id,
        endpoint: endpoint.addr.clone(),
        conn,
        framing,
        input: Vec::new(),
        last_io: std::time::Instant::now(),
    });
    let staged = media::StagedDownload::new(dest)?;
    let path = crate::rpc::with_live_transport(&mut slot, || {
        crate::rpc::with_rpc_timeout_secs(45, || {
            use std::io::{Seek, SeekFrom, Write};
            let mut out = std::fs::File::create(staged.path())
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            let mut offset = job.offset;
            loop {
                if media::download_cancelled(dest) {
                    return Err(MtprotoError::Message("cancelled".into()));
                }
                let mut part = loop {
                    let response: UploadCdnFile = api_invoke::invoke_api_without_updates(
                        &mut snap,
                        api_id,
                        media::get_cdn_file_request(job.file_token.clone(), offset, media::CHUNK),
                    )?;
                    match media::cdn_file_bytes(response)? {
                        Ok(bytes) => break bytes,
                        Err(request_token) => {
                            let _ = with_client_mut(handle, |state| {
                                api_invoke::invoke_api::<_, Vector<Box<FileHash>>>(
                                    &mut state.snapshot,
                                    api_id,
                                    media::reupload_cdn_file_request(
                                        job.file_token.clone(),
                                        request_token,
                                    ),
                                )
                            })?;
                        }
                    }
                };
                media::decrypt_cdn_part(
                    &job.encryption_key,
                    &job.encryption_iv,
                    offset,
                    &mut part,
                )?;
                out.seek(SeekFrom::Start(offset as u64))
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                out.write_all(&part)
                    .map_err(|e| MtprotoError::Message(e.to_string()))?;
                if (part.len() as i32) < media::CHUNK {
                    break;
                }
                offset += part.len() as i64;
            }
            out.flush()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            Ok(staged.path().display().to_string())
        })
    })?;
    let client = get_client(handle)?;
    let session_id = client.data.lock().home_session_id;
    publish_media(&client, session_id, staged, dest)?;
    let _ = path;
    Ok(dest.display().to_string())
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
