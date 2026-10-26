use tellers_mtproto::codec::{Boxed, TlEncode};
use tellers_mtproto::latest::api::{
    AccountGetNotifySettingsRequest, AccountUpdateNotifySettingsRequest, Bool,
    BoolFalseConstructor, BoolTrueConstructor, InputNotifyForumTopicConstructor, InputNotifyPeer,
    InputPeer, InputPeerNotifySettings, MessagesAffectedHistory, MessagesCreateForumTopicRequest,
    MessagesDeleteTopicHistoryRequest, MessagesEditForumTopicRequest,
    MessagesReorderPinnedForumTopicsRequest, MessagesUpdatePinnedForumTopicRequest,
    PeerNotifySettings, True, TrueConstructor, Updates, UpdatesTooLongConstructor, Vector,
    VectorConstructor,
};

use super::group_management::group_peer;
use super::management::{HistoryDeletion, enqueue_response};
use super::{call_with_migrate, with_client_mut};
use crate::MtprotoError;

#[derive(Debug, Clone, Default)]
pub struct TopicChanges {
    pub title: Option<String>,
    pub icon_emoji_id: Option<i64>,
    pub closed: Option<bool>,
    pub hidden: Option<bool>,
}

/// Persist and reuse `random_id` when retrying the same topic creation.
pub fn create_topic(
    handle: u64,
    chat_id: i64,
    title: &str,
    random_id: i64,
    icon_color: Option<i32>,
    icon_emoji_id: Option<i64>,
) -> Result<Updates, MtprotoError> {
    if title.trim().is_empty() || random_id == 0 {
        return Err(MtprotoError::Message(
            "a title and nonzero random id are required".into(),
        ));
    }
    topic_updates(handle, chat_id, |peer| MessagesCreateForumTopicRequest {
        flags: u32::from(icon_color.is_some()) | (u32::from(icon_emoji_id.is_some()) << 3),
        title_missing: None,
        peer: Box::new(peer),
        title: title.to_owned(),
        icon_color,
        icon_emoji_id,
        random_id,
        send_as: None,
    })
}

pub fn edit_topic(
    handle: u64,
    chat_id: i64,
    topic_id: i32,
    changes: TopicChanges,
) -> Result<Updates, MtprotoError> {
    validate_topic(topic_id)?;
    if changes
        .title
        .as_ref()
        .is_some_and(|title| title.trim().is_empty())
        || (changes.title.is_none()
            && changes.icon_emoji_id.is_none()
            && changes.closed.is_none()
            && changes.hidden.is_none())
    {
        return Err(MtprotoError::Message(
            "a nonempty topic change is required".into(),
        ));
    }
    if changes.hidden.is_some() && topic_id != 1 {
        return Err(MtprotoError::Message(
            "only the general topic may be hidden".into(),
        ));
    }
    topic_updates(handle, chat_id, |peer| {
        edit_request(peer, topic_id, &changes)
    })
}

pub fn pin_topic(
    handle: u64,
    chat_id: i64,
    topic_id: i32,
    pinned: bool,
) -> Result<Updates, MtprotoError> {
    validate_topic(topic_id)?;
    topic_updates(handle, chat_id, |peer| {
        MessagesUpdatePinnedForumTopicRequest {
            peer: Box::new(peer),
            topic_id,
            pinned: Box::new(tl_bool(pinned)),
        }
    })
}

pub fn reorder_pinned_topics(
    handle: u64,
    chat_id: i64,
    order: &[i32],
    force: bool,
) -> Result<Updates, MtprotoError> {
    let mut seen = std::collections::HashSet::new();
    for id in order {
        validate_topic(*id)?;
        if !seen.insert(*id) {
            return Err(MtprotoError::Message(
                "duplicate topic in pinned order".into(),
            ));
        }
    }
    let count = order
        .len()
        .try_into()
        .map_err(|_| MtprotoError::Message("too many topics".into()))?;
    topic_updates(handle, chat_id, |peer| {
        MessagesReorderPinnedForumTopicsRequest {
            flags: u32::from(force),
            force: force.then(|| Box::new(True::True(TrueConstructor {}))),
            peer: Box::new(peer),
            order: Box::new(Vector::Vector(VectorConstructor {
                field_0: count,
                field_1: order.to_vec(),
            })),
        }
    })
}

