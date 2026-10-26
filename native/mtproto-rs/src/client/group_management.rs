use tellers_mtproto::latest::api::{
    Bool, BoolFalseConstructor, BoolTrueConstructor, ChannelsCreateChannelRequest,
    ChannelsEditAdminRequest, ChannelsEditBannedRequest, ChannelsEditPhotoRequest,
    ChannelsEditTitleRequest, ChannelsInviteToChannelRequest, ChannelsToggleForumRequest,
    ChannelsUpdateUsernameRequest, ChatAdminRights, ChatBannedRights, InputChannel,
    InputChannelConstructor, InputChatPhoto, InputCheckPasswordSrp, InputPeer, InputUser,
    InputUserConstructor, MessagesAddChatUserRequest, MessagesCreateChatRequest,
    MessagesDeleteChatUserRequest, MessagesEditChatAboutRequest, MessagesEditChatAdminRequest,
    MessagesEditChatCreatorRequest, MessagesEditChatDefaultBannedRightsRequest,
    MessagesEditChatPhotoRequest, MessagesEditChatTitleRequest, MessagesInvitedUsers, True,
    TrueConstructor, Updates, Vector, VectorConstructor,
};

use super::management::enqueue_response;
use super::{call_with_migrate, with_client_mut};
use crate::MtprotoError;
use crate::peers::{PeerKind, input_peer_from_cached, require_usable_peer};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChannelKind {
    Broadcast,
    Megagroup,
    Forum,
}

/// Returns missing invitees as well as updates so partial membership success is
/// visible to callers. Creation is not replayed after an uncertain disconnect.
pub fn create_group(
    handle: u64,
    title: &str,
    member_ids: &[i64],
) -> Result<MessagesInvitedUsers, MtprotoError> {
    validate_title(title)?;
    if member_ids.is_empty() || member_ids.iter().any(|id| *id <= 0) {
        return Err(MtprotoError::Message(
            "group members must be user peers".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let users = member_ids
                .iter()
                .map(|id| user_peer(state, *id).map(Box::new))
                .collect::<Result<Vec<_>, MtprotoError>>()?;
            let response: MessagesInvitedUsers = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesCreateChatRequest {
                    flags: 0,
                    users: Box::new(Vector::Vector(VectorConstructor {
                        field_0: users
                            .len()
                            .try_into()
                            .map_err(|_| MtprotoError::Message("too many group members".into()))?,
                        field_1: users,
                    })),
                    title: title.to_owned(),
                    ttl_period: None,
                },
            )?;
            let MessagesInvitedUsers::MessagesInvitedUsers(result) = &response;
            super::management::cache_response_peers(state, &result.updates);
            enqueue_response(state.snapshot.dc_id, &result.updates)?;
            Ok(response)
        })
    })
}

pub fn create_channel(
    handle: u64,
    title: &str,
    about: &str,
    kind: ChannelKind,
) -> Result<Updates, MtprotoError> {
    validate_title(title)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                channel_request(title, about, kind),
            )?;
            super::management::cache_response_peers(state, &response);
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn invite_channel_members(
    handle: u64,
    chat_id: i64,
    member_ids: &[i64],
) -> Result<MessagesInvitedUsers, MtprotoError> {
    if member_ids.is_empty() {
        return Err(MtprotoError::Message(
            "at least one member is required".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let channel = channel_peer(state, chat_id)?;
            let users = member_ids
                .iter()
                .map(|id| user_peer(state, *id).map(Box::new))
                .collect::<Result<Vec<_>, _>>()?;
            let response: MessagesInvitedUsers = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                ChannelsInviteToChannelRequest {
                    channel: Box::new(channel),
                    users: Box::new(Vector::Vector(VectorConstructor {
                        field_0: users
                            .len()
                            .try_into()
                            .map_err(|_| MtprotoError::Message("too many members".into()))?,
                        field_1: users,
                    })),
                },
            )?;
            let MessagesInvitedUsers::MessagesInvitedUsers(result) = &response;
            enqueue_response(state.snapshot.dc_id, &result.updates)?;
            Ok(response)
        })
    })
}

pub fn add_group_member(
    handle: u64,
    chat_id: i64,
    user_id: i64,
    forward_limit: i32,
) -> Result<MessagesInvitedUsers, MtprotoError> {
    if !(0..=100).contains(&forward_limit) {
        return Err(MtprotoError::Message(
            "forward history limit must be between 0 and 100".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let group_id = basic_group_id(state, chat_id)?;
            let user = user_peer(state, user_id)?;
            let response: MessagesInvitedUsers = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesAddChatUserRequest {
                    chat_id: group_id,
                    user_id: Box::new(user),
                    fwd_limit: forward_limit,
                },
            )?;
            let MessagesInvitedUsers::MessagesInvitedUsers(result) = &response;
            enqueue_response(state.snapshot.dc_id, &result.updates)?;
            Ok(response)
        })
    })
}

