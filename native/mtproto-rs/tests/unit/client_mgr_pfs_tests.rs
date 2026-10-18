use super::*;
use crate::UpdateEventDto;

struct Fixture {
    handle: u64,
    path: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("monogram-pfs-{}-{nonce}.json", std::process::id()));
        let handle = create_client(1, String::new(), path.to_string_lossy().into_owned());
        Self { handle, path }
    }
    fn temporary(&self, expires_at: i32) -> Arc<Client> {
        let client = get_client(self.handle).unwrap();
        {
            let mut main = client.main.lock();
            let mut data = client.data.lock();
            data.user_id = Some(7);
            data.persist_epoch = 1;
            data.home_auth_key = Some(vec![3; 256]);
            data.home_snapshot.auth_key = data.home_auth_key.clone();
            main.snapshot = fork_session(&main.snapshot);
            main.snapshot.auth_key = Some(vec![4; 256]);
            main.temp_expires_at = expires_at;
            main.perm_key = Some(vec![3; 256]);
        }
        client
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        destroy_client(self.handle);
        let _ = std::fs::remove_file(&self.path);
    }
}

#[test]
fn main_renews_at_lane_boundary_and_preserves_permanent_identity() {
    let now = crate::auth::bind_temp::unix_now();
    for expiry in [1, now + 30, now + 3600] {
        let fixture = Fixture::new();
        let client = fixture.temporary(expiry);
        let original_home = client.data.lock().home_session_id;
        let calls = Cell::new(0);
        with_client_mut_binding(fixture.handle, |state| {
            let expected = if expiry > now + 60 { 4 } else { 5 };
            assert!(state.snapshot.auth_key.as_deref() == Some([expected; 256].as_slice()));
            assert!(state.permanent.as_ref().unwrap().auth_key.as_deref() == Some([3; 256].as_slice()));
            persist(state)
        }, |permanent, dc, offset| {
            calls.set(calls.get() + 1);
            assert!(permanent == [3; 256]);
            let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
            snapshot.auth_key = Some(vec![5; 256]);
            snapshot.time_offset_micros = offset;
            Ok(crate::auth::bind_temp::BoundTemp { snapshot, expires_at: now + 3600 })
        }).unwrap();
        assert_eq!(calls.get(), usize::from(expiry <= now + 60));
        let restored = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
        assert!(restored.snapshot.auth_key.as_deref() == Some([3; 256].as_slice()));
        assert_eq!(restored.snapshot.session_id, original_home);
        let data = client.data.lock();
        assert!(data.home_auth_key.as_deref() == Some([3; 256].as_slice()));
        assert_eq!(data.home_session_id, original_home);
    }
}

#[test]
fn failed_or_stale_renewal_never_runs_the_application_request_or_publishes_a_key() {
    for mode in ["failure", "session", "account", "dc", "close"] {
        let fixture = Fixture::new();
        let client = fixture.temporary(1);
        let original = client.main.lock().snapshot.clone();
        let invoked = Cell::new(false);
        let result = with_client_mut_binding(fixture.handle, |_| {
            invoked.set(true);
            Ok(())
        }, |_, dc, _| {
            if mode == "failure" { return Err(MtprotoError::Message("injected bind failure".into())); }
            match mode {
                "session" => client.data.lock().home_session_id = original.session_id,
                "account" => client.data.lock().user_id = Some(8),
                "dc" => client.data.lock().home_dc = dc + 1,
                "close" => client.connections.close(),
                _ => unreachable!(),
            }
            let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
            snapshot.auth_key = Some(vec![5; 256]);
            Ok(crate::auth::bind_temp::BoundTemp { snapshot, expires_at: crate::auth::bind_temp::unix_now() + 3600 })
        });
        assert!(result.is_err(), "case {mode}");
        assert!(!invoked.get());
        assert!(client.main.lock().snapshot == original);
        assert!(!fixture.path.exists());
    }
}

