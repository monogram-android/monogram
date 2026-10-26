use super::management::enqueue_response;
use super::{ClientState, call_with_migrate, with_client_mut};
use crate::MtprotoError;
use crate::peers::{input_peer_from_cached, require_usable_peer, vector_boxed_items};
#[cfg(test)]
#[path = "../../tests/unit/shared_folders_tests.rs"]
mod tests;

use tellers_mtproto::latest::api::*;

pub fn export_invite(
    handle: u64,
    filter_id: i32,
    title: &str,
    chat_ids: &[i64],
) -> Result<ChatlistsExportedChatlistInvite, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        let peers = peer_list(state, chat_ids)?;
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsExportChatlistInviteRequest {
                chatlist: Box::new(chatlist.clone()),
                title: title.to_owned(),
                peers,
            },
        )
    })
}

/// None leaves a field unchanged; an empty title explicitly removes the label.
pub fn edit_invite(
    handle: u64,
    filter_id: i32,
    slug: &str,
    title: Option<String>,
    chat_ids: Option<Vec<i64>>,
) -> Result<ExportedChatlistInvite, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    validate_slug(slug)?;
    if title.is_none() && chat_ids.is_none() {
        return Err(MtprotoError::Message("an invite change is required".into()));
    }
    call(handle, |state| {
        let peers = chat_ids
            .as_ref()
            .map(|ids| peer_list(state, ids))
            .transpose()?;
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsEditExportedInviteRequest {
                flags: (u32::from(title.is_some()) << 1) | (u32::from(peers.is_some()) << 2),
                chatlist: Box::new(chatlist.clone()),
                slug: slug.to_owned(),
                title: title.clone(),
                peers,
            },
        )
    })
}

pub fn delete_invite(handle: u64, filter_id: i32, slug: &str) -> Result<(), MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    validate_slug(slug)?;
    call(handle, |state| {
        let response: Bool = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsDeleteExportedInviteRequest {
                chatlist: Box::new(chatlist.clone()),
                slug: slug.to_owned(),
            },
        )?;
        confirm(response)
    })
}

pub fn get_invites(handle: u64, filter_id: i32) -> Result<ChatlistsExportedInvites, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        let response: ChatlistsExportedInvites = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsGetExportedInvitesRequest {
                chatlist: Box::new(chatlist.clone()),
            },
        )?;
        let ChatlistsExportedInvites::ChatlistsExportedInvites(result) = &response;
        cache_peers(state, &result.users, &result.chats);
        Ok(response)
    })
}

/// Preview also caches full access hashes needed to join previously unknown chats.
pub fn check_invite(handle: u64, slug: &str) -> Result<ChatlistsChatlistInvite, MtprotoError> {
    validate_slug(slug)?;
    call(handle, |state| {
        let response: ChatlistsChatlistInvite = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsCheckChatlistInviteRequest {
                slug: slug.to_owned(),
            },
        )?;
        match &response {
            ChatlistsChatlistInvite::ChatlistsChatlistInvite(result) => {
                cache_peers(state, &result.users, &result.chats)
            }
            ChatlistsChatlistInvite::ChatlistsChatlistInviteAlready(result) => {
                cache_peers(state, &result.users, &result.chats)
            }
        }
        Ok(response)
    })
}

pub fn join_invite(
    handle: u64,
    slug: &str,
    selected_chat_ids: &[i64],
) -> Result<Updates, MtprotoError> {
    validate_slug(slug)?;
    call(handle, |state| {
        let peers = peer_list(state, selected_chat_ids)?;
        let response: Updates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsJoinChatlistInviteRequest {
                slug: slug.to_owned(),
                peers,
            },
        )?;
        enqueue_response(state.snapshot.dc_id, &response)?;
        Ok(response)
    })
}

pub fn get_updates(handle: u64, filter_id: i32) -> Result<ChatlistsChatlistUpdates, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        let response: ChatlistsChatlistUpdates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsGetChatlistUpdatesRequest {
                chatlist: Box::new(chatlist.clone()),
            },
        )?;
        let ChatlistsChatlistUpdates::ChatlistsChatlistUpdates(result) = &response;
        cache_peers(state, &result.users, &result.chats);
        Ok(response)
    })
}

pub fn join_updates(
    handle: u64,
    filter_id: i32,
    selected_chat_ids: &[i64],
) -> Result<Updates, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        let peers = peer_list(state, selected_chat_ids)?;
        let response: Updates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsJoinChatlistUpdatesRequest {
                chatlist: Box::new(chatlist.clone()),
                peers,
            },
        )?;
        enqueue_response(state.snapshot.dc_id, &response)?;
        Ok(response)
    })
}

pub fn hide_updates(handle: u64, filter_id: i32) -> Result<(), MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        let response: Bool = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsHideChatlistUpdatesRequest {
                chatlist: Box::new(chatlist.clone()),
            },
        )?;
        confirm(response)
    })
}

