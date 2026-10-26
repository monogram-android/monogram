use tellers_mtproto::latest::api::{
    Bool, ChannelsDeleteHistoryRequest, ChannelsLeaveChannelRequest, FoldersEditPeerFoldersRequest,
    InputChannel, InputChannelConstructor, InputDialogPeer, InputDialogPeerConstructor,
    InputFolderPeer, InputFolderPeerConstructor, InputPeer, InputUser, InputUserSelfConstructor,
    MessagesAffectedHistory, MessagesDeleteChatUserRequest, MessagesDeleteHistoryRequest,
    MessagesToggleDialogPinRequest, MessagesUpdatePinnedMessageRequest, True, TrueConstructor,
    Updates, UpdatesTooLongConstructor, Vector, VectorConstructor,
};

use crate::MtprotoError;
use crate::peers::{PeerKind, input_peer_from_cached, require_usable_peer};

use super::{call_with_migrate, with_client_mut};

/// Pins or unpins a message. `one_sided` applies only to private conversations.
pub fn pin_message(
    handle: u64,
    chat_id: i64,
    message_id: i32,
    pinned: bool,
    silent: bool,
    one_sided: bool,
) -> Result<(), MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("invalid message id".into()));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            if one_sided && chat_id < 0 {
                return Err(MtprotoError::Message(
                    "one-sided pins require a user peer".into(),
                ));
            }
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                pin_request(
                    input_peer_from_cached(peer),
                    message_id,
                    pinned,
                    silent,
                    one_sided,
                ),
            )?;
            enqueue_response(state.snapshot.dc_id, &response)
        })
    })
}

pub fn pin_chat(handle: u64, chat_id: i64, pinned: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            let response: Bool = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesToggleDialogPinRequest {
                    flags: u32::from(pinned),
                    pinned: flag(pinned),
                    peer: Box::new(InputDialogPeer::InputDialogPeer(
                        InputDialogPeerConstructor {
                            peer: Box::new(input_peer_from_cached(peer)),
                        },
                    )),
                },
            )?;
            match response {
                Bool::BoolTrue(_) => Ok(()),
                Bool::BoolFalse(_) => {
                    Err(MtprotoError::Message("dialog pin was not applied".into()))
                }
            }
        })
    })
}

pub fn archive_chat(handle: u64, chat_id: i64, archived: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                FoldersEditPeerFoldersRequest {
                    folder_peers: Box::new(Vector::Vector(VectorConstructor {
                        field_0: 1,
                        field_1: vec![Box::new(InputFolderPeer::InputFolderPeer(
                            InputFolderPeerConstructor {
                                peer: Box::new(input_peer_from_cached(peer)),
                                folder_id: i32::from(archived),
                            },
                        ))],
                    })),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)
        })
    })
}

/// Leaves a channel/megagroup or basic group without destroying it.
pub fn leave_chat(handle: u64, chat_id: i64) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            let response: Updates = match peer.kind {
                PeerKind::Channel => crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    ChannelsLeaveChannelRequest {
                        channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                            channel_id: peer.id,
                            access_hash: peer.access_hash,
                        })),
                    },
                )?,
                PeerKind::Chat => crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    MessagesDeleteChatUserRequest {
                        flags: 0,
                        revoke_history: None,
                        chat_id: peer.id,
                        user_id: Box::new(InputUser::InputUserSelf(InputUserSelfConstructor {})),
                    },
                )?,
                _ => return Err(MtprotoError::Message("cannot leave a user dialog".into())),
            };
            enqueue_response(state.snapshot.dc_id, &response)
        })
    })
}

/// A nonzero server offset requires another call with the same history boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HistoryDeletion {
    pub more: bool,
}

pub fn clear_history(
    handle: u64,
    chat_id: i64,
    max_id: i32,
    revoke: bool,
) -> Result<HistoryDeletion, MtprotoError> {
    change_history(handle, chat_id, max_id, revoke, true)
}