#[test]
fn temporary_key_rejection_preserves_login_and_renews_before_the_next_request() {
    for message in ["RPC 401: AUTH_KEY_UNREGISTERED", "RPC 401: AUTH_KEY_PERM_EMPTY", "RPC 406: AUTH_KEY_DUPLICATED", "MTProto transport error -404"] {
        let fixture = Fixture::new();
        let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
        let original_home = client.data.lock().home_session_id;
        let invocations = Cell::new(0);
        let result = with_client_mut_binding(fixture.handle, |state| {
            call_with_migrate(state, |_| {
                invocations.set(invocations.get() + 1);
                Err::<(), _>(MtprotoError::Message(message.into()))
            })
        }, |_, _, _| panic!("valid key was renewed before rejection"));
        assert_eq!(result.unwrap_err().to_string(), "connection closed: temporary authorization renewal required");
        assert_eq!(invocations.get(), 1);
        assert_eq!(client.main.lock().temp_expires_at, 1);
        {
            let data = client.data.lock();
            assert!(!data.session_dead);
            assert_eq!(data.home_session_id, original_home);
            assert!(data.home_auth_key.as_deref() == Some([3; 256].as_slice()));
        }
        with_client_mut_binding(fixture.handle, |state| {
            assert!(state.snapshot.auth_key.as_deref() == Some([5; 256].as_slice()));
            Ok(())
        }, |_, dc, offset| {
            let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
            snapshot.auth_key = Some(vec![5; 256]);
            snapshot.time_offset_micros = offset;
            Ok(crate::auth::bind_temp::BoundTemp { snapshot, expires_at: crate::auth::bind_temp::unix_now() + 3600 })
        }).unwrap();
    }
}

#[test]
fn permanent_authorization_revocation_remains_terminal_under_pfs() {
    let fixture = Fixture::new();
    let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
    let result = with_client_mut_binding(fixture.handle, |state| {
        call_with_migrate(state, |_| Err::<(), _>(MtprotoError::Message("RPC 401: SESSION_REVOKED".into())))
    }, |_, _, _| panic!("valid temporary key was renewed"));
    assert!(result.is_err());
    assert!(client.data.lock().session_dead);
    let restored = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
    assert!(restored.session_dead);
    assert!(restored.snapshot.auth_key.as_deref() == Some([3; 256].as_slice()));
}

#[test]
fn destruction_cancels_renewal_then_saves_the_permanent_session() {
    let fixture = Fixture::new();
    let client = fixture.temporary(1);
    let handle = fixture.handle;
    let waiting = client.clone();
    let (entered_tx, entered_rx) = std::sync::mpsc::channel();
    let (finished_tx, finished_rx) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let result: Result<(), MtprotoError> = with_client_mut_binding(handle, |_| panic!("request ran during shutdown"), |_, _, _| {
            entered_tx.send(()).unwrap();
            waiting.connections.wait(std::time::Duration::from_secs(60))
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
            unreachable!("shutdown did not cancel renewal")
        });
        finished_tx.send(result.is_err()).unwrap();
    });
    entered_rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap();
    let started = std::time::Instant::now();
    destroy_client(handle);
    assert!(started.elapsed() < std::time::Duration::from_secs(2));
    assert!(finished_rx.recv_timeout(std::time::Duration::from_secs(2)).unwrap());
    worker.join().unwrap();
    assert!(!client_exists(handle));
    let restored = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
    assert!(restored.snapshot.auth_key.as_deref() == Some([3; 256].as_slice()));
    assert_eq!(restored.user_id, Some(7));
    assert!(client.connections.while_persisting(|| panic!("late persistence")).is_err());
}

#[test]
fn failed_final_save_does_not_advance_persistence_state() {
    let fixture = Fixture::new();
    let client = fixture.temporary(1);
    std::fs::create_dir(&fixture.path).unwrap();
    let home_id = client.data.lock().home_session_id;
    {
        let mut data = client.data.lock();
        data.persist_epoch = 7;
        data.persisted_epoch = 0;
    }
    assert!(persist_updates_data(&client, home_id).is_err());
    assert_eq!(client.data.lock().persisted_epoch, 0);
    std::fs::remove_dir(&fixture.path).unwrap();
}

