use crate::dialogs;
use crate::peers;
use crate::scheduler;
use crate::upload_rpc::UploadStaging;
use crate::{
    ChatDto, FolderDto, ForumTopicsPageDto, MessageDto, MtprotoError, NotifyExceptionDto,
    NotifySettingsDto, ProfileDto,
};

use super::*;

pub fn get_chats(handle: u64) -> Result<Vec<ChatDto>, MtprotoError> {
    with_read_lane(handle, |state| {
        let chats = crate::rpc::with_rpc_timeout_secs(8, || {
            call_with_migrate(state, |state| {
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
            })
        })?;
        persist(state)?;
        Ok(chats)
    })
}

pub fn update_folder(handle: u64, folder: FolderDto) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            dialogs::update_folder(&mut state.snapshot, state.api_id, &state.peers, &folder)
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn delete_folder(handle: u64, id: i32) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            dialogs::delete_folder(&mut state.snapshot, state.api_id, id)
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn update_folder_order(handle: u64, order: Vec<i32>) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            dialogs::update_folder_order(&mut state.snapshot, state.api_id, &order)
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn get_folders(handle: u64) -> Result<Vec<FolderDto>, MtprotoError> {
    with_read_lane(handle, |state| {
        let folders = crate::rpc::with_rpc_timeout_secs(8, || {
            call_with_migrate(state, |state| {
                dialogs::get_folders(&mut state.snapshot, state.api_id, &state.peers)
            })
        })?;
        persist(state)?;
        Ok(folders)
    })
}

