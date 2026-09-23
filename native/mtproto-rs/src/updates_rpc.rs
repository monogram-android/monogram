//! Live updates dispatching, push verification, and difference recovery engine.
//! https://core.telegram.org/api/updates
//! https://core.telegram.org/method/updates.getDifference
//! https://core.telegram.org/method/updates.getChannelDifference

use crate::HashMap;

use tellers_mtproto::latest::api::{
    ChannelMessagesFilter, ChannelMessagesFilterEmptyConstructor, Dialog, DialogPeer, InputChannel,
    InputChannelConstructor, Message, Peer, Update, UpdatesChannelDifference, UpdatesDifference,
    UpdatesGetChannelDifferenceRequest, UpdatesGetDifferenceRequest, UpdatesGetStateRequest,
    UpdatesState,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::dialogs::{cache_from_users_chats, message_to_dto};
use crate::emoji_status::emoji_status_document_id;
use crate::media::MediaIndex;
use crate::peers::{
    self, CachedPeer, PeerKind, channel_id_from_chat_id, chat_id_for_channel, chat_id_for_chat,
    chat_id_for_user, peer_chat_id, vector_boxed_items, vector_items,
};
use crate::presence::{action_kind, user_status_parts};
use crate::{MtprotoError, UpdateEventDto, UpdatesStateDto};

fn push_gap() -> MtprotoError {
    MtprotoError::Message("updates gap".into())
}

/// Message-box continuity is independent of the outer Updates sequence.
/// https://core.telegram.org/api/updates#pts-checking-and-applying
pub(crate) fn advance_push_counter(
    local: &mut i32,
    remote: i32,
    count: i32,
) -> Result<bool, MtprotoError> {
    if *local < 0 || remote < 0 || count < 0 {
        return Err(push_gap());
    }
    let expected = local.checked_add(count).ok_or_else(push_gap)?;
    if expected < remote {
        return Err(push_gap());
    }
    if expected > remote {
        return Ok(false);
    }
    *local = remote;
    Ok(true)
}

fn push_channel(message: &Message) -> Result<i64, MtprotoError> {
    let peer = match message {
        Message::Message(m) => &m.peer_id,
        Message::MessageService(m) => &m.peer_id,
        _ => return Err(push_gap()),
    };
    match peer.as_ref() {
        Peer::PeerChannel(p) => Ok(chat_id_for_channel(p.channel_id)),
        _ => Err(push_gap()),
    }
}

pub(crate) fn advance_push_update(
    update: &Update,
    cursor: &mut UpdatesStateDto,
    channels: &mut HashMap<i64, i32>,
    apply_unsequenced: bool,
    pending_channels: &mut Vec<i64>,
) -> Result<bool, MtprotoError> {
    let common = match update {
        Update::UpdateNewMessage(u) => Some((u.pts, u.pts_count)),
        Update::UpdateEditMessage(u) => Some((u.pts, u.pts_count)),
        Update::UpdateDeleteMessages(u) => Some((u.pts, u.pts_count)),
        Update::UpdateReadHistoryInbox(u) => Some((u.pts, u.pts_count)),
        Update::UpdateReadHistoryOutbox(u) => Some((u.pts, u.pts_count)),
        Update::UpdateReadMessagesContents(u) => Some((u.pts, u.pts_count)),
        Update::UpdateWebPage(u) => Some((u.pts, u.pts_count)),
        Update::UpdateFolderPeers(u) => Some((u.pts, u.pts_count)),
        Update::UpdatePinnedMessages(u) => Some((u.pts, u.pts_count)),
        _ => None,
    };
    if let Some((pts, count)) = common {
        return advance_push_counter(&mut cursor.pts, pts, count);
    }
    let channel = match update {
        Update::UpdateNewChannelMessage(u) => Some((push_channel(&u.message)?, u.pts, u.pts_count)),
        Update::UpdateEditChannelMessage(u) => {
            Some((push_channel(&u.message)?, u.pts, u.pts_count))
        }
        Update::UpdateDeleteChannelMessages(u) => {
            Some((chat_id_for_channel(u.channel_id), u.pts, u.pts_count))
        }
        Update::UpdateChannelWebPage(u) => {
            Some((chat_id_for_channel(u.channel_id), u.pts, u.pts_count))
        }
        Update::UpdatePinnedChannelMessages(u) => {
            Some((chat_id_for_channel(u.channel_id), u.pts, u.pts_count))
        }
        Update::UpdateReadChannelInbox(u) => Some((chat_id_for_channel(u.channel_id), u.pts, 0)),
        _ => None,
    };
    if let Some((id, pts, count)) = channel {
        let result = channels
            .get_mut(&id)
            .ok_or_else(push_gap)
            .and_then(|local| advance_push_counter(local, pts, count));
        if result.is_err() && !pending_channels.contains(&id) {
            pending_channels.push(id);
        }
        return result;
    }
    let qts = match update {
        Update::UpdateNewEncryptedMessage(u) => Some(u.qts),
        Update::UpdateMessagePollVote(u) => Some(u.qts),
        Update::UpdateChatParticipant(u) => Some(u.qts),
        Update::UpdateChannelParticipant(u) => Some(u.qts),
        Update::UpdateBotStopped(u) => Some(u.qts),
        Update::UpdateBotChatInviteRequester(u) => Some(u.qts),
        Update::UpdateBotChatBoost(u) => Some(u.qts),
        Update::UpdateBotMessageReaction(u) => Some(u.qts),
        Update::UpdateBotMessageReactions(u) => Some(u.qts),
        Update::UpdateBotBusinessConnect(u) => Some(u.qts),
        Update::UpdateBotNewBusinessMessage(u) => Some(u.qts),
        Update::UpdateBotEditBusinessMessage(u) => Some(u.qts),
        Update::UpdateBotDeleteBusinessMessage(u) => Some(u.qts),
        Update::UpdateBotPurchasedPaidMedia(u) => Some(u.qts),
        Update::UpdateManagedBot(u) => Some(u.qts),
        Update::UpdateBotGuestChatQuery(u) => Some(u.qts),
        Update::UpdateBotStarsSubscription(u) => Some(u.qts),
        _ => None,
    };
    if let Some(qts) = qts {
        return advance_push_counter(&mut cursor.qts, qts, 1);
    }
    // Keep this explicit: a new TL variant may add sequence metadata, and must
    // reach authoritative difference recovery until its contract is reviewed.
    match update {
        Update::UpdatePtsChanged(_) => Err(push_gap()),
        Update::UpdateChannelTooLong(_)
        | Update::UpdateMessageId(_)
        | Update::UpdateReadChannelDiscussionOutbox(_)
        | Update::UpdateRecentStickers(_)
        | Update::UpdateFavedStickers(_)
        | Update::UpdateReadFeaturedStickers(_)
        | Update::UpdateReadFeaturedEmojiStickers(_)
        | Update::UpdateRecentEmojiStatuses(_)
        | Update::UpdateRecentReactions(_)
        | Update::UpdateConfig(_)
        | Update::UpdateDcOptions(_)
        | Update::UpdateContactsReset(_)
        | Update::UpdateDraftMessage(_)
        | Update::UpdateAutoSaveSettings(_)
        | Update::UpdateDialogFilter(_)
        | Update::UpdateDialogFilterOrder(_)
        | Update::UpdateDialogFilters(_)
        | Update::UpdateDialogPinned(_)
        | Update::UpdatePinnedDialogs(_)
        | Update::UpdateChannel(_)
        | Update::UpdateChat(_)
        | Update::UpdateUser(_)
        | Update::UpdateUserName(_)
        | Update::UpdateUserPhone(_)
        | Update::UpdateChatParticipants(_)
        | Update::UpdateDialogUnreadMark(_)
        | Update::UpdatePeerSettings(_)
        | Update::UpdatePeerBlocked(_)
        | Update::UpdateNotifySettings(_)
        | Update::UpdateSavedGifs(_)
        | Update::UpdateChannelReadMessagesContents(_)
        | Update::UpdateReadChannelDiscussionInbox(_)
        | Update::UpdateMessageReactions(_)
        | Update::UpdateUserTyping(_)
        | Update::UpdateChatUserTyping(_)
        | Update::UpdateChannelUserTyping(_)
        | Update::UpdateUserEmojiStatus(_)
        | Update::UpdateUserStatus(_)
        | Update::UpdateReadChannelOutbox(_) => Ok(apply_unsequenced),
        _ => Err(push_gap()),
    }
}

/// Validates all sequence transitions before mutating caches or emitting events.
/// Unsupported compact forms recover through difference, preserving peer metadata.
pub(crate) fn apply_push(
    bytes: &[u8],
    peers: &mut HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    cursor: &mut UpdatesStateDto,
    channel_pts: &mut HashMap<i64, i32>,
    pending_channels: &mut Vec<i64>,
) -> Result<Vec<UpdateEventDto>, MtprotoError> {
    use tellers_mtproto::codec::{BoxedDecode, Decoder, Limits};
    use tellers_mtproto::latest::api::Updates;
    if bytes.len() > 8 * 1024 * 1024 {
        return Err(push_gap());
    }
    let limits = Limits {
        max_bytes: 8 * 1024 * 1024,
        max_vector_len: 4096,
        max_depth: 32,
        max_total_bytes: 8 * 1024 * 1024,
    };
    let mut decoder = Decoder::new(bytes, limits).map_err(|_| push_gap())?;
    let invalid =
        || MtprotoError::Message("invalid updates constructor; reconnect required".into());
    let decoded = Updates::decode_boxed(&mut decoder).map_err(|_| invalid())?;
    decoder.finish().map_err(|_| invalid())?;
    let (updates, users, chats, date, start, end) = match &decoded {
        Updates::Updates(u) => (
            vector_boxed_items(&u.updates).collect::<Vec<_>>(),
            Some(&u.users),
            Some(&u.chats),
            u.date,
            u.seq,
            u.seq,
        ),
        Updates::UpdatesCombined(u) => (
            vector_boxed_items(&u.updates).collect::<Vec<_>>(),
            Some(&u.users),
            Some(&u.chats),
            u.date,
            u.seq_start,
            u.seq,
        ),
        Updates::UpdateShort(u) => (vec![u.update.as_ref()], None, None, u.date, 0, 0),
        _ => return Err(push_gap()),
    };
    if start < 0 || end < start || date < 0 || updates.len() > 4096 {
        return Err(push_gap());
    }
    let mut next = cursor.clone();
    let mut next_channels = channel_pts.clone();
    let apply_unsequenced = if start == 0 {
        if end != 0 {
            return Err(push_gap());
        }
        true
    } else if cursor.seq.checked_add(1).ok_or_else(push_gap)? == start {
        next.seq = end;
        true
    } else if cursor.seq >= start {
        false
    } else {
        return Err(push_gap());
    };
    let mut accepted = Vec::with_capacity(updates.len());
    for update in updates {
        if advance_push_update(
            update,
            &mut next,
            &mut next_channels,
            apply_unsequenced,
            pending_channels,
        )? {
            accepted.push(update);
        }
    }
    if apply_unsequenced || !accepted.is_empty() {
        next.date = next.date.max(date);
    }
    if let (Some(users), Some(chats)) = (users, chats) {
        cache_from_users_chats(
            peers,
            media,
            vector_boxed_items(users).cloned(),
            vector_boxed_items(chats).cloned(),
        );
    }
    let mut events = Vec::new();
    collect_other_updates(accepted.into_iter(), media, pending_channels, &mut events);
    *cursor = next;
    *channel_pts = next_channels;
    Ok(events)
}

pub fn get_updates_state(
    snapshot: &mut Snapshot,
    api_id: i32,
) -> Result<UpdatesStateDto, MtprotoError> {
    let state: UpdatesState =
        api_invoke::invoke_api_allow_updates(snapshot, api_id, UpdatesGetStateRequest {})?;
    let UpdatesState::UpdatesState(s) = state else {
        return Err(MtprotoError::Message("unexpected updates.state".into()));
    };
    Ok(UpdatesStateDto {
        pts: s.pts,
        qts: s.qts,
        date: s.date,
        seq: s.seq,
    })
}

fn update_is_silent(update: &Update) -> bool {
    matches!(
        update,
        Update::UpdateReadChannelDiscussionOutbox(_)
            | Update::UpdateRecentStickers(_)
            | Update::UpdateFavedStickers(_)
            | Update::UpdateReadFeaturedStickers(_)
            | Update::UpdateReadFeaturedEmojiStickers(_)
            | Update::UpdateRecentEmojiStatuses(_)
            | Update::UpdateRecentReactions(_)
            | Update::UpdateConfig(_)
            | Update::UpdatePtsChanged(_)
            | Update::UpdateDcOptions(_)
            | Update::UpdateContactsReset(_)
            | Update::UpdateDraftMessage(_)
            | Update::UpdateWebPage(_)
            | Update::UpdateChannelWebPage(_)
            | Update::UpdateAutoSaveSettings(_)
    )
}

fn message_mentioned(msg: &Message) -> bool {
    match msg {
        Message::Message(m) => m.mentioned.is_some() && m.out.is_none(),
        Message::MessageService(m) => m.mentioned.is_some() && m.out.is_none(),
        _ => false,
    }
}

fn push_mentioned(events: &mut Vec<UpdateEventDto>, msg: &Message) {
    if !message_mentioned(msg) {
        return;
    }
    let chat_id = match msg {
        Message::Message(m) => peer_chat_id(&m.peer_id),
        Message::MessageService(m) => peer_chat_id(&m.peer_id),
        _ => return,
    };
    events.push(UpdateEventDto::Ignored {
        kind: format!("unread_mentions_delta:{chat_id}:1"),
    });
}

fn push_peer_typing(
    events: &mut Vec<UpdateEventDto>,
    chat_id: i64,
    user_id: i64,
    action: &tellers_mtproto::latest::api::SendMessageAction,
) {
    let kind = action_kind(action);
    events.push(UpdateEventDto::PeerTyping {
        chat_id,
        user_id,
        typing: crate::presence::action_is_active(action),
        action: kind.unwrap_or("").to_string(),
    });
}

pub(crate) fn collect_other_updates<'a>(
    updates: impl Iterator<Item = &'a Update>,
    media_index: &mut MediaIndex,
    pending_channels: &mut Vec<i64>,
    events: &mut Vec<UpdateEventDto>,
) {
    let mut folders_changed = false;
    let mut chats_changed = false;
    for update in updates {
        match update {
            Update::UpdateNewMessage(u) => {
                push_mentioned(events, &u.message);
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    events.push(UpdateEventDto::NewMessage { message: dto });
                }
            }
            Update::UpdateNewChannelMessage(u) => {
                push_mentioned(events, &u.message);
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    events.push(UpdateEventDto::NewMessage { message: dto });
                }
            }
            Update::UpdateEditMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    events.push(UpdateEventDto::MessageEdited { message: dto });
                }
            }
            Update::UpdateEditChannelMessage(u) => {
                if let Some(dto) = message_to_dto(&u.message, media_index) {
                    events.push(UpdateEventDto::MessageEdited { message: dto });
                }
            }
            Update::UpdateDeleteMessages(u) => {
                events.push(UpdateEventDto::MessagesDeleted {
                    chat_id: None,
                    message_ids: vector_items(&u.messages).to_vec(),
                });
            }
            Update::UpdateDeleteChannelMessages(u) => {
                events.push(UpdateEventDto::MessagesDeleted {
                    chat_id: Some(chat_id_for_channel(u.channel_id)),
                    message_ids: vector_items(&u.messages).to_vec(),
                });
            }
            Update::UpdateChannelTooLong(u) => {
                pending_channels.push(chat_id_for_channel(u.channel_id));
            }
            Update::UpdateFolderPeers(_)
            | Update::UpdateDialogFilter(_)
            | Update::UpdateDialogFilterOrder(_)
            | Update::UpdateDialogFilters(_) => folders_changed = true,
            Update::UpdateDialogPinned(_)
            | Update::UpdatePinnedDialogs(_)
            | Update::UpdateChannel(_)
            | Update::UpdateChat(_)
            | Update::UpdateUser(_)
            | Update::UpdateUserName(_)
            | Update::UpdateUserPhone(_)
            | Update::UpdateChatParticipants(_)
            | Update::UpdatePeerSettings(_)
            | Update::UpdatePeerBlocked(_) => chats_changed = true,
            Update::UpdateDialogUnreadMark(u) => {
                if let DialogPeer::DialogPeer(peer) = u.peer.as_ref() {
                    events.push(UpdateEventDto::Ignored {
                        kind: format!(
                            "dialog_unread_mark:{}:{}",
                            peer_chat_id(peer.peer.as_ref()),
                            u.unread.is_some() as u8
                        ),
                    });
                }
            }
            Update::UpdateNotifySettings(_) => {
                if let Some(ex) = crate::push_rpc::exception_from_update(update) {
                    events.push(UpdateEventDto::Ignored {
                        kind: format!(
                            "notify_settings:{}:{}:{}",
                            ex.peer_kind, ex.chat_id, ex.mute_until
                        ),
                    });
                }
            }
            Update::UpdatePinnedMessages(_) | Update::UpdatePinnedChannelMessages(_) => {
                chats_changed = true
            }
            Update::UpdateSavedGifs(_) => {
                events.push(crate::UpdateEventDto::SavedGifsChanged);
            }
            Update::UpdateReadChannelDiscussionInbox(u) => {
                events.push(crate::UpdateEventDto::DiscussionInbox {
                    channel_id: crate::peers::chat_id_for_channel(u.channel_id),
                    top_message_id: u.top_msg_id,
                    read_max_id: u.read_max_id,
                });
                chats_changed = true;
            }
            Update::UpdateMessageReactions(u) => {
                // This is a message snapshot, not a dialog-counter delta. It can repeat an
                // unread reaction or remove the last one, so reconcile absolute dialog counts.
                chats_changed = true;
                events.push(crate::UpdateEventDto::MessageReactions {
                    chat_id: peer_chat_id(&u.peer),
                    message_id: u.msg_id,
                    reactions_json: crate::extras_rpc::reaction_update_json(u.reactions.as_ref()),
                });
            }
            u if update_is_silent(u) => {}
            Update::UpdateUserTyping(u) => {
                push_peer_typing(events, chat_id_for_user(u.user_id), u.user_id, &u.action);
            }
            Update::UpdateChatUserTyping(u) => {
                push_peer_typing(
                    events,
                    chat_id_for_chat(u.chat_id),
                    peer_chat_id(&u.from_id),
                    &u.action,
                );
            }
            Update::UpdateChannelUserTyping(u) => {
                push_peer_typing(
                    events,
                    chat_id_for_channel(u.channel_id),
                    peer_chat_id(&u.from_id),
                    &u.action,
                );
            }
            Update::UpdateUserEmojiStatus(u) => {
                events.push(UpdateEventDto::PeerEmojiStatus {
                    user_id: u.user_id,
                    document_id: emoji_status_document_id(Some(u.emoji_status.as_ref())),
                });
            }
            Update::UpdateUserStatus(u) => {
                let (status, status_at) = user_status_parts(Some(u.status.as_ref()));
                events.push(UpdateEventDto::PeerStatus {
                    user_id: u.user_id,
                    status,
                    status_at,
                });
            }
            Update::UpdateReadHistoryInbox(u) => {
                events.push(UpdateEventDto::ReadInbox {
                    chat_id: peer_chat_id(&u.peer),
                    max_id: u.max_id,
                    still_unread: u.still_unread_count,
                });
            }
            // These carry message ids but no absolute dialog-counter delta. Re-read dialogs so
            // reading mentions/reactions on another device updates their absolute counters.
            Update::UpdateReadMessagesContents(_)
            | Update::UpdateChannelReadMessagesContents(_) => {
                chats_changed = true;
            }
            Update::UpdateReadChannelInbox(u) => {
                events.push(UpdateEventDto::ReadInbox {
                    chat_id: chat_id_for_channel(u.channel_id),
                    max_id: u.max_id,
                    still_unread: u.still_unread_count,
                });
            }
            Update::UpdateReadHistoryOutbox(u) => {
                events.push(UpdateEventDto::ReadOutbox {
                    chat_id: peer_chat_id(&u.peer),
                    max_id: u.max_id,
                });
            }
            Update::UpdateReadChannelOutbox(u) => {
                events.push(UpdateEventDto::ReadOutbox {
                    chat_id: chat_id_for_channel(u.channel_id),
                    max_id: u.max_id,
                });
            }
            Update::UpdateMessageId(u) => {
                events.push(UpdateEventDto::Ignored {
                    kind: format!("UpdateMessageId:{}:{}", u.random_id, u.id),
                });
            }
            other => {
                let kind = format!("{other:?}");
                let kind = kind.split('(').next().unwrap_or("Update").to_string();
                eprintln!("monogram.updates ignored {kind}");
                events.push(UpdateEventDto::Ignored { kind });
            }
        }
    }
    if folders_changed {
        events.push(UpdateEventDto::FoldersChanged);
    }
    if chats_changed {
        events.push(UpdateEventDto::ChatsChanged);
    }
}