#[test]
fn scheduler_permissions_and_bound_keys_are_isolated_between_clients() {
    let first = Fixture::new();
    let second = Fixture::new();
    with_client_mut(first.handle, |_| {
        crate::scheduler::set_main_session_allowance(Some(3));
        crate::scheduler::set_bound_extra_sessions(2);
        assert_eq!(crate::scheduler::extra_main_sessions(), 2);
        Ok(())
    }).unwrap();
    with_client_mut(second.handle, |_| {
        assert!(!crate::scheduler::main_session_allowance_known());
        assert_eq!(crate::scheduler::extra_main_sessions(), 0);
        crate::scheduler::set_main_session_allowance(Some(1));
        crate::scheduler::set_bound_extra_sessions(2);
        assert_eq!(crate::scheduler::extra_main_sessions(), 0);
        Ok(())
    }).unwrap();
    with_client_mut(first.handle, |_| {
        assert_eq!(crate::scheduler::main_session_allowance(), 3);
        assert_eq!(crate::scheduler::extra_main_sessions(), 2);
        crate::scheduler::set_bound_extra_sessions(0);
        Ok(())
    }).unwrap();
    with_client_mut(second.handle, |_| {
        assert_eq!(crate::scheduler::main_session_allowance(), 1);
        assert_eq!(crate::transfer_policy::current().bound_extra_sessions.load(Ordering::Acquire), 2);
        Ok(())
    }).unwrap();
}

#[test]
fn saturated_sibling_queue_releases_partial_family_reservations() {
    let fixture = Fixture::new();
    let client = get_client(fixture.handle).unwrap();
    let saturated = &client.media[1].gate;
    let waiters: Vec<_> = (0..crate::scheduler::MAX_LANE_WAITERS)
        .map(|_| saturated.enqueue(scheduler::RequestClass::BackgroundMedia).unwrap()).collect();
    let result = super::lanes::acquire_lane_family(0..2, scheduler::RequestClass::InteractiveMedia,
        "queue_capacity", "queue_capacity", |index| &client.media[index]);
    assert!(result.is_err());
    assert_eq!(client.media[0].gate.waiter_count(), 0);
    assert_eq!(saturated.waiter_count(), crate::scheduler::MAX_LANE_WAITERS);
    drop(waiters);
    assert_eq!(saturated.waiter_count(), 0);
}

#[test]
fn key_migration_preserves_same_account_journal_and_concurrent_cursors() {
    for replace_user in [false, true] {
        let fixture = Fixture::new();
        let cursor = |pts| UpdatesStateDto { pts, qts: 0, date: pts, seq: pts };
        with_client_mut(fixture.handle, |state| {
            state.user_id = Some(7);
            state.snapshot.auth_key = Some(vec![3; 256]);
            state.updates = Some(cursor(100));
            state.channel_pts.insert(7, 100);
            for chat_id in [1, 2] {
                state.channel_recovery.push_back(ChannelRecovery { chat_id, due_at: 100, watching: false });
            }
            Ok(())
        }).unwrap();
        let client = get_client(fixture.handle).unwrap();
        let generation = {
            let mut data = client.data.lock();
            Arc::make_mut(&mut data.journal).push(vec![UpdateEventDto::MessagesDeleted {
                chat_id: Some(7), message_ids: vec![1],
            }], Some(cursor(100)), vec![(7, 100)]);
            data.journal.account_generation
        };
        with_client_mut(fixture.handle, |state| {
            {
                let mut data = client.data.lock();
                data.updates = Some(cursor(200));
                data.channel_pts.insert(7, 200);
                data.channel_recovery.iter_mut().find(|entry| entry.chat_id == 2).unwrap().due_at = 200;
                data.channel_recovery.push_back(ChannelRecovery { chat_id: 9, due_at: 100, watching: false });
                Arc::make_mut(&mut data.journal).push(vec![UpdateEventDto::MessagesDeleted {
                    chat_id: Some(7), message_ids: vec![2],
                }], Some(cursor(200)), vec![(7, 200)]);
            }
            state.snapshot = Snapshot::new(4, &mut OsRandom).unwrap();
            state.snapshot.auth_key = Some(vec![8; 256]);
            state.channel_recovery.clear();
            if replace_user {
                state.user_id = Some(8);
                state.updates = None;
                state.channel_pts.clear();
            } else {
                state.channel_recovery.push_back(ChannelRecovery { chat_id: 8, due_at: 100, watching: false });
            }
            Ok(())
        }).unwrap();
        let session = client.data.lock().home_session_id;
        persist_updates_data(&client, session).unwrap();
        let saved = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
        if replace_user {
            assert_ne!(saved.journal.account_generation, generation);
            assert!(saved.journal.pending().is_empty());
            assert!(saved.updates.is_none());
            assert!(saved.channel_pts.is_empty());
            assert!(saved.channel_recovery.is_empty());
        } else {
            assert_eq!(saved.journal.account_generation, generation);
            assert_eq!(saved.journal.pending().len(), 2);
            assert_eq!(saved.updates.as_ref().unwrap().pts, 200);
            assert_eq!(saved.channel_pts.get(&7), Some(&200));
            assert_eq!(saved.channel_recovery.iter().map(|entry| entry.chat_id).collect::<Vec<_>>(), vec![2, 9, 8]);
            assert_eq!(saved.channel_recovery.front().unwrap().due_at, 200);
        }
    }
}

