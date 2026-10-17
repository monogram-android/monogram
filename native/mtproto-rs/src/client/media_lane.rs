pub(crate) fn split_even_odd_request_indices(
    offsets: &[i64],
    chunk: i32,
) -> (Vec<usize>, Vec<usize>) {
    let step = i64::from(chunk.max(1));
    let mut even = Vec::new();
    let mut odd = Vec::new();
    for (index, offset) in offsets.iter().enumerate() {
        if (*offset / step) % 2 == 0 {
            even.push(index);
        } else {
            odd.push(index);
        }
    }
    (even, odd)
}

fn invoke_lane_batch(
    client: &Client,
    snapshot: &mut tellers_mtproto_session::Snapshot,
    slot: &mut Option<crate::rpc::LiveTransport>,
    api_id: i32,
    requests: Vec<tellers_mtproto::latest::api::UploadGetFileRequest>,
    on_chunk: &mut dyn FnMut(
        usize,
        Result<tellers_mtproto::latest::api::UploadFile, MtprotoError>,
    ) -> Option<tellers_mtproto::latest::api::UploadGetFileRequest>,
) -> Result<Vec<Result<tellers_mtproto::latest::api::UploadFile, MtprotoError>>, MtprotoError> {
    with_client_transport(client, slot, || {
        crate::api_invoke::invoke_api_batch_without_updates_streaming::<
            _,
            tellers_mtproto::latest::api::UploadFile,
        >(snapshot, api_id, requests, on_chunk)
    })
}