pub fn drain_difference(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    cursor: &mut UpdatesStateDto,
    pending_channels: &mut Vec<i64>,
) -> Result<(Vec<UpdateEventDto>, bool), MtprotoError> {
    let request = UpdatesGetDifferenceRequest {
        flags: 0,
        pts: cursor.pts,
        pts_limit: None,
        pts_total_limit: None,
        date: cursor.date,
        qts: cursor.qts,
        qts_limit: None,
    };
    let response: UpdatesDifference =
        api_invoke::invoke_api_allow_updates(snapshot, api_id, request)?;
    let has_more = matches!(&response, UpdatesDifference::UpdatesDifferenceSlice(_));
    let mut events = Vec::new();
    match response {
        UpdatesDifference::UpdatesDifferenceEmpty(e) => {
            cursor.date = e.date;
            cursor.seq = e.seq;
        }
        UpdatesDifference::UpdatesDifference(diff) => {
            cache_from_users_chats(
                peers,
                media_index,
                vector_boxed_items(&diff.users).cloned(),
                vector_boxed_items(&diff.chats).cloned(),
            );
            for msg in vector_boxed_items(&diff.new_messages) {
                if let Some(dto) = message_to_dto(msg, media_index) {
                    events.push(UpdateEventDto::NewMessage { message: dto });
                }
            }
            collect_other_updates(
                vector_boxed_items(&diff.other_updates),
                media_index,
                pending_channels,
                &mut events,
            );
            if let UpdatesState::UpdatesState(s) = *diff.state {
                cursor.pts = s.pts;
                cursor.qts = s.qts;
                cursor.date = s.date;
                cursor.seq = s.seq;
            }
        }
        UpdatesDifference::UpdatesDifferenceSlice(diff) => {
            cache_from_users_chats(
                peers,
                media_index,
                vector_boxed_items(&diff.users).cloned(),
                vector_boxed_items(&diff.chats).cloned(),
            );
            for msg in vector_boxed_items(&diff.new_messages) {
                if let Some(dto) = message_to_dto(msg, media_index) {
                    events.push(UpdateEventDto::NewMessage { message: dto });
                }
            }
            collect_other_updates(
                vector_boxed_items(&diff.other_updates),
                media_index,
                pending_channels,
                &mut events,
            );
            if let UpdatesState::UpdatesState(s) = *diff.intermediate_state {
                cursor.pts = s.pts;
                cursor.qts = s.qts;
                cursor.date = s.date;
                cursor.seq = s.seq;
            }
        }
        UpdatesDifference::UpdatesDifferenceTooLong(t) => {
            cursor.pts = t.pts;
            // The server has compacted the missing update range. The cursor
            // is now authoritative, but the open dialog needs a bounded
            // history refresh to refill messages that cannot be reconstructed
            // from the difference response.
            events.push(UpdateEventDto::Ignored {
                kind: "difference_too_long".into(),
            });
            events.push(UpdateEventDto::ChatsChanged);
        }
        _ => {}
    }
    Ok((events, has_more))
}