/// Repeat while `more` is true. The response never advances a cursor past
/// unapplied updates; channel history is recovered with getChannelDifference.
pub fn delete_topic(
    handle: u64,
    chat_id: i64,
    topic_id: i32,
) -> Result<HistoryDeletion, MtprotoError> {
    validate_topic(topic_id)?;
    if topic_id == 1 {
        return Err(MtprotoError::Message(
            "the general topic cannot be deleted".into(),
        ));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            let response: MessagesAffectedHistory = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                MessagesDeleteTopicHistoryRequest {
                    peer: Box::new(peer),
                    top_msg_id: topic_id,
                },
            )?;
            if crate::peers::is_channel_chat_id(chat_id) {
                super::prepare_channel_recovery(
                    &mut state.channel_recovery,
                    vec![chat_id],
                    0,
                    super::recovery_now(),
                );
            } else {
                enqueue_response(
                    state.snapshot.dc_id,
                    &Updates::UpdatesTooLong(UpdatesTooLongConstructor {}),
                )?;
            }
            let MessagesAffectedHistory::MessagesAffectedHistory(result) = response;
            Ok(HistoryDeletion {
                more: result.offset > 0,
            })
        })
    })
}

pub fn get_topic_notifications(
    handle: u64,
    chat_id: i64,
    topic_id: i32,
) -> Result<PeerNotifySettings, MtprotoError> {
    validate_topic(topic_id)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                AccountGetNotifySettingsRequest {
                    peer: Box::new(InputNotifyPeer::InputNotifyForumTopic(
                        InputNotifyForumTopicConstructor {
                            peer: Box::new(peer),
                            top_msg_id: topic_id,
                        },
                    )),
                },
            )
        })
    })
}

pub fn set_topic_notifications(
    handle: u64,
    chat_id: i64,
    topic_id: i32,
    settings: InputPeerNotifySettings,
) -> Result<(), MtprotoError> {
    validate_topic(topic_id)?;
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            let response: Bool = crate::api_invoke::invoke_api(
                &mut state.snapshot,
                state.api_id,
                AccountUpdateNotifySettingsRequest {
                    peer: Box::new(InputNotifyPeer::InputNotifyForumTopic(
                        InputNotifyForumTopicConstructor {
                            peer: Box::new(peer),
                            top_msg_id: topic_id,
                        },
                    )),
                    settings: Box::new(settings.clone()),
                },
            )?;
            match response {
                Bool::BoolTrue(_) => Ok(()),
                Bool::BoolFalse(_) => Err(MtprotoError::Message(
                    "topic notification change was not applied".into(),
                )),
            }
        })
    })
}

fn topic_updates<Req: TlEncode + Boxed + Clone>(
    handle: u64,
    chat_id: i64,
    request: impl Fn(InputPeer) -> Req,
) -> Result<Updates, MtprotoError> {
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            let peer = group_peer(state, chat_id)?;
            let response: Updates =
                crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request(peer))?;
            enqueue_response(state.snapshot.dc_id, &response)?;
            Ok(response)
        })
    })
}

fn edit_request(
    peer: InputPeer,
    topic_id: i32,
    changes: &TopicChanges,
) -> MessagesEditForumTopicRequest {
    MessagesEditForumTopicRequest {
        flags: u32::from(changes.title.is_some())
            | (u32::from(changes.icon_emoji_id.is_some()) << 1)
            | (u32::from(changes.closed.is_some()) << 2)
            | (u32::from(changes.hidden.is_some()) << 3),
        peer: Box::new(peer),
        topic_id,
        title: changes.title.clone(),
        icon_emoji_id: changes.icon_emoji_id,
        closed: changes.closed.map(|value| Box::new(tl_bool(value))),
        hidden: changes.hidden.map(|value| Box::new(tl_bool(value))),
    }
}

fn tl_bool(value: bool) -> Bool {
    if value {
        Bool::BoolTrue(BoolTrueConstructor {})
    } else {
        Bool::BoolFalse(BoolFalseConstructor {})
    }
}

fn validate_topic(topic_id: i32) -> Result<(), MtprotoError> {
    if topic_id <= 0 {
        return Err(MtprotoError::Message(
            "a positive topic id is required".into(),
        ));
    }
    Ok(())
}

#[cfg(test)]
#[path = "../../tests/unit/client_forum_management_tests.rs"]
mod tests;