pub fn leave_suggestions(handle: u64, filter_id: i32) -> Result<Vector<Box<Peer>>, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsGetLeaveChatlistSuggestionsRequest {
                chatlist: Box::new(chatlist.clone()),
            },
        )
    })
}

/// Only explicitly selected chats are left. An empty selection keeps membership
/// in every chat while removing the shared folder.
pub fn leave(handle: u64, filter_id: i32, chats_to_leave: &[i64]) -> Result<Updates, MtprotoError> {
    let chatlist = chatlist(filter_id)?;
    call(handle, |state| {
        let peers = peer_list(state, chats_to_leave)?;
        let response: Updates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChatlistsLeaveChatlistRequest {
                chatlist: Box::new(chatlist.clone()),
                peers,
            },
        )?;
        enqueue_response(state.snapshot.dc_id, &response)?;
        Ok(response)
    })
}

pub fn get_filters(handle: u64) -> Result<MessagesDialogFilters, MtprotoError> {
    call(handle, |state| {
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesGetDialogFiltersRequest {},
        )
    })
}

/// Keeps the caller's regular/shared filter variant and all of its fields.
/// None deletes the filter; peer memberships are unaffected.
pub fn set_filter(
    handle: u64,
    filter_id: i32,
    filter: Option<DialogFilter>,
) -> Result<(), MtprotoError> {
    chatlist(filter_id)?;
    if let Some(filter) = &filter {
        let id = match filter {
            DialogFilter::DialogFilter(value) => value.id,
            DialogFilter::DialogFilterChatlist(value) => value.id,
            _ => {
                return Err(MtprotoError::Message(
                    "a custom dialog filter is required".into(),
                ));
            }
        };
        if id != filter_id {
            return Err(MtprotoError::Message(
                "dialog filter id does not match".into(),
            ));
        }
    }
    call(handle, |state| {
        let response: Bool = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesUpdateDialogFilterRequest {
                flags: u32::from(filter.is_some()),
                id: filter_id,
                filter: filter.clone().map(Box::new),
            },
        )?;
        confirm(response)
    })
}

pub fn reorder_filters(handle: u64, order: &[i32]) -> Result<(), MtprotoError> {
    let mut seen = std::collections::HashSet::new();
    if order
        .iter()
        .any(|id| *id < 0 || *id == 1 || !seen.insert(*id))
    {
        return Err(MtprotoError::Message("invalid dialog filter order".into()));
    }
    let count = order
        .len()
        .try_into()
        .map_err(|_| MtprotoError::Message("too many filters".into()))?;
    call(handle, |state| {
        let response: Bool = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesUpdateDialogFiltersOrderRequest {
                order: Box::new(Vector::Vector(VectorConstructor {
                    field_0: count,
                    field_1: order.to_vec(),
                })),
            },
        )?;
        confirm(response)
    })
}

fn call<T>(
    handle: u64,
    operation: impl Fn(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| operation(state))
    })
}

fn chatlist(filter_id: i32) -> Result<InputChatlist, MtprotoError> {
    if filter_id <= 1 {
        return Err(MtprotoError::Message(
            "a custom folder id is required".into(),
        ));
    }
    Ok(InputChatlist::InputChatlistDialogFilter(
        InputChatlistDialogFilterConstructor { filter_id },
    ))
}

fn validate_slug(slug: &str) -> Result<(), MtprotoError> {
    if slug.trim().is_empty() {
        return Err(MtprotoError::Message("an invite slug is required".into()));
    }
    Ok(())
}

fn peer_list(
    state: &ClientState,
    chat_ids: &[i64],
) -> Result<Box<Vector<Box<InputPeer>>>, MtprotoError> {
    let mut seen = std::collections::HashSet::new();
    let mut peers = Vec::with_capacity(chat_ids.len());
    for id in chat_ids {
        if !seen.insert(*id) {
            return Err(MtprotoError::Message("duplicate chat selection".into()));
        }
        peers.push(Box::new(input_peer_from_cached(require_usable_peer(
            &state.peers,
            *id,
        )?)));
    }
    Ok(Box::new(Vector::Vector(VectorConstructor {
        field_0: peers
            .len()
            .try_into()
            .map_err(|_| MtprotoError::Message("too many chats".into()))?,
        field_1: peers,
    })))
}

fn cache_peers(state: &mut ClientState, users: &Vector<Box<User>>, chats: &Vector<Box<Chat>>) {
    crate::dialogs::cache_from_users_chats(
        &mut state.peers,
        &mut state.media,
        vector_boxed_items(users).cloned(),
        vector_boxed_items(chats).cloned(),
    );
}

fn confirm(response: Bool) -> Result<(), MtprotoError> {
    match response {
        Bool::BoolTrue(_) => Ok(()),
        Bool::BoolFalse(_) => Err(MtprotoError::Message(
            "shared folder change was not applied".into(),
        )),
    }
}