pub(crate) struct ChannelDifferencePage {
    pub events: Vec<UpdateEventDto>,
    pub pts: i32,
    pub final_page: bool,
    pub timeout: Option<i32>,
    pub pending_channels: Vec<i64>,
}

pub(crate) fn drain_channel_difference(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    pts: i32,
) -> Result<ChannelDifferencePage, MtprotoError> {
    let channel_id = channel_id_from_chat_id(chat_id)
        .ok_or_else(|| MtprotoError::Message("not a channel".into()))?;
    let access_hash = peers
        .get(&chat_id)
        .filter(|p| p.kind == PeerKind::Channel && peers::has_usable_access_hash(p))
        .map(|p| p.access_hash)
        .ok_or_else(|| {
            MtprotoError::Message(format!("unknown peer {chat_id}; refresh chats first"))
        })?;
    let request = UpdatesGetChannelDifferenceRequest {
        flags: 0,
        force: None,
        channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
            channel_id,
            access_hash,
        })),
        filter: Box::new(ChannelMessagesFilter::ChannelMessagesFilterEmpty(
            ChannelMessagesFilterEmptyConstructor {},
        )),
        pts: pts.max(1),
        limit: 100,
    };
    let response: UpdatesChannelDifference =
        api_invoke::invoke_api_allow_updates(snapshot, api_id, request)?;
    let mut events = Vec::new();
    let mut next_pts = pts.max(1);
    let (mut final_page, mut timeout) = (false, None);
    let mut pending_channels = Vec::new();
    match response {
        UpdatesChannelDifference::UpdatesChannelDifferenceEmpty(e) => {
            final_page = e.final_.is_some();
            timeout = e.timeout;
            next_pts = e.pts;
        }
        UpdatesChannelDifference::UpdatesChannelDifference(diff) => {
            final_page = diff.final_.is_some();
            timeout = diff.timeout;
            cache_from_users_chats(
                peers,
                media_index,
                vector_boxed_items(&diff.users).cloned(),
                vector_boxed_items(&diff.chats).cloned(),
            );
            for msg in vector_boxed_items(&diff.new_messages) {
                if let Some(dto) = message_to_dto(msg, media_index) {
                    events.push(UpdateEventDto::NewMessage { message: dto });
                }
            }
            collect_other_updates(
                vector_boxed_items(&diff.other_updates),
                media_index,
                &mut pending_channels,
                &mut events,
            );
            if !pending_channels.is_empty() {
                events.push(UpdateEventDto::ChatsChanged);
            }
            next_pts = diff.pts;
        }
        UpdatesChannelDifference::UpdatesChannelDifferenceTooLong(t) => {
            final_page = t.final_.is_some();
            timeout = t.timeout;
            cache_from_users_chats(
                peers,
                media_index,
                vector_boxed_items(&t.users).cloned(),
                vector_boxed_items(&t.chats).cloned(),
            );
            let mut n = 0_i32;
            for msg in vector_boxed_items(&t.messages) {
                if let Some(dto) = message_to_dto(msg, media_index) {
                    events.push(UpdateEventDto::NewMessage { message: dto });
                    n += 1;
                }
            }
            if let Dialog::Dialog(d) = t.dialog.as_ref() {
                if let Some(p) = d.pts {
                    next_pts = p;
                }
            }
            next_pts = next_channel_pts_after_too_long(pts, next_pts, n);
            events.push(UpdateEventDto::ChatsChanged);
        }
        _ => {}
    }
    Ok(ChannelDifferencePage {
        events,
        pts: next_pts,
        final_page,
        timeout,
        pending_channels,
    })
}

/// Message count is not a pts delta. Only server state can advance the cursor.
pub(crate) fn next_channel_pts_after_too_long(
    current: i32,
    dialog_pts: i32,
    _new_messages: i32,
) -> i32 {
    dialog_pts.max(current)
}

#[cfg(test)]
#[path = "updates_rpc_tests.rs"]
mod tests;