#[test]
fn successful_sign_in_requires_pfs_before_business_io_without_blocking_local_authorization() {
    let fixture = Fixture::new();
    with_client_mut(fixture.handle, |state| {
        state.snapshot.auth_key = Some(vec![3; 256]);
        super::auth::complete_sign_in(state, 7)
    }).unwrap();
    let client = get_client(fixture.handle).unwrap();
    let permanent = client.data.lock().home_snapshot.clone();
    assert!(is_authorized(fixture.handle).unwrap());
    assert_eq!(client.main.lock().temp_expires_at, 1);
    let invoked = Cell::new(false);
    let failed = with_client_mut_binding(fixture.handle, |_| {
        invoked.set(true);
        Ok(())
    }, |_, _, _| Err(MtprotoError::Message("injected offline bind failure".into())));
    assert!(failed.is_err());
    assert!(!invoked.get());
    assert!(is_authorized(fixture.handle).unwrap());
    with_client_mut_binding(fixture.handle, |state| {
        invoked.set(true);
        assert_eq!(state.snapshot.auth_key.as_deref(), Some(&[4; 256][..]));
        assert_eq!(state.permanent.as_ref(), Some(&permanent));
        Ok(())
    }, |_, dc, offset| {
        let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
        snapshot.auth_key = Some(vec![4; 256]);
        snapshot.time_offset_micros = offset;
        Ok(crate::auth::bind_temp::BoundTemp { snapshot,
            expires_at: crate::auth::bind_temp::unix_now() + 3600 })
    }).unwrap();
    assert!(invoked.get());
    client.main.lock().temp_expires_at = 1;
    assert!(is_authorized(fixture.handle).unwrap());
    persist_updates_data(&client, permanent.session_id).unwrap();
    let saved = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
    assert_eq!(saved.snapshot, permanent);
    assert_eq!(saved.user_id, Some(7));
}

#[test]
fn media_binding_publishes_only_for_the_current_account_dc_master_and_open_client() {
    for mode in ["ok", "failure", "session", "account", "home_key", "master", "dc", "shutdown"] {
        let fixture = Fixture::new();
        let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
        client.data.lock().dc_auth_keys.insert(4, crate::session_file::DcAuthKey { key: vec![6; 256], salt: 9 });
        let owner = client.data.lock().home_snapshot.clone();
        let mut io = client.media[0].io.lock();
        let original = io.snapshot.clone();
        let result = crate::auth::bind_temp::ensure_dc_temp_with(&client, &mut io, 4,
            Some(owner.session_id), &crate::rpc::SystemClock, |key, dc, offset| {
                assert_eq!(key, &[6; 256]);
                match mode {
                    "failure" => return Err(MtprotoError::Message("injected media binding failure".into())),
                    "session" => client.data.lock().home_session_id = owner.session_id.wrapping_add(1),
                    "account" => client.data.lock().user_id = Some(8),
                    "home_key" => client.data.lock().home_auth_key = Some(vec![9; 256]),
                    "master" => client.data.lock().dc_auth_keys.get_mut(&4).unwrap().key = vec![9; 256],
                    "dc" => client.data.lock().home_dc = 5,
                    "shutdown" => client.connections.begin_shutdown(),
                    _ => {},
                }
                let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
                snapshot.auth_key = Some(vec![8; 256]);
                snapshot.time_offset_micros = offset;
                Ok(crate::auth::bind_temp::BoundTemp { snapshot,
                    expires_at: crate::auth::bind_temp::unix_now() + 3600 })
            });
        if mode == "ok" {
            assert!(result.unwrap());
            assert_eq!(io.snapshot.auth_key.as_deref(), Some(&[8; 256][..]));
            assert_eq!(client.data.lock().dc_temp_auth.get(&4).unwrap().snapshot, io.snapshot);
            let first = io.snapshot.clone();
            drop(io);
            let mut second = client.media[1].io.lock();
            crate::auth::bind_temp::ensure_dc_temp_with(&client, &mut second, 4,
                Some(owner.session_id), &crate::rpc::SystemClock,
                |_, _, _| panic!("valid cached binding must be reused")).unwrap();
            assert_eq!(second.snapshot.auth_key, first.auth_key);
            assert_ne!(second.snapshot.session_id, first.session_id);
        } else {
            assert!(result.is_err(), "{mode}");
            assert_eq!(io.snapshot, original);
            assert!(client.data.lock().dc_temp_auth.is_empty());
            let mut data = client.data.lock();
            data.home_dc = owner.dc_id;
            data.home_session_id = owner.session_id;
            data.home_auth_key = owner.auth_key.clone();
            data.user_id = Some(7);
        }
    }
}

