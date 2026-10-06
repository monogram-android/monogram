use super::*;

#[test]
fn duplicated_auth_key_is_unrecoverable() {
    assert!(is_unrecoverable_session(&MtprotoError::Message(
        "RPC 406: AUTH_KEY_DUPLICATED".into(),
    )));
    assert!(!is_unrecoverable_session(&MtprotoError::Message(
        "RPC timeout recv=139455".into(),
    )));
}

#[test]
fn unregistered_key_without_a_user_does_not_kill_the_login_session() {
    let unregistered = MtprotoError::Message("RPC 401: AUTH_KEY_UNREGISTERED".into());
    assert!(!kills_session(None, &unregistered));
    assert!(kills_session(Some(7), &unregistered));
    assert!(kills_session(
        None,
        &MtprotoError::Message("RPC 401: SESSION_REVOKED".into()),
    ));
}

#[test]
fn password_required_normalizes_tl_and_text_errors() {
    assert!(is_password_required(&MtprotoError::PasswordRequired));
    assert!(is_password_required(&MtprotoError::Message(
        "RPC 401: SESSION_PASSWORD_NEEDED".into(),
    )));
    assert!(!is_password_required(&MtprotoError::Message(
        "RPC 400: PASSWORD_HASH_INVALID".into(),
    )));
}

#[test]
fn file_reference_retry_requires_new_bytes() {
    let old = media_rpc::MediaLocation::Photo {
        id: 1,
        access_hash: 2,
        file_reference: vec![1, 2, 3],
        thumb_size: "m".into(),
        dc_id: 2,
    };
    let same = old.clone();
    let mut newer = old.clone();
    if let media_rpc::MediaLocation::Photo { file_reference, .. } = &mut newer {
        *file_reference = vec![9, 9, 9];
    }
    assert_eq!(
        media_rpc::location_token(&old),
        media_rpc::location_token(&same),
    );
    assert_ne!(
        media_rpc::location_token(&old),
        media_rpc::location_token(&newer),
    );
    assert!(media_rpc::is_file_reference_error(&MtprotoError::Message(
        "FILE_REFERENCE_EXPIRED".into(),
    )));
    // Retry is one getFile after source refresh, not gated on token equality.
}

#[test]
fn peer_refreshable_errors_are_detected() {
    assert!(is_peer_refreshable(&MtprotoError::Message(
        "unknown peer -100123; refresh chats first".into(),
    )));
    assert!(is_peer_refreshable(&MtprotoError::Message(
        "RPC 400: PEER_ID_INVALID".into(),
    )));
    assert!(!is_peer_refreshable(&MtprotoError::Message(
        "RPC timeout".into()
    )));
}

#[test]
fn fork_session_keeps_auth_and_new_session_id() {
    let mut home = Snapshot::new(2, &mut OsRandom).expect("home");
    home.auth_key = Some(vec![7_u8; 256]);
    home.server_salt = 99;
    home.time_offset_micros = 1_000;
    let forked = fork_session(&home);
    assert_eq!(forked.dc_id, 2);
    assert_eq!(forked.auth_key.as_ref().map(|k| k.len()), Some(256));
    assert_eq!(forked.server_salt, 99);
    assert_eq!(forked.time_offset_micros, 1_000);
    assert_ne!(forked.session_id, home.session_id);
}

#[test]
fn existing_auth_key_does_not_rewrite_snapshot_on_every_rpc() {
    let path =
        std::env::temp_dir().join(format!("mtproto-auth-no-write-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    with_client_mut(handle, |state| {
        state.snapshot.auth_key = Some(vec![7; 256]);
        ensure_auth_key(state)
    })
    .unwrap();
    assert!(!path.exists());
    destroy_client(handle);
}

#[test]
fn cached_connect_does_not_rewrite_session() {
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!("mtproto-cached-connect-{nonce}"));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("session.json");
    let expires = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
        + 3600;
    std::fs::write(
        dir.join("dc_txt.config"),
        format!("# dc_txt.config v2\nexpires {expires}\ntmp_sessions 1\n2 149.154.167.51:443\n"),
    )
    .unwrap();
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    with_client_mut(handle, |state| {
        state.snapshot.auth_key = Some(vec![7; 256]);
        state.user_id = Some(42);
        persist(state)
    })
    .unwrap();
    let before = std::fs::read(&path).unwrap();
    assert!(!before.is_empty());
    connect(handle).expect("cached connect");
    let after = std::fs::read(&path).unwrap();
    assert_eq!(
        after, before,
        "cached connect must not rewrite the session file"
    );
    destroy_client(handle);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn getfile_without_durable_change_does_not_rewrite_session() {
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let path = std::env::temp_dir().join(format!("mtproto-getfile-nopersist-{nonce}.json"));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    with_client_mut(handle, |state| {
        state.snapshot.auth_key = Some(vec![7; 256]);
        state.user_id = Some(42);
        persist(state)
    })
    .unwrap();
    let client = get_client(handle).unwrap();
    flush_persist(&client);
    let before = std::fs::read(&path).unwrap();
    with_client_mut(handle, |state| {
        state.snapshot.server_salt = state.snapshot.server_salt.wrapping_add(1);
        state.snapshot.time_offset_micros = state.snapshot.time_offset_micros.wrapping_add(1);
        Ok(())
    })
    .unwrap();
    flush_persist(&client);
    let after = std::fs::read(&path).unwrap();
    assert_eq!(
        after, before,
        "getFile-like RPC must not rewrite the session file"
    );
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}

#[test]
fn dead_session_reports_the_invalidating_error_not_a_fake_401() {
    let path =
        std::env::temp_dir().join(format!("mtproto-dead-reason-{}.json", std::process::id()));
    let handle = create_client(1, "hash".into(), path.to_string_lossy().into());
    with_client_mut(handle, |state| {
        state.session_dead = true;
        state.session_dead_reason = Some("AUTH_KEY_DUPLICATED".into());
        let err = reject_if_dead(state).expect_err("a dead session must refuse RPCs");
        assert!(
            matches!(&err, MtprotoError::Message(message)
                    if message == "session invalidated: AUTH_KEY_DUPLICATED"),
            "unexpected error: {err}"
        );
        // A local gate must not be mistakable for a fresh server rejection.
        assert!(!err.to_string().contains("RPC 401"));
        assert!(!err.to_string().contains("AUTH_KEY_UNREGISTERED"));
        Ok(())
    })
    .expect("dead session probe");
    destroy_client(handle);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn session_token_keeps_only_catalog_tokens() {
    assert_eq!(
        session_dead_token(&MtprotoError::Message(
            "RPC 406: AUTH_KEY_DUPLICATED".into()
        )),
        "AUTH_KEY_DUPLICATED"
    );
    assert_eq!(
        session_dead_token(&MtprotoError::Message(
            "RPC 401: AUTH_KEY_UNREGISTERED".into()
        )),
        "AUTH_KEY_UNREGISTERED"
    );
    assert_eq!(
        session_dead_token(&MtprotoError::Message(
            "decode failed for +1 555 0100 secret note".into()
        )),
        "UNKNOWN"
    );
    assert_eq!(
        session_dead_token(&MtprotoError::RegistrationRequired),
        "UNKNOWN"
    );
}