pub fn remove_group_member(
    handle: u64,
    chat_id: i64,
    user_id: i64,
    revoke_history: bool,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let group_id = basic_group_id(state, chat_id)?;
            let user = user_peer(state, user_id)?;
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesDeleteChatUserRequest {
                    flags: u32::from(revoke_history),
                    revoke_history: flag(revoke_history),
                    chat_id: group_id,
                    user_id: Box::new(user),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn set_channel_admin(
    handle: u64,
    chat_id: i64,
    user_id: i64,
    rights: ChatAdminRights,
    rank: Option<String>,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let channel = channel_peer(state, chat_id)?;
            let user = user_peer(state, user_id)?;
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                ChannelsEditAdminRequest {
                    flags: u32::from(rank.is_some()),
                    channel: Box::new(channel),
                    user_id: Box::new(user),
                    admin_rights: Box::new(rights.clone()),
                    rank: rank.clone(),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn set_group_admin(
    handle: u64,
    chat_id: i64,
    user_id: i64,
    is_admin: bool,
) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let group_id = basic_group_id(state, chat_id)?;
            let user = user_peer(state, user_id)?;
            let response: Bool = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesEditChatAdminRequest {
                    chat_id: group_id,
                    user_id: Box::new(user),
                    is_admin: Box::new(if is_admin {
                        Bool::BoolTrue(BoolTrueConstructor {})
                    } else {
                        Bool::BoolFalse(BoolFalseConstructor {})
                    }),
                },
            )?;
            confirm_change(response)
        })
    })
}

/// The rights object controls ban/restriction/unban and its expiry. Telegram
/// remains authoritative about which restrictions the caller may grant.
pub fn set_channel_restrictions(
    handle: u64,
    chat_id: i64,
    participant_id: i64,
    rights: ChatBannedRights,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let channel = channel_peer(state, chat_id)?;
            let participant =
                input_peer_from_cached(require_usable_peer(&state.peers, participant_id)?);
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                ChannelsEditBannedRequest {
                    channel: Box::new(channel),
                    participant: Box::new(participant),
                    banned_rights: Box::new(rights.clone()),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn set_title(handle: u64, chat_id: i64, title: &str) -> Result<Updates, MtprotoError> {
    validate_title(title)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            let response: Updates = match peer.kind {
                PeerKind::Chat => crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    MessagesEditChatTitleRequest {
                        chat_id: peer.id,
                        title: title.to_owned(),
                    },
                )?,
                PeerKind::Channel => {
                    let channel = channel_peer(state, chat_id)?;
                    crate::api_invoke::invoke_api(
                        &mut state.snapshot,
                        state.api_id,
                        ChannelsEditTitleRequest {
                            channel: Box::new(channel),
                            title: title.to_owned(),
                        },
                    )?
                }
                _ => {
                    return Err(MtprotoError::Message(
                        "a group or channel is required".into(),
                    ));
                }
            };
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn set_photo(
    handle: u64,
    chat_id: i64,
    photo: InputChatPhoto,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = require_usable_peer(&state.peers, chat_id)?;
            let response: Updates = match peer.kind {
                PeerKind::Chat => crate::api_invoke::invoke_api(
                    &mut state.snapshot,
                    state.api_id,
                    MessagesEditChatPhotoRequest {
                        chat_id: peer.id,
                        photo: Box::new(photo.clone()),
                    },
                )?,
                PeerKind::Channel => {
                    let channel = channel_peer(state, chat_id)?;
                    crate::api_invoke::invoke_api(
                        &mut state.snapshot,
                        state.api_id,
                        ChannelsEditPhotoRequest {
                            channel: Box::new(channel),
                            photo: Box::new(photo.clone()),
                        },
                    )?
                }
                _ => {
                    return Err(MtprotoError::Message(
                        "a group or channel is required".into(),
                    ));
                }
            };
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn set_about(handle: u64, chat_id: i64, about: &str) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            let response: Bool = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesEditChatAboutRequest {
                    peer: Box::new(peer),
                    about: about.to_owned(),
                },
            )?;
            confirm_change(response)
        })
    })
}

/// An empty username removes the current public username.
pub fn set_username(handle: u64, chat_id: i64, username: &str) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let channel = channel_peer(state, chat_id)?;
            let response: Bool = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                ChannelsUpdateUsernameRequest {
                    channel: Box::new(channel),
                    username: username.to_owned(),
                },
            )?;
            confirm_change(response)
        })
    })
}