pub fn get_group_admin_tags(handle: u64, chat_id: i64) -> Result<String, MtprotoError> {
    with_client_mut(handle, |state| {
        let tags = crate::rpc::with_rpc_timeout_secs(8, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    crate::profile::get_group_admin_tags(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        chat_id,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(tags)
    })
}

pub fn get_profile(handle: u64, peer_id: i64) -> Result<ProfileDto, MtprotoError> {
    with_read_lane(handle, |state| {
        // Extra read lane so full-user does not sit behind history on main.
        let profile = crate::rpc::with_rpc_timeout_secs(8, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, peer_id, |state| {
                    crate::profile::get_profile(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        &mut state.media,
                        state.user_id,
                        peer_id,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(profile)
    })
}

/// `messages.getSearchCounters` for the requested filters, as compact JSON.
pub fn get_search_counters(
    handle: u64,
    chat_id: i64,
    filters: Vec<String>,
) -> Result<String, MtprotoError> {
    with_read_lane(handle, |state| {
        let json = crate::rpc::with_rpc_timeout_secs(8, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    crate::messages::get_search_counters(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        chat_id,
                        &filters,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(json)
    })
}

/// https://core.telegram.org/method/channels.getParticipants
pub fn get_participants(
    handle: u64,
    chat_id: i64,
    filter: String,
    query: String,
    offset: i32,
    limit: i32,
) -> Result<String, MtprotoError> {
    with_read_lane(handle, |state| {
        let json = crate::rpc::with_rpc_timeout_secs(10, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    crate::profile::get_participants(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        &mut state.media,
                        chat_id,
                        &filter,
                        &query,
                        offset,
                        limit,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(json)
    })
}

/// https://core.telegram.org/method/messages.getCommonChats
pub fn get_common_chats(
    handle: u64,
    user_id: i64,
    max_id: i64,
    limit: i32,
) -> Result<String, MtprotoError> {
    with_read_lane(handle, |state| {
        let json = crate::rpc::with_rpc_timeout_secs(8, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, user_id, |state| {
                    crate::profile::get_common_chats(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        &mut state.media,
                        user_id,
                        max_id,
                        limit,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(json)
    })
}

pub fn load_more_chats(
    handle: u64,
    offset_date: i32,
    offset_id: i32,
    offset_peer_id: i64,
    folder_id: i32,
) -> Result<Vec<ChatDto>, MtprotoError> {
    with_read_lane(handle, |state| {
        let chats = call_with_migrate(state, |state| {
            with_peer_refresh(state, offset_peer_id, |state| {
                dialogs::get_dialogs(
                    &mut state.snapshot,
                    state.api_id,
                    &mut state.peers,
                    &mut state.media,
                    &mut state.channel_pts,
                    offset_date,
                    offset_id,
                    offset_peer_id,
                    // 0 is the main list, which the plain page already covers.
                    (folder_id > 0).then_some(folder_id),
                )
            })
        })?;
        persist(state)?;
        Ok(chats)
    })
}

pub(crate) fn with_peer_refresh<T>(
    state: &mut ClientState,
    chat_id: i64,
    mut f: impl FnMut(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    if chat_id != 0 {
        let missing = !state
            .peers
            .get(&chat_id)
            .is_some_and(peers::has_usable_access_hash);
        if missing {
            crate::perf::count("peer_refresh");
            let _ = refresh_dialogs(state);
        } else {
            crate::perf::count("peer_cached");
        }
    }
    match f(state) {
        Ok(value) => Ok(value),
        Err(err) if is_peer_refreshable(&err) => {
            refresh_dialogs(state)?;
            f(state)
        }
        Err(err) => Err(err),
    }
}

/// Re-fetch the constructor that issued the `file_reference` (message or full peer).

pub(crate) fn history_with_peer_refresh(
    state: &mut ClientState,
    chat_id: i64,
    limit: i32,
    offset_id: i32,
    offset_date: i32,
    add_offset: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_peer_refresh(state, chat_id, |state| {
        dialogs::get_history(
            &mut state.snapshot,
            state.api_id,
            &mut state.peers,
            &mut state.media,
            chat_id,
            limit,
            offset_id,
            offset_date,
            add_offset,
            state.user_id,
        )
    })
}

pub fn get_history(
    handle: u64,
    chat_id: i64,
    limit: i32,
    offset_id: i32,
    offset_date: i32,
    add_offset: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    if let Ok(client) = get_client(handle) {
        client.data.lock().last_history_chat_id = chat_id;
    }
    with_read_lane(handle, |state| {
        // Match Kotlin historyTimeoutMs=20s. Busy chats still have a mid-frame
        // rpc_result after ~100KiB of msg_container (emulator last_ctor=0x73f1f8dc).
        let messages = crate::rpc::with_rpc_timeout_secs(20, || {
            call_with_migrate(state, |state| {
                history_with_peer_refresh(state, chat_id, limit, offset_id, offset_date, add_offset)
            })
        })?;
        persist(state)?;
        Ok(messages)
    })
}

pub fn get_replies(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
    limit: i32,
    offset_id: i32,
    add_offset: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        let user_id = state.user_id;
        crate::rpc::with_rpc_timeout_secs(20, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    dialogs::get_replies(
                        &mut state.snapshot,
                        state.api_id,
                        &mut state.peers,
                        &mut state.media,
                        chat_id,
                        msg_id,
                        limit,
                        offset_id,
                        add_offset,
                        user_id,
                    )
                })
            })
        })
    })
}

pub fn get_forum_topics(
    handle: u64,
    chat_id: i64,
    offset_date: i32,
    offset_id: i32,
    offset_topic: i32,
    limit: i32,
) -> Result<ForumTopicsPageDto, MtprotoError> {
    with_client_mut(handle, |state| {
        crate::rpc::with_rpc_timeout_secs(15, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    dialogs::get_forum_topics(
                        &mut state.snapshot,
                        state.api_id,
                        &mut state.peers,
                        &mut state.media,
                        chat_id,
                        offset_date,
                        offset_id,
                        offset_topic,
                        limit,
                    )
                })
            })
        })
    })
}

pub fn get_forum_topics_by_id(
    handle: u64,
    chat_id: i64,
    topic_ids: Vec<i32>,
) -> Result<ForumTopicsPageDto, MtprotoError> {
    with_client_mut(handle, |state| {
        crate::rpc::with_rpc_timeout_secs(15, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    dialogs::get_forum_topics_by_id(
                        &mut state.snapshot,
                        state.api_id,
                        &mut state.peers,
                        &mut state.media,
                        chat_id,
                        &topic_ids,
                    )
                })
            })
        })
    })
}

