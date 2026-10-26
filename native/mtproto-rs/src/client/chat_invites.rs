#[cfg(test)]
#[path = "../../tests/unit/client_chat_invites_tests.rs"]
mod tests;

use super::group_management::{group_peer, user_peer};
use super::management::enqueue_response;
use super::{ClientState, call_with_migrate, with_client_mut};
use crate::MtprotoError;
use tellers_mtproto::latest::api::*;

#[derive(Clone, Default)]
pub struct InviteOptions {
    pub expire_date: Option<i32>,
    pub usage_limit: Option<i32>,
    pub title: Option<String>,
    pub request_needed: bool,
    pub revoke_permanent: bool,
}

#[derive(Clone, Default)]
pub struct InviteChanges {
    pub expire_date: Option<i32>,
    pub usage_limit: Option<i32>,
    pub title: Option<String>,
    pub request_needed: Option<bool>,
    pub revoked: bool,
}

#[derive(Clone)]
pub struct InviteCursor {
    pub date: i32,
    pub link: String,
}

#[derive(Clone)]
pub struct ImporterCursor {
    pub date: i32,
    pub user_id: i64,
}

pub fn export_invite(
    handle: u64,
    chat_id: i64,
    options: InviteOptions,
) -> Result<ExportedChatInvite, MtprotoError> {
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            export_request(peer, &options),
        )
    })
}

pub fn edit_invite(
    handle: u64,
    chat_id: i64,
    link: &str,
    changes: InviteChanges,
) -> Result<MessagesExportedChatInvite, MtprotoError> {
    validate_link(link)?;
    if !changes.revoked
        && changes.expire_date.is_none()
        && changes.usage_limit.is_none()
        && changes.title.is_none()
        && changes.request_needed.is_none()
    {
        return Err(MtprotoError::Message(
            "an invitation change is required".into(),
        ));
    }
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesEditExportedChatInviteRequest {
                flags: u32::from(changes.expire_date.is_some())
                    | (u32::from(changes.usage_limit.is_some()) << 1)
                    | (u32::from(changes.revoked) << 2)
                    | (u32::from(changes.request_needed.is_some()) << 3)
                    | (u32::from(changes.title.is_some()) << 4),
                revoked: flag(changes.revoked),
                peer: Box::new(peer),
                link: link.to_owned(),
                expire_date: changes.expire_date,
                usage_limit: changes.usage_limit,
                request_needed: changes.request_needed.map(|value| Box::new(boolean(value))),
                title: changes.title.clone(),
            },
        )
    })
}

/// Use both fields of the last returned invitation as the next-page cursor.
pub fn get_invites(
    handle: u64,
    chat_id: i64,
    admin_id: Option<i64>,
    cursor: Option<InviteCursor>,
    revoked: bool,
    limit: i32,
) -> Result<MessagesExportedChatInvites, MtprotoError> {
    validate_limit(limit)?;
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        let admin = admin_id
            .map(|id| user_peer(state, id))
            .transpose()?
            .unwrap_or(InputUser::InputUserSelf(InputUserSelfConstructor {}));
        let response: MessagesExportedChatInvites = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            list_request(peer, admin, cursor.as_ref(), revoked, limit),
        )?;
        let MessagesExportedChatInvites::MessagesExportedChatInvites(result) = &response;
        cache_users(state, &result.users);
        Ok(response)
    })
}

pub fn delete_invite(handle: u64, chat_id: i64, link: &str) -> Result<(), MtprotoError> {
    validate_link(link)?;
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        let result: Bool = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesDeleteExportedChatInviteRequest {
                peer: Box::new(peer),
                link: link.to_owned(),
            },
        )?;
        match result {
            Bool::BoolTrue(_) => Ok(()),
            Bool::BoolFalse(_) => Err(MtprotoError::Message(
                "invite deletion was not applied".into(),
            )),
        }
    })
}

pub fn check_invite(handle: u64, hash: &str) -> Result<ChatInvite, MtprotoError> {
    validate_link(hash)?;
    call(handle, |state| {
        crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesCheckChatInviteRequest {
                hash: hash.to_owned(),
            },
        )
    })
}

pub fn join_invite(handle: u64, hash: &str) -> Result<Updates, MtprotoError> {
    validate_link(hash)?;
    call(handle, |state| {
        let response: Updates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesImportChatInviteRequest {
                hash: hash.to_owned(),
            },
        )?;
        enqueue_response(state.snapshot.dc_id, &response)?;
        Ok(response)
    })
}

