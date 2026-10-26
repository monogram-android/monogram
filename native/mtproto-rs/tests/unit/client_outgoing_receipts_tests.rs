use super::*;

fn result(scheduled: bool) -> crate::OutgoingMessageResult {
    let message = serde_json::from_str(r#"{"chat_id":7,"id":37,"text":"fixture","date":100,"outgoing":true,"noforwards":false,"supports_streaming":false,"replies_count":0,"forum_topic":false}"#).unwrap();
    if scheduled {
        crate::OutgoingMessageResult::Scheduled {
            message: crate::ScheduledMessageDto {
                message,
                has_unsupported_content: false,
            },
        }
    } else {
        crate::OutgoingMessageResult::Sent { message }
    }
}

#[test]
fn outgoing_modes_and_random_ids_survive_restart_without_claiming_cursors() {
    for scheduled in [false, true] {
        let path = std::env::temp_dir().join(format!(
            "monogram-outcome-{}-{scheduled}.session",
            std::process::id()
        ));
        let _ = std::fs::remove_file(&path);
        let handle = create_client(1, "fixture".into(), path.to_string_lossy().into());
        let client = get_client(handle).unwrap();
        let generation = client.data.lock().journal.account_generation;
        let session_id = {
            let _main = client.main.lock();
            updates::stage_outgoing_result(&client, generation, 123, result(scheduled)).unwrap()
        };
        persist::persist_journal_sync(&client, session_id).unwrap();
        destroy_client(handle);
        let restarted = create_client(1, "fixture".into(), path.to_string_lossy().into());
        let batches = pending_update_batches(restarted).unwrap();
        assert_eq!(batches.len(), 1);
        assert!(batches[0].cursor_after.is_none());
        assert!(batches[0].channel_pts_after.is_empty());
        let crate::UpdateEventDto::OutgoingMessageAcknowledged { random_id, result } =
            &batches[0].events[0]
        else {
            panic!("receipt required")
        };
        assert_eq!(*random_id, 123);
        assert_eq!(
            matches!(result, crate::OutgoingMessageResult::Scheduled { .. }),
            scheduled
        );
        acknowledge_update_batch(restarted, generation, batches[0].batch_id).unwrap();
        assert!(pending_update_batches(restarted).unwrap().is_empty());
        destroy_client(restarted);
        let _ = std::fs::remove_file(path);
    }
}

#[test]
fn album_receipts_are_one_restart_durable_batch_and_invalid_sets_are_atomic() {
    let path = std::env::temp_dir().join(format!(
        "monogram-album-receipts-{}.session",
        std::process::id()
    ));
    let _ = std::fs::remove_file(&path);
    let handle = create_client(1, "fixture".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let generation = client.data.lock().journal.account_generation;
    for invalid in [
        vec![],
        vec![(123, result(true)), (0, result(true))],
        vec![(123, result(true)), (123, result(true))],
    ] {
        assert!(updates::stage_outgoing_results(&client, generation, invalid).is_err());
        assert!(client.data.lock().journal.batches.is_empty());
    }
    let mut second = result(true);
    if let crate::OutgoingMessageResult::Scheduled { message } = &mut second {
        message.message.id = 38;
    }
    let session = {
        let _main = client.main.lock();
        updates::stage_outgoing_results(
            &client,
            generation,
            vec![(123, result(true)), (456, second)],
        )
        .unwrap()
    };
    persist::persist_journal_sync(&client, session).unwrap();
    destroy_client(handle);
    let restarted = create_client(1, "fixture".into(), path.to_string_lossy().into());
    let batches = pending_update_batches(restarted).unwrap();
    assert_eq!(batches.len(), 1);
    assert_eq!(batches[0].events.len(), 2);
    assert!(batches[0].cursor_after.is_none());
    assert!(batches[0].channel_pts_after.is_empty());
    let ids = batches[0]
        .events
        .iter()
        .map(|event| match event {
            crate::UpdateEventDto::OutgoingMessageAcknowledged { random_id, .. } => *random_id,
            _ => panic!("receipt expected"),
        })
        .collect::<Vec<_>>();
    assert_eq!(ids, vec![123, 456]);
    acknowledge_update_batch(restarted, generation, batches[0].batch_id).unwrap();
    assert!(pending_update_batches(restarted).unwrap().is_empty());
    destroy_client(restarted);
    let _ = std::fs::remove_file(path);
}

#[test]
fn receipts_reject_stale_account_generation_zero_random_id_and_expired_session() {
    let path = std::env::temp_dir().join(format!(
        "monogram-outcome-lease-{}.session",
        std::process::id()
    ));
    let _ = std::fs::remove_file(&path);
    let handle = create_client(1, "fixture".into(), path.to_string_lossy().into());
    let client = get_client(handle).unwrap();
    let generation = client.data.lock().journal.account_generation;
    assert!(
        updates::stage_outgoing_result(&client, generation.wrapping_add(1), 123, result(true))
            .is_err()
    );
    assert!(updates::stage_outgoing_result(&client, generation, 0, result(true)).is_err());
    client.data.lock().session_dead = true;
    assert!(updates::stage_outgoing_result(&client, generation, 123, result(true)).is_err());
    assert!(client.data.lock().journal.batches.is_empty());
    destroy_client(handle);
    let _ = std::fs::remove_file(path);
}