pub fn search_messages(
    handle: u64,
    chat_id: i64,
    query: String,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        let messages = call_with_migrate(state, |state| {
            crate::messages::search_messages(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                chat_id,
                &query,
                limit,
            )
        })?;
        persist(state)?;
        Ok(messages)
    })
}

pub fn search_messages_filtered(
    handle: u64,
    chat_id: i64,
    query: String,
    filter: String,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        let messages = call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::search_messages_filtered(
                    &mut state.snapshot,
                    state.api_id,
                    &mut state.peers,
                    &mut state.media,
                    chat_id,
                    &query,
                    &filter,
                    offset_id,
                    add_offset,
                    limit,
                )
            })
        })?;
        persist(state)?;
        Ok(messages)
    })
}

pub fn contacts_search(
    handle: u64,
    query: String,
    limit: i32,
) -> Result<crate::ContactsSearchDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::search_rpc::contacts_search(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                &query,
                limit,
            )
        })?;
        persist(state)?;
        Ok(dto)
    })
}

pub fn search_global(
    handle: u64,
    query: String,
    offset_rate: i32,
    offset_peer_id: i64,
    offset_id: i32,
    limit: i32,
) -> Result<crate::GlobalMessageSearchDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let dto = call_with_migrate(state, |state| {
            crate::search_rpc::search_global(
                &mut state.snapshot,
                state.api_id,
                &mut state.peers,
                &mut state.media,
                &query,
                offset_rate,
                offset_peer_id,
                offset_id,
                limit,
            )
        })?;
        persist(state)?;
        Ok(dto)
    })
}

pub fn get_pinned_messages(
    handle: u64,
    chat_id: i64,
    limit: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        let messages = call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::get_pinned_messages(
                    &mut state.snapshot,
                    state.api_id,
                    &mut state.peers,
                    &mut state.media,
                    chat_id,
                    limit,
                )
            })
        })?;
        persist(state)?;
        Ok(messages)
    })
}