#[test]
fn terminal_lane_error_survives_a_concurrent_main_commit_and_persists_the_permanent_owner() {
    let fixture = Fixture::new();
    let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
    let owner = client.data.lock().home_session_id;
    let error = MtprotoError::Message("RPC 401: SESSION_REVOKED".into());
    assert!(!mark_lane_session_dead(&client, owner.wrapping_add(1), &error));
    with_client_mut_binding(fixture.handle, |_| {
        assert!(mark_lane_session_dead(&client, owner, &error));
        Ok(())
    }, |_, _, _| panic!("terminal failure must not negotiate")).unwrap();
    assert!(client.data.lock().session_dead);
    persist_updates_data(&client, owner).unwrap();
    let saved = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
    assert!(saved.session_dead);
    assert_eq!(saved.snapshot.auth_key.as_deref(), Some(&[3; 256][..]));
    assert_eq!(saved.snapshot.session_id, owner);
}

#[test]
fn temporary_lane_rejection_preserves_dc_master_and_does_not_evict_a_newer_cached_key() {
    let fixture = Fixture::new();
    let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
    client.data.lock().dc_auth_keys.insert(4, crate::session_file::DcAuthKey { key: vec![6; 256], salt: 9 });
    let mut io = client.media[0].io.lock();
    io.snapshot.dc_id = 4;
    io.snapshot.auth_key = Some(vec![8; 256]);
    io.perm_key = Some(vec![6; 256]);
    io.temp_expires_at = crate::auth::bind_temp::unix_now() + 3600;
    client.data.lock().dc_temp_auth.insert(4, DcTempAuth {
        snapshot: io.snapshot.clone(), temp_expires_at: io.temp_expires_at, perm_key: vec![6; 256],
    });
    let error = MtprotoError::Message("MTProto transport error -404".into());
    assert!(expire_temporary_lane(&client, &mut io, &error));
    assert_eq!(io.temp_expires_at, 1);
    assert!(client.data.lock().dc_temp_auth.is_empty());
    let mut newer = io.snapshot.clone();
    newer.auth_key = Some(vec![9; 256]);
    client.data.lock().dc_temp_auth.insert(4, DcTempAuth {
        snapshot: newer, temp_expires_at: crate::auth::bind_temp::unix_now() + 3600, perm_key: vec![6; 256],
    });
    assert!(expire_temporary_lane(&client, &mut io, &error));
    let data = client.data.lock();
    assert!(!data.session_dead);
    assert_eq!(data.dc_auth_keys.get(&4).unwrap().key, vec![6; 256]);
    assert_eq!(data.dc_temp_auth.get(&4).unwrap().snapshot.auth_key.as_deref(), Some(&[9; 256][..]));
}

#[test]
fn upload_rejection_expires_the_lane_without_replaying_the_batch_or_revoking_login() {
    let fixture = Fixture::new();
    let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
    let home = client.main.lock().snapshot.clone();
    for lane in &client.upload {
        let mut io = lane.io.lock();
        io.snapshot = fork_session(&home);
        io.perm_key = Some(vec![3; 256]);
        io.temp_expires_at = crate::auth::bind_temp::unix_now() + 3600;
    }
    let payload = fixture.path.with_extension("payload");
    std::fs::write(&payload, [0_u8; 8]).unwrap();
    let item = crate::UploadItemDto {
        path: payload.to_string_lossy().into_owned(), kind: "document".into(),
        mime_type: String::new(), file_name: "fixture.bin".into(), caption: String::new(),
        entities_json: None, duration: 0, width: 0, height: 0, random_id: 7,
    };
    let calls = Cell::new(0);
    let result = save_items_with(&client, 1, &[item], |_, _| {
        calls.set(calls.get() + 1);
        Err(MtprotoError::Message("RPC 401: AUTH_KEY_UNREGISTERED".into()))
    });
    std::fs::remove_file(payload).unwrap();
    assert_eq!(result.err().unwrap().to_string(), "connection closed: temporary authorization renewal required");
    assert_eq!(calls.get(), 1);
    assert_eq!(client.upload.iter().filter(|lane| lane.io.lock().temp_expires_at == 1).count(), 1);
    assert!(!client.data.lock().session_dead);
    assert!(is_authorized(fixture.handle).unwrap());
}