/// Removes a user/basic-group dialog's history; leaving membership is separate.
pub fn delete_dialog(
    handle: u64,
    chat_id: i64,
    max_id: i32,
    revoke: bool,
) -> Result<HistoryDeletion, MtprotoError> {
    change_history(handle, chat_id, max_id, revoke, false)
}

fn change_history(
    handle: u64,
    chat_id: i64,
    max_id: i32,
    revoke: bool,
    just_clear: bool,
) -> Result<HistoryDeletion, MtprotoError> {
    if max_id <= 0 {
        return Err(MtprotoError::Message(
            "an explicit positive history boundary is required".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            if peer.kind == PeerKind::Channel {
                if !just_clear {
                    return Err(MtprotoError::Message(
                        "leave a channel instead of deleting its dialog history".into(),
                    ));
                }
                let response: Updates = crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    ChannelsDeleteHistoryRequest {
                        flags: u32::from(revoke),
                        for_everyone: flag(revoke),
                        channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                            channel_id: peer.id,
                            access_hash: peer.access_hash,
                        })),
                        max_id,
                    },
                )?;
                enqueue_response(state.snapshot.dc_id, &response)?;
                return Ok(HistoryDeletion { more: false });
            }
            let response: MessagesAffectedHistory = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                history_request(input_peer_from_cached(peer), max_id, revoke, just_clear),
            )?;
            let MessagesAffectedHistory::MessagesAffectedHistory(affected) = response;
            // Recover through getDifference rather than advancing past unseen
            // updates from an affectedHistory response without journaling them.
            enqueue_response(
                state.snapshot.dc_id,
                &Updates::UpdatesTooLong(UpdatesTooLongConstructor {}),
            )?;
            Ok(HistoryDeletion {
                more: affected.offset > 0,
            })
        })
    })
}

fn history_request(
    peer: InputPeer,
    max_id: i32,
    revoke: bool,
    just_clear: bool,
) -> MessagesDeleteHistoryRequest {
    MessagesDeleteHistoryRequest {
        flags: u32::from(just_clear) | (u32::from(revoke) << 1),
        just_clear: flag(just_clear),
        revoke: flag(revoke),
        peer: Box::new(peer),
        max_id,
        min_date: None,
        max_date: None,
    }
}

fn flag(enabled: bool) -> Option<Box<True>> {
    enabled.then(|| Box::new(True::True(TrueConstructor {})))
}

fn pin_request(
    peer: InputPeer,
    message_id: i32,
    pinned: bool,
    silent: bool,
    one_sided: bool,
) -> MessagesUpdatePinnedMessageRequest {
    MessagesUpdatePinnedMessageRequest {
        flags: u32::from(silent) | (u32::from(!pinned) << 1) | (u32::from(one_sided) << 2),
        silent: flag(silent),
        unpin: flag(!pinned),
        pm_oneside: flag(one_sided),
        peer: Box::new(peer),
        id: message_id,
    }
}

// RPC-result updates must pass through the normal cursor/journal pipeline, not
// advance cursors here or disappear when the main transport returns to its lane.
pub(super) fn cache_response_peers(state: &mut super::ClientState, updates: &Updates) {
    let (users, chats) = super::membership::updates_users_chats(updates);
    crate::dialogs::cache_from_users_chats(
        &mut state.peers,
        &mut state.media,
        users.into_iter(),
        chats.into_iter(),
    );
}

pub(crate) fn enqueue_response(dc_id: i32, updates: &Updates) -> Result<(), MtprotoError> {
    let mut encoder = tellers_mtproto::codec::Encoder::new();
    tellers_mtproto::codec::TlEncode::encode(updates, &mut encoder)
        .map_err(|error| MtprotoError::Message(error.to_string()))?;
    crate::rpc::queue_rpc_updates(dc_id, encoder.into_bytes())
}

#[cfg(test)]
#[path = "../../tests/unit/client_management_tests.rs"]
mod tests;