pub fn send_text_message(
    handle: u64,
    chat_id: i64,
    text: String,
    reply_to_msg_id: i32,
    entities_json: Option<String>,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    with_interactive_client_mut(handle, |state| {
        let sent = crate::rpc::with_rpc_timeout_secs(15, || {
            call_with_migrate(state, |state| {
                crate::messages::send_text(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    &mut state.media,
                    chat_id,
                    &text,
                    reply_to_msg_id,
                    entities_json.as_deref(),
                    top_msg_id,
                )
            })
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub(crate) fn upload_batches(
    staging: &mut UploadStaging,
    width: usize,
    mut accept: impl FnMut(&crate::upload_rpc::UploadBatch) -> Result<(), MtprotoError>,
) -> Result<(), MtprotoError> {
    while let Some(batch) = staging.next_batch(width) {
        accept(&batch?)?;
    }
    Ok(())
}

pub(crate) fn save_items(
    state: &mut ClientState,
    items: &[crate::UploadItemDto],
) -> Result<Vec<UploadStaging>, MtprotoError> {
    let width = pipeline_parts().clamp(1, crate::upload_rpc::MAX_PARTS_IN_FLIGHT);
    scheduler::with_class(scheduler::RequestClass::InteractiveMedia, || {
        let mut out = Vec::with_capacity(items.len());
        for item in items {
            let mut staging = crate::upload_rpc::open_staging(item)?;
            upload_batches(&mut staging, width, |batch| {
                crate::upload_rpc::save_batch(&mut state.snapshot, state.api_id, batch)
            })?;
            out.push(staging);
        }
        Ok(out)
    })
}

pub(crate) fn send_uploaded_on_state<T>(
    state: &mut ClientState,
    chat_id: i64,
    items: &[crate::UploadItemDto],
    send: impl Fn(&mut ClientState, &[UploadStaging]) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    call_with_migrate(state, |state| {
        with_peer_refresh(state, chat_id, |state| {
            let staging = save_items(state, items)?;
            scheduler::with_class(scheduler::RequestClass::InteractiveRead, || {
                send(state, &staging)
            })
        })
    })
}

pub fn send_photo_message(
    handle: u64,
    chat_id: i64,
    path: String,
    caption: String,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    entities_json: Option<String>,
) -> Result<MessageDto, MtprotoError> {
    let filename = std::path::Path::new(&path)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("photo.jpg")
        .to_string();
    let item = crate::UploadItemDto {
        path,
        kind: "photo".into(),
        mime_type: String::new(),
        file_name: filename,
        caption,
        duration: 0,
        width: 0,
        height: 0,
        random_id: 0,
    };
    with_interactive_client_mut(handle, |state| {
        let sent = crate::rpc::with_rpc_timeout_secs(300, || {
            send_uploaded_on_state(
                state,
                chat_id,
                std::slice::from_ref(&item),
                |state, staging| {
                    crate::upload_rpc::send_uploaded(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        &mut state.media,
                        chat_id,
                        &item,
                        crate::upload_rpc::staged_input_file(&item, &staging[0]),
                        reply_to_msg_id,
                        top_msg_id,
                        entities_json.as_deref(),
                    )
                },
            )
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub fn send_uploaded_media(
    handle: u64,
    chat_id: i64,
    item: crate::UploadItemDto,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    entities_json: Option<String>,
) -> Result<MessageDto, MtprotoError> {
    with_interactive_client_mut(handle, |state| {
        let sent = crate::rpc::with_rpc_timeout_secs(300, || {
            send_uploaded_on_state(
                state,
                chat_id,
                std::slice::from_ref(&item),
                |state, staging| {
                    crate::upload_rpc::send_uploaded(
                        &mut state.snapshot,
                        state.api_id,
                        &state.peers,
                        &mut state.media,
                        chat_id,
                        &item,
                        crate::upload_rpc::staged_input_file(&item, &staging[0]),
                        reply_to_msg_id,
                        top_msg_id,
                        entities_json.as_deref(),
                    )
                },
            )
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub fn send_uploaded_album(
    handle: u64,
    chat_id: i64,
    items: Vec<crate::UploadItemDto>,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_interactive_client_mut(handle, |state| {
        let sent = crate::rpc::with_rpc_timeout_secs(300, || {
            send_uploaded_on_state(state, chat_id, &items, |state, staging| {
                let inputs: Vec<_> = items
                    .iter()
                    .zip(staging)
                    .map(|(item, staged)| crate::upload_rpc::staged_input_file(item, staged))
                    .collect();
                crate::upload_rpc::send_album(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    &mut state.media,
                    chat_id,
                    &items,
                    &inputs,
                    reply_to_msg_id,
                    top_msg_id,
                )
            })
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub fn forward_messages(
    handle: u64,
    from_chat_id: i64,
    message_id: i32,
    to_chat_id: i64,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        let sent = crate::rpc::with_rpc_timeout_secs(15, || {
            call_with_migrate(state, |state| {
                crate::messages::forward_messages(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    &mut state.media,
                    from_chat_id,
                    message_id,
                    to_chat_id,
                )
            })
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub fn edit_text_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    text: String,
    entities_json: Option<String>,
) -> Result<MessageDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let sent = crate::rpc::with_rpc_timeout_secs(15, || {
            call_with_migrate(state, |state| {
                crate::messages::edit_text(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    &mut state.media,
                    chat_id,
                    message_id,
                    &text,
                    entities_json.as_deref(),
                )
            })
        })?;
        persist(state)?;
        Ok(sent)
    })
}

pub fn delete_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    revoke: bool,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        crate::rpc::with_rpc_timeout_secs(15, || {
            call_with_migrate(state, |state| {
                crate::messages::delete_message(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                    revoke,
                )
            })
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn read_history(handle: u64, chat_id: i64, max_id: i32) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::read_history(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    max_id,
                )
            })
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn mark_dialog_unread(handle: u64, chat_id: i64, unread: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::mark_dialog_unread(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    unread,
                )
            })
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn get_unread_mentions(
    handle: u64,
    chat_id: i64,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_read_lane(handle, |state| {
        let user_id = state.user_id;
        let messages = crate::rpc::with_rpc_timeout_secs(20, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    crate::messages::get_unread_mentions(
                        &mut state.snapshot,
                        state.api_id,
                        &mut state.peers,
                        &mut state.media,
                        chat_id,
                        offset_id,
                        add_offset,
                        limit,
                        top_msg_id,
                        user_id,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(messages)
    })
}

pub fn read_mentions(handle: u64, chat_id: i64, top_msg_id: i32) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::read_mentions(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    top_msg_id,
                )
            })
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn get_unread_reactions(
    handle: u64,
    chat_id: i64,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    top_msg_id: i32,
) -> Result<Vec<MessageDto>, MtprotoError> {
    with_read_lane(handle, |state| {
        let user_id = state.user_id;
        let messages = crate::rpc::with_rpc_timeout_secs(20, || {
            call_with_migrate(state, |state| {
                with_peer_refresh(state, chat_id, |state| {
                    crate::messages::get_unread_reactions(
                        &mut state.snapshot,
                        state.api_id,
                        &mut state.peers,
                        &mut state.media,
                        chat_id,
                        offset_id,
                        add_offset,
                        limit,
                        top_msg_id,
                        user_id,
                    )
                })
            })
        })?;
        persist(state)?;
        Ok(messages)
    })
}

pub fn read_reactions(handle: u64, chat_id: i64, top_msg_id: i32) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::read_reactions(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    top_msg_id,
                )
            })
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn read_discussion(
    handle: u64,
    chat_id: i64,
    msg_id: i32,
    read_max_id: i32,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                crate::messages::read_discussion(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    msg_id,
                    read_max_id,
                )
            })
        })?;
        persist(state)?;
        Ok(())
    })
}

pub fn update_status(handle: u64, offline: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::profile::update_status(&mut state.snapshot, state.api_id, offline)
        })
    })
}

pub fn register_device(
    handle: u64,
    token_type: i32,
    token: String,
    secret: Vec<u8>,
    no_muted: bool,
    app_sandbox: bool,
    other_uids: Vec<i64>,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::register_device(
                &mut state.snapshot,
                state.api_id,
                token_type,
                token.clone(),
                secret.clone(),
                no_muted,
                app_sandbox,
                other_uids.clone(),
            )
        })
    })
}

pub fn unregister_device(
    handle: u64,
    token_type: i32,
    token: String,
    other_uids: Vec<i64>,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::unregister_device(
                &mut state.snapshot,
                state.api_id,
                token_type,
                token.clone(),
                other_uids.clone(),
            )
        })
    })
}