#[test]
fn pfs_migration_binds_before_retry_and_rolls_back_on_binding_failure() {
    for mode in ["ok", "failure", "invalid"] {
        let fixture = Fixture::new();
        let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
        client.data.lock().dc_auth_keys.insert(4, crate::session_file::DcAuthKey { key: vec![6; 256], salt: 9 });
        let original = client.data.lock().home_snapshot.clone();
        let retry = Cell::new(false);
        let expires = crate::auth::bind_temp::unix_now() + 3600;
        let result = with_client_mut_binding(fixture.handle, |state| {
            ensure_ready_after_migrate_with_binding(state, MtprotoError::Message("MIGRATE_4".into()),
                |key, dc, offset| {
                    assert_eq!(key, &[6; 256]);
                    if mode == "failure" { return Err(MtprotoError::Message("offline".into())); }
                    let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
                    snapshot.auth_key = Some(vec![if mode == "invalid" { 6 } else { 8 }; 256]);
                    snapshot.time_offset_micros = offset;
                    Ok(crate::auth::bind_temp::BoundTemp { snapshot, expires_at: expires })
                })?;
            retry.set(true);
            assert_eq!(state.snapshot.auth_key.as_deref(), Some(&[8; 256][..]));
            assert_eq!(state.permanent.as_ref().unwrap().auth_key.as_deref(), Some(&[6; 256][..]));
            Ok(())
        }, |_, _, _| panic!("existing home binding must not renew"));
        if mode == "ok" {
            result.unwrap();
            assert!(retry.get());
            assert_eq!(client.main.lock().temp_expires_at, expires);
            let owner = client.data.lock().home_session_id;
            persist_updates_data(&client, owner).unwrap();
            let saved = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
            assert_eq!(saved.snapshot.auth_key.as_deref(), Some(&[6; 256][..]));
            assert_eq!(saved.snapshot.dc_id, 4);
            assert_eq!(saved.user_id, Some(7));
        } else {
            assert!(result.is_err());
            assert!(!retry.get());
            assert_eq!(client.data.lock().home_snapshot, original);
            assert_eq!(client.main.lock().snapshot.auth_key.as_deref(), Some(&[4; 256][..]));
        }
    }
}

#[test]
fn secondary_read_lane_distinguishes_temporary_rejection_from_account_revocation() {
    for revoked in [false, true] {
        let fixture = Fixture::new();
        let client = fixture.temporary(crate::auth::bind_temp::unix_now() + 3600);
        let owner = client.data.lock().home_session_id;
        let _policy = crate::transfer_policy::bind(client.policy.clone());
        scheduler::set_main_session_allowance(Some(2));
        scheduler::set_bound_extra_sessions(1);
        {
            let mut io = client.rpc[0].io.lock();
            io.snapshot.auth_key = Some(vec![5; 256]);
            io.perm_key = Some(vec![3; 256]);
            io.temp_expires_at = crate::auth::bind_temp::unix_now() + 3600;
        }
        let result = with_read_lane(fixture.handle, |state| {
            assert_eq!(state.snapshot.auth_key.as_deref(), Some(&[5; 256][..]));
            call_with_migrate(state, |_| Err::<(), _>(MtprotoError::Message(
                if revoked { "RPC 401: SESSION_REVOKED" } else { "RPC 401: AUTH_KEY_UNREGISTERED" }.into())))
        });
        let error = result.unwrap_err().to_string();
        if revoked {
            assert!(error.contains("SESSION_REVOKED"));
            assert!(client.data.lock().session_dead);
            persist_updates_data(&client, owner).unwrap();
            let saved = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
            assert!(saved.session_dead);
            assert_eq!(saved.snapshot.auth_key.as_deref(), Some(&[3; 256][..]));
        } else {
            assert_eq!(error, "connection closed: temporary authorization renewal required");
            assert_eq!(client.rpc[0].io.lock().temp_expires_at, 1);
            assert!(!client.data.lock().session_dead);
            assert!(is_authorized(fixture.handle).unwrap());
        }
    }
}