pub(crate) fn invoke_parallel_media_batches<Req: Send, Res: Send + Clone>(
    batches: [Vec<(usize, Req)>; 2],
    invoke_a: impl FnOnce(
        Vec<Req>,
        &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
    ) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
    + Send,
    invoke_b: impl FnOnce(
        Vec<Req>,
        &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
    ) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
    + Send,
    on_chunk: &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError> {
    use std::sync::mpsc::{Sender, sync_channel};

    fn run_lane<Req, Res>(
        batch: Vec<(usize, Req)>,
        completed: Sender<(
            usize,
            Result<Res, MtprotoError>,
            std::sync::mpsc::SyncSender<Option<(usize, Req)>>,
        )>,
        invoke: impl FnOnce(
            Vec<Req>,
            &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
        ) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>,
    ) -> Result<(), MtprotoError> {
        let (mut indices, requests): (Vec<_>, Vec<_>) = batch.into_iter().unzip();
        invoke(requests, &mut |index, result| {
            let global_index = *indices.get(index)?;
            let (reply, refill) = sync_channel(0);
            completed.send((global_index, result, reply)).ok()?;
            let (next_index, request) = refill.recv().ok()??;
            indices.push(next_index);
            Some(request)
        })
        .map(|_| ())
    }

    let initial_len = batches.iter().map(Vec::len).sum();
    let mut results: Vec<Option<Result<Res, MtprotoError>>> =
        (0..initial_len).map(|_| None).collect();
    let failed = std::sync::atomic::AtomicBool::new(false);
    std::thread::scope(|scope| {
        let [a, b] = batches;
        let failed_ref = &failed;
        let (completed, chunks) = std::sync::mpsc::channel();
        let completed_b = completed.clone();
        let even = scope.spawn(move || {
            let result = run_lane(a, completed, invoke_a);
            if result.is_err() {
                failed_ref.store(true, Ordering::Release);
            }
            result
        });
        let odd = scope.spawn(move || {
            let result = run_lane(b, completed_b, invoke_b);
            if result.is_err() {
                failed_ref.store(true, Ordering::Release);
            }
            result
        });
        for (index, result, reply) in chunks {
            if result.is_err() {
                failed.store(true, Ordering::Release);
            }
            let refill = on_chunk(index, result.clone())
                .filter(|_| !failed.load(Ordering::Acquire))
                .map(|request| {
                    let next_index = results.len();
                    results.push(None);
                    (next_index, request)
                });
            results[index] = Some(result);
            let _ = reply.send(refill);
        }
        even.join()
            .map_err(|_| MtprotoError::Message("even media lane".into()))??;
        odd.join()
            .map_err(|_| MtprotoError::Message("odd media lane".into()))??;
        Ok(results
            .into_iter()
            .map(|result| {
                result.unwrap_or_else(|| Err(MtprotoError::Message("missing media part".into())))
            })
            .collect())
    })
}

fn invoke_media_batch_even_odd(
    client: &Client,
    snap_a: &mut tellers_mtproto_session::Snapshot,
    slot_a: &mut Option<crate::rpc::LiveTransport>,
    snap_b: Option<&mut tellers_mtproto_session::Snapshot>,
    slot_b: Option<&mut Option<crate::rpc::LiveTransport>>,
    api_id: i32,
    requests: Vec<tellers_mtproto::latest::api::UploadGetFileRequest>,
    on_chunk: &mut dyn FnMut(
        usize,
        Result<tellers_mtproto::latest::api::UploadFile, MtprotoError>,
    ) -> Option<tellers_mtproto::latest::api::UploadGetFileRequest>,
) -> Result<Vec<Result<tellers_mtproto::latest::api::UploadFile, MtprotoError>>, MtprotoError> {
    let Some(first) = requests.first() else {
        return Ok(Vec::new());
    };
    let offsets: Vec<i64> = requests.iter().map(|r| r.offset).collect();
    let (even_idx, odd_idx) = split_even_odd_request_indices(&offsets, first.limit);
    let even_reqs: Vec<_> = even_idx.iter().map(|i| requests[*i].clone()).collect();
    let odd_reqs: Vec<_> = odd_idx.iter().map(|i| requests[*i].clone()).collect();
    if snap_b.is_none() || slot_b.is_none() || odd_reqs.is_empty() || even_reqs.is_empty() {
        return with_client_transport(client, slot_a, || {
            crate::api_invoke::invoke_api_batch_without_updates_streaming(
                snap_a, api_id, requests, on_chunk,
            )
        });
    }
    let snap_b = snap_b.unwrap();
    let slot_b = slot_b.unwrap();
    let timeout = crate::rpc::rpc_timeout_secs();
    let class = crate::scheduler::current_class();
    let request_id = crate::request_control::current_id();
    let run = |snapshot: &mut tellers_mtproto_session::Snapshot,
               slot: &mut Option<crate::rpc::LiveTransport>,
               requests,
               on_chunk: &mut dyn FnMut(_, _) -> _| {
        let _policy = crate::transfer_policy::bind(client.policy.clone());
        crate::request_control::with_shared_binding(request_id, || {
            crate::scheduler::with_class(class, || {
                crate::rpc::with_rpc_timeout_secs(timeout, || {
                    invoke_lane_batch(client, snapshot, slot, api_id, requests, on_chunk)
                })
            })
        })
    };
    let result = invoke_parallel_media_batches(
        [
            even_idx.into_iter().zip(even_reqs).collect(),
            odd_idx.into_iter().zip(odd_reqs).collect(),
        ],
        |requests, on_chunk| run(snap_a, slot_a, requests, on_chunk),
        |requests, on_chunk| run(snap_b, slot_b, requests, on_chunk),
        on_chunk,
    );
    crate::request_control::detach_sockets();
    result
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
    loop {
    let _span = crate::perf::span("media_file");
    let mut io = lock_media_lane(client)?;
    if !session_lease_valid(&client.data.lock(), home.session_id) {
        return Err(expired_session_lease());
    }
    let mut first_chunk_span = Some(crate::perf::span("media_first_chunk"));
    let already_temp = download_temp_ready(&io, client, target_dc, home.auth_key.as_deref());
    if !already_temp {
        match prepare_media_lane_snapshot(&mut io.snapshot, home, target_dc) {
            MediaLanePrep::NeedExport => {
                io.snapshot = with_client_mut(handle, |state| {
                    if !home_lease_ok(state, home.session_id) {
                        return Err(expired_session_lease());
                    }
                    copy_authorization_to_dc(state, target_dc)
                })?;
                io.transport = None;
            }
            MediaLanePrep::Replaced => io.transport = None,
            MediaLanePrep::Reuse => {}
        }
        if let Some(key) = io.snapshot.auth_key.clone() {
            let dc = io.snapshot.dc_id;
            if let Some(cached) = matching_temp(client, dc, &key) {
                io.perm_key = Some(cached.perm_key);
                io.snapshot = fork_session(&cached.snapshot);
                io.temp_expires_at = cached.temp_expires_at;
                io.transport = None;
            } else {
                io.perm_key = Some(key.clone());
                let time_offset = io.snapshot.time_offset_micros;
                if let Ok(bound) = crate::auth::bind_temp::negotiate_bound_temp(&key, dc, time_offset) {
                    remember_temp(client, dc, &bound.snapshot, bound.expires_at, &key);
                    io.snapshot = bound.snapshot;
                    io.temp_expires_at = bound.expires_at;
                    io.transport = None;
                }
            }
        }
    }
    if target_dc == home.dc_id && io.snapshot.auth_key.as_ref() == home.auth_key.as_ref() {
        drop(io);
        return download_media_range_on_home_session(
            handle,
            client,
            home.session_id,
            api_id,
            media,
            dest,
            offset,
        );
    }
    let staged = stage_for(dest, offset)?;
    let bind_key = io.perm_key.clone().or_else(|| {
        (target_dc == home.dc_id)
            .then(|| home.auth_key.clone())
            .flatten()
    });
    let mut io2 = if should_open_second_media_lane(target_dc, media::download_in_flight()) {
        try_lock_second_media_lane(client)
    } else {
        None
    };
    let second_ready = match io2.as_mut() {
        Some(second) if download_temp_ready(second, client, target_dc, home.auth_key.as_deref()) => {
            true
        }
        _ => false,
    };
    if !second_ready {
        io2 = None;
    }
    let mut preparation = if io2.is_none() && offset.is_none()
        && should_open_second_media_lane(target_dc, media::download_in_flight()) {
        bind_key.and_then(|key| get_client(handle).ok().map(|worker_client| {
            let class = scheduler::current_class();
            let home_key = home.auth_key.clone();
            let time_offset = home.time_offset_micros;
            let expected_session = home.session_id;
            MediaLanePreparation::start(move || {
                let _policy = crate::transfer_policy::bind(worker_client.policy.clone());
                scheduler::with_class(class, || {
                    tcp::with_connection_control(&worker_client.connections, || {
                        let _span = crate::perf::span("media_second_lane_prepare");
                        let Some(mut second) = try_lock_second_media_lane(&worker_client) else { return false; };
                        if !session_lease_valid(&worker_client.data.lock(), expected_session) { return false; }
                        if download_temp_ready(&second, &worker_client, target_dc, home_key.as_deref()) { return true; }
                        if let Some(cached) = matching_temp(&worker_client, target_dc, &key) {
                            second.perm_key = Some(cached.perm_key);
                            second.snapshot = fork_session(&cached.snapshot);
                            second.temp_expires_at = cached.temp_expires_at;
                            second.transport = None;
                            return true;
                        }
                        let Ok(bound) = crate::auth::bind_temp::negotiate_bound_temp(&key, target_dc, time_offset) else { return false; };
                        if crate::request_control::check().is_err()
                            || !session_lease_valid(&worker_client.data.lock(), expected_session) { return false; }
                        remember_temp(&worker_client, target_dc, &bound.snapshot, bound.expires_at, &key);
                        second.perm_key = Some(key);
                        second.snapshot = bound.snapshot;
                        second.temp_expires_at = bound.expires_at;
                        second.transport = None;
                        true
                    })
                })
            })
        }))
    } else { None };
    let mut media_snap = io.snapshot.clone();
    let mut slot = io.transport.take();
    let mut media_snap2 = io2.as_ref().map(|lane| lane.snapshot.clone());
    let mut slot2 = io2.as_mut().map(|lane| lane.transport.take());
    crate::rpc::clear_new_session_metadata();
    // Timeout is per window, not the whole file. Streaming idle-extends while parts arrive.
    let path = {
        let lane_dc = media_snap.dc_id;
        media::download_media_range_batched_streaming_capped(
            lane_dc,
            media,
            staged.path(),
            dest,
            offset,
            media::download_in_flight(),
            None,
            |_init_first, requests, on_chunk| {
                media::check_transfer_flood(lane_dc, media::TransferClass::Download)?;
                if preparation.as_ref().is_some_and(MediaLanePreparation::finished) {
                    preparation = None;
                    if should_open_second_media_lane(lane_dc, media::download_in_flight()) {
                        io2 = try_lock_second_media_lane(client).filter(|lane|
                            download_temp_ready(lane, client, lane_dc, home.auth_key.as_deref()));
                        media_snap2 = io2.as_ref().map(|lane| lane.snapshot.clone());
                        slot2 = io2.as_mut().map(|lane| lane.transport.take());
                    }
                }
                let mut stream_chunk = |index, result: Result<tellers_mtproto::latest::api::UploadFile, MtprotoError>| {
                    if matches!(&result, Ok(tellers_mtproto::latest::api::UploadFile::UploadFile(file)) if !file.bytes.is_empty()) {
                        first_chunk_span.take();
                    }
                    let refill = on_chunk(index, result);
                    if preparation.as_ref().is_some_and(MediaLanePreparation::finished) { None } else { refill }
                };
                crate::rpc::with_rpc_timeout_secs(45, || {
                    invoke_media_batch_even_odd(
                        client,
                        &mut media_snap,
                        &mut slot,
                        media_snap2.as_mut(),
                        slot2.as_mut(),
                        api_id,
                        requests,
                        &mut stream_chunk,
                    )
                })
            },
        )
    };
    drop(preparation);
    io.snapshot = media_snap.clone();
    // FILE_REFERENCE / transport errors must not park this TCP for the next file.
    io.transport = if path.is_ok() { slot } else { None };
    if let Some(second) = io2.as_mut() {
        if let Some(snap2) = media_snap2 {
            second.snapshot = snap2;
        }
        second.transport = if path.is_ok() { slot2.flatten() } else { None };
    }
    drop(io);
    drop(io2);
    if path.as_ref().err().is_some_and(media::optional_paused) {
        staged.keep();
        while media::should_pause_optional(Some(media::DownloadProfile::Background)) {
            if media::download_cancelled(dest) || crate::request_control::check().is_err() {
                return Err(MtprotoError::Message("cancelled".into()));
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        continue;
    }
    if let Ok(client) = get_client(handle) {
        schedule_file_cleanup(&client);
    }
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
        let foreign_key = target_dc != home.dc_id
            && !(media_snap.dc_id == home.dc_id && media_snap.auth_key == home.auth_key)
            && is_foreign_auth_key_failure(err);
        if foreign_key && !DC_AUTH_RETRY.with(std::cell::Cell::get) {
            let _retry = DcAuthRetry::enter();
            let _ = with_client_mut(handle, |state| {
                if home_lease_ok(state, home.session_id) {
                    state.dc_auth_keys.remove(&target_dc);
                }
                Ok(())
            });
            client.data.lock().dc_temp_auth.remove(&target_dc);
            for lane in client.media.iter() {
                if let Some(mut lane_io) = lane.io.try_lock() {
                    if lane_io.snapshot.dc_id == target_dc {
                        lane_io.temp_expires_at = 0;
                        lane_io.perm_key = None;
                        lane_io.transport = None;
                    }
                }
            }
            staged.keep();
            return download_media_range_on_lane_dc(
                handle, client, home, api_id, media, dest, offset, target_dc,
            );
        }
        if media_snap.dc_id == home.dc_id
            && media_snap.auth_key == home.auth_key
            && is_unrecoverable_session(err)
        {
            let _ = with_client_mut(handle, |state| {
                if !home_lease_ok(state, home.session_id) {
                    return Err(expired_session_lease());
                }
                mark_session_dead(state, err);
                Ok(())
            });
        }
    }
    return finish_staged(staged, path, client, home.session_id, dest);
    }
}

thread_local! {
    static DC_AUTH_RETRY: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

struct DcAuthRetry(bool);

impl DcAuthRetry {
    fn enter() -> Self {
        Self(DC_AUTH_RETRY.with(|flag| flag.replace(true)))
    }
}

impl Drop for DcAuthRetry {
    fn drop(&mut self) {
        DC_AUTH_RETRY.with(|flag| flag.set(self.0));
    }
}

pub(crate) fn should_open_second_media_lane(dc_id: i32, in_flight: usize) -> bool {
    in_flight > 1
        && crate::scheduler::active_media_lanes() > 1
        && !crate::media::download_flood_active(dc_id)
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
    let mut conn = tcp::connect_obfuscated_timeout_obf(
        &endpoint.addr,
        5,
        Some(job.dc_id as i16),
        endpoint.secret.as_ref().map(|secret| secret.as_slice()),
    )
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
                if media::download_cancelled(dest) || crate::request_control::check().is_err() {
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
                media::notify_progress(&dest.display().to_string(), offset, 0);
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

