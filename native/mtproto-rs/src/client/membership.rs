//! Join a channel, megagroup, or basic group, and unblock a user.
//! https://core.telegram.org/method/channels.joinChannel
//! https://core.telegram.org/method/messages.addChatUser
//! https://core.telegram.org/method/contacts.unblock

use tellers_mtproto::latest::api::{
    Bool, ChannelsJoinChannelRequest, Chat as TlChat, ContactsUnblockRequest, InputChannel,
    InputChannelConstructor, InputUser, InputUserSelfConstructor, MessagesAddChatUserRequest,
    MessagesChatInviteJoinResult, MessagesInvitedUsers, MissingInvitee, Updates, User, Vector,
};

use crate::dialogs::{cache_from_users_chats, chat_meta_from_tl};
use crate::peers::{self, channel_id_from_chat_id, input_peer_from_cached, vector_boxed_items};
use crate::{JoinChatDto, MtprotoError};

use super::ClientState;

pub fn join_chat(state: &mut ClientState, chat_id: i64) -> Result<JoinChatDto, MtprotoError> {
    if channel_id_from_chat_id(chat_id).is_some() {
        let cached = peers::require_usable_peer(&state.peers, chat_id)?;
        let response: MessagesChatInviteJoinResult = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            ChannelsJoinChannelRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id: cached.id,
                    access_hash: cached.access_hash,
                })),
            },
        )?;
        return Ok(match response {
            MessagesChatInviteJoinResult::MessagesChatInviteJoinResultOk(ok) => {
                grant_from_updates(state, chat_id, None, ok.updates.as_ref())
            }
            MessagesChatInviteJoinResult::MessagesChatInviteJoinResultWebView(_) => {
                JoinChatDto::pending()
            }
        });
    }
    if chat_id < 0 {
        let response: MessagesInvitedUsers = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesAddChatUserRequest {
                chat_id: -chat_id,
                user_id: Box::new(InputUser::InputUserSelf(InputUserSelfConstructor {})),
                fwd_limit: 0,
            },
        )?;
        let MessagesInvitedUsers::MessagesInvitedUsers(invited) = response;
        return Ok(grant_from_updates(
            state,
            chat_id,
            Some(invited.missing_invitees.as_ref()),
            invited.updates.as_ref(),
        ));
    }
    Err(MtprotoError::Message(
        "join is only for a channel, megagroup, or basic group".into(),
    ))
}

pub fn unblock_user(state: &mut ClientState, chat_id: i64) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(&state.peers, chat_id)?;
    let _: Bool = crate::api_invoke::invoke_api(
        &mut state.snapshot,
        state.api_id,
        ContactsUnblockRequest {
            flags: 0,
            my_stories_from: None,
            id: Box::new(input_peer_from_cached(cached)),
        },
    )?;
    Ok(())
}

fn grant_from_updates(
    state: &mut ClientState,
    chat_id: i64,
    missing: Option<&Vector<Box<MissingInvitee>>>,
    updates: &Updates,
) -> JoinChatDto {
    let (users, chats) = updates_users_chats(updates);
    cache_from_users_chats(
        &mut state.peers,
        &mut state.media,
        users.into_iter(),
        chats.iter().cloned(),
    );
    if missing.is_some_and(|items| missing_self(items, state.user_id)) {
        return JoinChatDto::pending();
    }
    membership_granted(chat_id, &chats)
}

/// Membership is granted only when the returned chat for [chat_id] is present and not `left`.
pub(crate) fn membership_granted(chat_id: i64, chats: &[TlChat]) -> JoinChatDto {
    let raw = channel_id_from_chat_id(chat_id).unwrap_or_else(|| -chat_id);
    for chat in chats {
        let Some((id, meta)) = chat_meta_from_tl(chat) else {
            continue;
        };
        if id != raw {
            continue;
        }
        if meta.left {
            return JoinChatDto::pending();
        }
        return JoinChatDto {
            joined: true,
            can_send_plain: meta.can_send_plain,
            can_send_photos: meta.can_send_photos,
        };
    }
    JoinChatDto::pending()
}

fn missing_self(missing: &Vector<Box<MissingInvitee>>, self_id: Option<i64>) -> bool {
    let Some(self_id) = self_id else {
        return false;
    };
    vector_boxed_items(missing).any(|item| match item {
        MissingInvitee::MissingInvitee(invitee) => invitee.user_id == self_id,
    })
}

fn updates_users_chats(updates: &Updates) -> (Vec<User>, Vec<TlChat>) {
    match updates {
        Updates::Updates(batch) => (
            vector_boxed_items(&batch.users).cloned().collect(),
            vector_boxed_items(&batch.chats).cloned().collect(),
        ),
        Updates::UpdatesCombined(batch) => (
            vector_boxed_items(&batch.users).cloned().collect(),
            vector_boxed_items(&batch.chats).cloned().collect(),
        ),
        _ => (Vec::new(), Vec::new()),
    }
}

impl JoinChatDto {
    fn pending() -> Self {
        Self {
            joined: false,
            can_send_plain: false,
            can_send_photos: false,
        }
    }
}
