use crate::HashMap;

use tellers_mtproto::latest::api::{
    Bool, ChannelsReadHistoryRequest, ChannelsReadMessageContentsRequest, InputChannel,
    InputChannelConstructor, InputDialogPeer, InputDialogPeerConstructor, MessagesAffectedHistory,
    MessagesAffectedMessages, MessagesGetUnreadMentionsRequest, MessagesGetUnreadReactionsRequest,
    MessagesMarkDialogUnreadRequest, MessagesMessages, MessagesReadDiscussionRequest,
    MessagesReadHistoryRequest, MessagesReadMentionsRequest, MessagesReadMessageContentsRequest,
    MessagesReadReactionsRequest, MessagesSetTypingRequest, SendMessageAction,
    SendMessageCancelActionConstructor, SendMessageTypingActionConstructor, True, TrueConstructor,
    Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::dialogs::dtos_from_messages;
use crate::media::MediaIndex;
use crate::peers::{self, CachedPeer, PeerKind, channel_id_from_chat_id, input_peer_from_cached};
use crate::rich_rpc;
use crate::{MessageDto, MtprotoError};

pub fn read_history(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    max_id: i32,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    if cached.kind == PeerKind::Channel {
        // https://core.telegram.org/method/channels.readHistory
        let channel_id = channel_id_from_chat_id(chat_id)
            .ok_or_else(|| MtprotoError::Message("not a channel".into()))?;
        let _: Bool = api_invoke::invoke_api(
            snapshot,
            api_id,
            ChannelsReadHistoryRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id,
                    access_hash: cached.access_hash,
                })),
                max_id,
            },
        )?;
        return Ok(());
    }
    // https://core.telegram.org/method/messages.readHistory
    let _: MessagesAffectedMessages = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesReadHistoryRequest {
            peer: Box::new(input_peer_from_cached(cached)),
            max_id,
        },
    )?;
    Ok(())
}

/// https://core.telegram.org/method/messages.readMessageContents
/// https://core.telegram.org/method/channels.readMessageContents
pub fn read_message_contents(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_ids: Vec<i32>,
) -> Result<(), MtprotoError> {
    let message_ids: Vec<i32> = message_ids.into_iter().filter(|id| *id > 0).collect();
    if message_ids.is_empty() {
        return Ok(());
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let ids = Box::new(Vector::Vector(VectorConstructor {
        field_0: message_ids.len() as u32,
        field_1: message_ids,
    }));
    if cached.kind == PeerKind::Channel {
        let channel_id = channel_id_from_chat_id(chat_id)
            .ok_or_else(|| MtprotoError::Message("not a channel".into()))?;
        let _: Bool = api_invoke::invoke_api(
            snapshot,
            api_id,
            ChannelsReadMessageContentsRequest {
                channel: Box::new(InputChannel::InputChannel(InputChannelConstructor {
                    channel_id,
                    access_hash: cached.access_hash,
                })),
                id: ids,
            },
        )?;
    } else {
        let _: MessagesAffectedMessages = api_invoke::invoke_api(
            snapshot,
            api_id,
            MessagesReadMessageContentsRequest { id: ids },
        )?;
    }
    Ok(())
}

/// https://core.telegram.org/method/messages.markDialogUnread
pub fn mark_dialog_unread(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    unread: bool,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesMarkDialogUnreadRequest {
            flags: if unread { 1 } else { 0 },
            unread: unread.then(|| Box::new(True::True(TrueConstructor {}))),
            parent_peer: None,
            peer: Box::new(InputDialogPeer::InputDialogPeer(
                InputDialogPeerConstructor {
                    peer: Box::new(input_peer_from_cached(cached)),
                },
            )),
        },
    )?;
    Ok(())
}

/// https://core.telegram.org/method/messages.readDiscussion
pub fn read_discussion(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    msg_id: i32,
    read_max_id: i32,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesReadDiscussionRequest {
            peer: Box::new(input_peer_from_cached(cached)),
            msg_id,
            read_max_id,
        },
    )?;
    Ok(())
}

pub fn set_typing(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    typing: bool,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let action = if typing {
        SendMessageAction::SendMessageTypingAction(SendMessageTypingActionConstructor {})
    } else {
        SendMessageAction::SendMessageCancelAction(SendMessageCancelActionConstructor {})
    };
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesSetTypingRequest {
            flags: 0,
            peer: Box::new(input_peer_from_cached(cached)),
            top_msg_id: None,
            action: Box::new(action),
        },
    )?;
    Ok(())
}

fn top_msg_flag(value: i32) -> (u32, Option<i32>) {
    if value > 0 {
        (1_u32 << 0, Some(value))
    } else {
        (0, None)
    }
}

/// https://core.telegram.org/method/messages.getUnreadMentions
/// https://core.telegram.org/api/offsets
pub fn get_unread_mentions(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    top_msg_id: i32,
    user_id: Option<i64>,
) -> Result<Vec<MessageDto>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (flags, top) = top_msg_flag(top_msg_id);
    let response: MessagesMessages = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetUnreadMentionsRequest {
            flags,
            peer: Box::new(input_peer_from_cached(cached)),
            top_msg_id: top,
            offset_id,
            add_offset,
            limit: limit.clamp(1, 100),
            max_id: 0,
            min_id: 0,
        },
    )?;
    let mut dtos = dtos_from_messages(response, peers, media_index)?;
    rich_rpc::fill_unsupported_rich_text(snapshot, api_id, peers, user_id, &mut dtos);
    Ok(dtos)
}

/// https://core.telegram.org/method/messages.readMentions
pub fn read_mentions(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    top_msg_id: i32,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (flags, top) = top_msg_flag(top_msg_id);
    let _: MessagesAffectedHistory = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesReadMentionsRequest {
            flags,
            peer: Box::new(input_peer_from_cached(cached)),
            top_msg_id: top,
        },
    )?;
    Ok(())
}

/// https://core.telegram.org/method/messages.getUnreadReactions
/// https://core.telegram.org/api/offsets
pub fn get_unread_reactions(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    offset_id: i32,
    add_offset: i32,
    limit: i32,
    top_msg_id: i32,
    user_id: Option<i64>,
) -> Result<Vec<MessageDto>, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (flags, top) = top_msg_flag(top_msg_id);
    let response: MessagesMessages = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetUnreadReactionsRequest {
            flags,
            peer: Box::new(input_peer_from_cached(cached)),
            top_msg_id: top,
            saved_peer_id: None,
            offset_id,
            add_offset,
            limit: limit.clamp(1, 100),
            max_id: 0,
            min_id: 0,
        },
    )?;
    let mut dtos = dtos_from_messages(response, peers, media_index)?;
    rich_rpc::fill_unsupported_rich_text(snapshot, api_id, peers, user_id, &mut dtos);
    Ok(dtos)
}

/// https://core.telegram.org/method/messages.readReactions
pub fn read_reactions(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    top_msg_id: i32,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let (flags, top) = top_msg_flag(top_msg_id);
    let _: MessagesAffectedHistory = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesReadReactionsRequest {
            flags,
            peer: Box::new(input_peer_from_cached(cached)),
            top_msg_id: top,
            saved_peer_id: None,
        },
    )?;
    Ok(())
}