pub fn set_default_restrictions(
    handle: u64,
    chat_id: i64,
    rights: ChatBannedRights,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesEditChatDefaultBannedRightsRequest {
                    peer: Box::new(peer),
                    banned_rights: Box::new(rights.clone()),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

/// The caller supplies a fresh SRP proof. Eligibility and ownership-transfer
/// waiting periods are enforced by Telegram; this does not persist the proof.
pub fn transfer_ownership(
    handle: u64,
    chat_id: i64,
    user_id: i64,
    password: InputCheckPasswordSrp,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            let user = user_peer(state, user_id)?;
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesEditChatCreatorRequest {
                    peer: Box::new(peer),
                    user_id: Box::new(user),
                    password: Box::new(password.clone()),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

pub fn set_forum_enabled(
    handle: u64,
    chat_id: i64,
    enabled: bool,
    tabs: bool,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let channel = channel_peer(state, chat_id)?;
            let boolean = |value| {
                Box::new(if value {
                    Bool::BoolTrue(BoolTrueConstructor {})
                } else {
                    Bool::BoolFalse(BoolFalseConstructor {})
                })
            };
            let response: Updates = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                ChannelsToggleForumRequest {
                    channel: Box::new(channel),
                    enabled: boolean(enabled),
                    tabs: boolean(tabs),
                },
            )?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

fn confirm_change(response: Bool) -> Result<(), MtprotoError> {
    match response {
        Bool::BoolTrue(_) => Ok(()),
        Bool::BoolFalse(_) => Err(MtprotoError::Message("chat change was not applied".into())),
    }
}

pub(super) fn group_peer(
    state: &super::ClientState,
    chat_id: i64,
) -> Result<InputPeer, MtprotoError> {
    let peer = require_usable_peer(&state.peers, chat_id)?;
    if peer.kind == PeerKind::User {
        return Err(MtprotoError::Message(
            "a group or channel is required".into(),
        ));
    }
    Ok(input_peer_from_cached(peer))
}

fn channel_peer(state: &super::ClientState, chat_id: i64) -> Result<InputChannel, MtprotoError> {
    let peer = require_usable_peer(&state.peers, chat_id)?;
    if peer.kind != PeerKind::Channel {
        return Err(MtprotoError::Message(
            "a channel or megagroup is required".into(),
        ));
    }
    Ok(InputChannel::InputChannel(InputChannelConstructor {
        channel_id: peer.id,
        access_hash: peer.access_hash,
    }))
}

fn basic_group_id(state: &super::ClientState, chat_id: i64) -> Result<i64, MtprotoError> {
    let peer = require_usable_peer(&state.peers, chat_id)?;
    if peer.kind != PeerKind::Chat {
        return Err(MtprotoError::Message("a basic group is required".into()));
    }
    Ok(peer.id)
}

pub(super) fn user_peer(
    state: &super::ClientState,
    user_id: i64,
) -> Result<InputUser, MtprotoError> {
    let peer = require_usable_peer(&state.peers, user_id)?;
    if peer.kind != PeerKind::User {
        return Err(MtprotoError::Message("a user peer is required".into()));
    }
    Ok(InputUser::InputUser(InputUserConstructor {
        user_id: peer.id,
        access_hash: peer.access_hash,
    }))
}

fn flag(enabled: bool) -> Option<Box<True>> {
    enabled.then(|| Box::new(True::True(TrueConstructor {})))
}

fn validate_title(title: &str) -> Result<(), MtprotoError> {
    if title.trim().is_empty() {
        return Err(MtprotoError::Message("a chat title is required".into()));
    }
    Ok(())
}

fn channel_request(title: &str, about: &str, kind: ChannelKind) -> ChannelsCreateChannelRequest {
    let broadcast = kind == ChannelKind::Broadcast;
    let forum = kind == ChannelKind::Forum;
    let flag = |enabled| {
        if enabled {
            Some(Box::new(True::True(TrueConstructor {})))
        } else {
            None
        }
    };
    ChannelsCreateChannelRequest {
        flags: u32::from(broadcast) | (u32::from(!broadcast) << 1) | (u32::from(forum) << 5),
        broadcast: flag(broadcast),
        megagroup: flag(!broadcast),
        for_import: None,
        forum: flag(forum),
        title: title.to_owned(),
        about: about.to_owned(),
        geo_point: None,
        address: None,
        ttl_period: None,
    }
}

#[cfg(test)]
#[path = "../../tests/unit/client_group_management_tests.rs"]
mod tests;