pub fn get_notify_settings(
    handle: u64,
    peer_kind: String,
    chat_id: i64,
) -> Result<NotifySettingsDto, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::get_notify_settings(
                &mut state.snapshot,
                state.api_id,
                &state.peers,
                peer_kind.clone(),
                chat_id,
            )
        })
    })
}

pub fn update_notify_settings(
    handle: u64,
    peer_kind: String,
    chat_id: i64,
    show_previews: bool,
    silent: bool,
    mute_until: i32,
    stories_muted: bool,
    sound: String,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::update_notify_settings(
                &mut state.snapshot,
                state.api_id,
                &state.peers,
                peer_kind.clone(),
                chat_id,
                show_previews,
                silent,
                mute_until,
                stories_muted,
                sound.clone(),
            )
        })
    })
}

pub fn reset_notify_settings(handle: u64) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::reset_notify_settings(&mut state.snapshot, state.api_id)
        })
    })
}

pub fn set_contact_joined_silent(handle: u64, silent: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::set_contact_joined_silent(&mut state.snapshot, state.api_id, silent)
        })
    })
}

pub fn get_notify_exceptions(
    handle: u64,
    compare_sound: bool,
) -> Result<Vec<NotifyExceptionDto>, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::push_rpc::get_notify_exceptions(&mut state.snapshot, state.api_id, compare_sound)
        })
    })
}

pub fn set_typing(handle: u64, chat_id: i64, typing: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            crate::messages::set_typing(
                &mut state.snapshot,
                state.api_id,
                &state.peers,
                chat_id,
                typing,
            )
        })
    })
}
