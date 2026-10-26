use super::*;

#[test]
fn upload_owner_changes_stop_before_the_next_chunk_or_file_access() {
    let path = std::env::temp_dir().join(format!(
        "monogram-upload-owner-{}.session",
        std::process::id()
    ));
    let file = path.with_extension("bin");
    std::fs::write(&file, vec![1u8; 96 * 1024]).unwrap();
    let handle = create_client(1, "fixture".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    client.policy.own_file_part_kib(32);
    client.policy.own_pipeline_parts(1);
    let dc = client.main.lock().snapshot.dc_id;
    *client.policy.upload_rtt.lock() = Some((
        dc,
        std::time::Instant::now(),
        std::time::Duration::from_secs(1),
    ));
    let owner = {
        let data = client.data.lock();
        UploadOwner {
            generation: data.journal.account_generation,
            session_id: data.home_session_id,
        }
    };
    let item = crate::UploadItemDto {
        path: file.to_string_lossy().into(),
        kind: "document".into(),
        mime_type: String::new(),
        file_name: "fixture.bin".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 123,
    };
    let mut chunks = 0;
    let result = save_items_with_owner(&client, 1, &[item.clone()], Some(owner), |_, _| {
        chunks += 1;
        let mut data = client.data.lock();
        Arc::make_mut(&mut data.journal).account_generation = owner.generation.wrapping_add(1);
        Ok(())
    });
    assert!(result.is_err());
    assert_eq!(chunks, 1);
    let stale = save_items_with_owner(&client, 1, &[item], Some(owner), |_, _| {
        panic!("stale owner must not upload")
    });
    assert!(
        matches!(stale, Err(MtprotoError::Message(ref value)) if value == "request cancelled: session changed")
    );
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(file);
}

#[test]
fn upload_options_reject_unpersisted_ids_before_client_or_file_access() {
    let item = crate::UploadItemDto {
        path: "synthetic-unused".into(),
        kind: "photo".into(),
        mime_type: String::new(),
        file_name: "fixture.jpg".into(),
        caption: String::new(),
        entities_json: None,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    let result = send_uploaded_media_with_options(
        0,
        7,
        item.clone(),
        0,
        0,
        None,
        crate::SendOptions::default(),
    );
    assert!(
        matches!(result, Err(MtprotoError::Message(ref value)) if value.contains("distinct persisted random ids"))
    );
    let mut item = item;
    item.random_id = 123;
    assert!(
        send_uploaded_album_with_options(
            0,
            7,
            vec![item.clone(), item],
            0,
            0,
            crate::SendOptions::default()
        )
        .is_err()
    );
}