#[test]
fn missing_permanent_identity_cannot_publish_temporary_key() {
    let fixture = Fixture::new();
    let client = get_client(fixture.handle).unwrap();
    let home_id = client.data.lock().home_session_id;
    {
        let mut main = client.main.lock();
        main.snapshot.auth_key = Some(vec![4; 256]);
        main.temp_expires_at = 1;
        main.perm_key = Some(vec![3; 256]);
    }
    assert!(persist_updates_data(&client, home_id).is_err());
    assert!(!fixture.path.exists());
}

#[test]
fn permanent_sequence_state_survives_initial_pfs_activation_and_all_persist_paths() {
    let fixture = Fixture::new();
    with_client_mut(fixture.handle, |state| {
        state.user_id = Some(7);
        state.snapshot.auth_key = Some(vec![3; 256]);
        state.snapshot.server_salt = 123;
        state.snapshot.content_sequence = 11;
        state.snapshot.last_message_id = 400;
        state.snapshot.pending_acknowledgements.insert(1);
        state.snapshot.received_message_ids.insert(1, true);
        Ok(())
    }).unwrap();
    let client = get_client(fixture.handle).unwrap();
    let permanent = client.main.lock().snapshot.clone();
    {
        let mut main = client.main.lock();
        crate::auth::bind_temp::ensure_main_temp_with(&client, &mut main, true, &crate::rpc::SystemClock,
            |_, dc, offset| {
                let mut snapshot = Snapshot::new(dc, &mut OsRandom).unwrap();
                snapshot.auth_key = Some(vec![4; 256]);
                snapshot.time_offset_micros = offset;
                snapshot.content_sequence = 88;
                Ok(crate::auth::bind_temp::BoundTemp { snapshot,
                    expires_at: crate::auth::bind_temp::unix_now() + 3600 })
            }).unwrap();
    }
    with_client_mut_binding(fixture.handle, |state| {
        assert_eq!(state.snapshot.content_sequence, 88);
        assert_eq!(state.permanent.as_ref(), Some(&permanent));
        persist(state)
    }, |_, _, _| panic!("valid bound key must not renew")).unwrap();
    let direct = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
    assert_eq!(direct.snapshot, permanent);
    persist_updates_data(&client, permanent.session_id).unwrap();
    let background = FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
    assert_eq!(background.snapshot, permanent);
}

#[test]
fn expired_temporary_key_never_replaces_durable_login_identity() {
    for expires_at in [crate::auth::bind_temp::unix_now() + 3600, 1] {
        let fixture = Fixture::new();
        let client = get_client(fixture.handle).unwrap();
        let permanent = vec![3; 256];
        let home_id;
        {
            let mut main = client.main.lock();
            let mut data = client.data.lock();
            data.user_id = Some(7);
            data.home_auth_key = Some(permanent.clone());
            data.home_salt = 123;
            data.home_snapshot = main.snapshot.clone();
            data.home_snapshot.auth_key = Some(permanent.clone());
            data.home_snapshot.server_salt = 123;
            home_id = data.home_session_id;
            let mut temporary = fork_session(&main.snapshot);
            temporary.auth_key = Some(vec![4; 256]);
            temporary.server_salt = 456;
            temporary.content_sequence = 99;
            temporary.last_message_id = 400;
            temporary.pending_acknowledgements.insert(1);
            temporary.received_message_ids.insert(1, true);
            main.snapshot = temporary;
            main.temp_expires_at = expires_at;
            main.perm_key = Some(permanent.clone());
        }
        assert!(persist_updates_data(&client, home_id).unwrap());
        let restored = crate::session_file::FileSessionStore::new(&fixture.path).load().unwrap().unwrap();
        assert_eq!(restored.snapshot.auth_key.as_deref(), Some(permanent.as_slice()));
        assert_eq!(restored.snapshot.session_id, home_id);
        assert_eq!(restored.snapshot.server_salt, 123);
        assert_eq!(restored.snapshot.content_sequence, 0);
        assert_eq!(restored.snapshot.last_message_id, 0);
        assert!(restored.snapshot.pending_acknowledgements.is_empty());
        assert!(restored.snapshot.received_message_ids.is_empty());
        assert_eq!(restored.user_id, Some(7));
        let data = client.data.lock();
        assert_eq!(data.home_auth_key.as_deref(), Some(permanent.as_slice()));
        assert_eq!(data.home_session_id, home_id);
    }
}