pub fn get_importers(
    handle: u64,
    chat_id: i64,
    link: Option<String>,
    requested: bool,
    cursor: Option<ImporterCursor>,
    limit: i32,
) -> Result<MessagesChatInviteImporters, MtprotoError> {
    validate_limit(limit)?;
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        let (offset_date, offset_user) = match &cursor {
            Some(cursor) => (cursor.date, user_peer(state, cursor.user_id)?),
            None => (0, InputUser::InputUserEmpty(InputUserEmptyConstructor {})),
        };
        let response: MessagesChatInviteImporters = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesGetChatInviteImportersRequest {
                flags: u32::from(requested) | (u32::from(link.is_some()) << 1),
                requested: flag(requested),
                subscription_expired: None,
                peer: Box::new(peer),
                link: link.clone(),
                q: None,
                offset_date,
                offset_user: Box::new(offset_user),
                limit,
            },
        )?;
        let MessagesChatInviteImporters::MessagesChatInviteImporters(result) = &response;
        cache_users(state, &result.users);
        Ok(response)
    })
}

pub fn resolve_join_request(
    handle: u64,
    chat_id: i64,
    user_id: i64,
    approved: bool,
) -> Result<Updates, MtprotoError> {
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        let user = user_peer(state, user_id)?;
        let response: Updates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesHideChatJoinRequestRequest {
                flags: u32::from(approved),
                approved: flag(approved),
                peer: Box::new(peer),
                user_id: Box::new(user),
            },
        )?;
        enqueue_response(state.snapshot.dc_id, &response)?;
        Ok(response)
    })
}

pub fn resolve_all_join_requests(
    handle: u64,
    chat_id: i64,
    link: Option<String>,
    approved: bool,
) -> Result<Updates, MtprotoError> {
    call(handle, |state| {
        let peer = group_peer(state, chat_id)?;
        let response: Updates = crate::api_invoke::invoke_api(
            &mut state.snapshot,
            state.api_id,
            MessagesHideAllChatJoinRequestsRequest {
                flags: u32::from(approved) | (u32::from(link.is_some()) << 1),
                approved: flag(approved),
                peer: Box::new(peer),
                link: link.clone(),
            },
        )?;
        enqueue_response(state.snapshot.dc_id, &response)?;
        Ok(response)
    })
}

fn export_request(peer: InputPeer, options: &InviteOptions) -> MessagesExportChatInviteRequest {
    MessagesExportChatInviteRequest {
        flags: u32::from(options.expire_date.is_some())
            | (u32::from(options.usage_limit.is_some()) << 1)
            | (u32::from(options.revoke_permanent) << 2)
            | (u32::from(options.request_needed) << 3)
            | (u32::from(options.title.is_some()) << 4),
        legacy_revoke_permanent: flag(options.revoke_permanent),
        request_needed: flag(options.request_needed),
        peer: Box::new(peer),
        expire_date: options.expire_date,
        usage_limit: options.usage_limit,
        title: options.title.clone(),
        subscription_pricing: None,
    }
}

fn list_request(
    peer: InputPeer,
    admin: InputUser,
    cursor: Option<&InviteCursor>,
    revoked: bool,
    limit: i32,
) -> MessagesGetExportedChatInvitesRequest {
    MessagesGetExportedChatInvitesRequest {
        flags: (u32::from(revoked) << 3) | (u32::from(cursor.is_some()) << 2),
        revoked: flag(revoked),
        peer: Box::new(peer),
        admin_id: Box::new(admin),
        offset_date: cursor.map(|value| value.date),
        offset_link: cursor.map(|value| value.link.clone()),
        limit,
    }
}

fn call<T>(
    handle: u64,
    operation: impl Fn(&mut ClientState) -> Result<T, MtprotoError>,
) -> Result<T, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| operation(state))
    })
}

fn cache_users(state: &mut ClientState, users: &Vector<Box<User>>) {
    crate::dialogs::cache_from_users_chats(
        &mut state.peers,
        &mut state.media,
        crate::peers::vector_boxed_items(users).cloned(),
        std::iter::empty(),
    );
}

fn validate_limit(limit: i32) -> Result<(), MtprotoError> {
    if !(1..=100).contains(&limit) {
        return Err(MtprotoError::Message(
            "page limit must be between 1 and 100".into(),
        ));
    }
    Ok(())
}

fn validate_link(link: &str) -> Result<(), MtprotoError> {
    if link.trim().is_empty() {
        return Err(MtprotoError::Message(
            "an invite link or hash is required".into(),
        ));
    }
    Ok(())
}

fn flag(value: bool) -> Option<Box<True>> {
    value.then(|| Box::new(True::True(TrueConstructor {})))
}
fn boolean(value: bool) -> Bool {
    if value {
        Bool::BoolTrue(BoolTrueConstructor {})
    } else {
        Bool::BoolFalse(BoolFalseConstructor {})
    }
}
