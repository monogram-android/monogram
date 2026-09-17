use std::collections::HashMap;

use tellers_mtproto::latest::api::{
    ForumTopic, Message, MessagesForumTopics, MessagesGetForumTopicsByIdRequest,
    MessagesGetForumTopicsRequest, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use super::peers::cache_from_users_chats;
use crate::api_invoke;
use crate::media::MediaIndex;
use crate::peers::{self, input_peer_from_cached, vector_boxed_items, CachedPeer};
use crate::{ForumTopicDto, ForumTopicsPageDto, MtprotoError};

pub fn get_forum_topics(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    offset_date: i32,
    offset_id: i32,
    offset_topic: i32,
    limit: i32,
) -> Result<ForumTopicsPageDto, MtprotoError> {
    let peer = Box::new(input_peer_from_cached(peers::require_usable_peer(
        peers, chat_id,
    )?));
    let request = MessagesGetForumTopicsRequest {
        flags: 0,
        peer,
        q: None,
        offset_date,
        offset_id,
        offset_topic,
        limit: limit.clamp(1, 100),
    };
    let response: MessagesForumTopics = api_invoke::invoke_api(snapshot, api_id, request)?;
    page_from_forum_topics(response, peers, media_index)
}

/// https://core.telegram.org/method/messages.getForumTopicsByID
pub fn get_forum_topics_by_id(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    topic_ids: &[i32],
) -> Result<ForumTopicsPageDto, MtprotoError> {
    if topic_ids.is_empty() {
        return Ok(ForumTopicsPageDto {
            count: 0,
            topics: Vec::new(),
        });
    }
    let peer = Box::new(input_peer_from_cached(peers::require_usable_peer(
        peers, chat_id,
    )?));
    let request = MessagesGetForumTopicsByIdRequest {
        peer,
        topics: Box::new(Vector::Vector(VectorConstructor {
            field_0: topic_ids.len() as u32,
            field_1: topic_ids.to_vec(),
        })),
    };
    let response: MessagesForumTopics = api_invoke::invoke_api(snapshot, api_id, request)?;
    page_from_forum_topics(response, peers, media_index)
}

pub(crate) fn page_from_forum_topics(
    response: MessagesForumTopics,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
) -> Result<ForumTopicsPageDto, MtprotoError> {
    let MessagesForumTopics::MessagesForumTopics(page) = response else {
        return Err(MtprotoError::Message(
            "unexpected messages.forumTopics".into(),
        ));
    };
    cache_from_users_chats(
        peers,
        media_index,
        vector_boxed_items(&page.users).cloned(),
        vector_boxed_items(&page.chats).cloned(),
    );
    let mut previews = HashMap::new();
    for msg in vector_boxed_items(&page.messages) {
        if let Message::Message(m) = msg {
            if !m.message.is_empty() {
                previews.insert(m.id, m.message.clone());
            }
        }
    }
    let topics = vector_boxed_items(&page.topics)
        .map(|topic| {
            let preview = topic_top_message_id(topic).and_then(|id| previews.get(&id).cloned());
            map_forum_topic(topic, preview)
        })
        .collect();
    Ok(ForumTopicsPageDto {
        count: page.count,
        topics,
    })
}

pub(crate) fn topic_top_message_id(topic: &ForumTopic) -> Option<i32> {
    match topic {
        ForumTopic::ForumTopic(t) => Some(t.top_message).filter(|&id| id > 0),
        _ => None,
    }
}

pub(crate) fn map_forum_topic(topic: &ForumTopic, preview: Option<String>) -> ForumTopicDto {
    match topic {
        ForumTopic::ForumTopicDeleted(deleted) => ForumTopicDto {
            id: deleted.id,
            title: String::new(),
            icon_color: 0,
            icon_emoji_id: None,
            top_message: 0,
            date: 0,
            unread_count: 0,
            unread_mentions_count: 0,
            read_inbox_max_id: 0,
            pinned: false,
            closed: false,
            hidden: false,
            short: false,
            deleted: true,
            last_message_preview: None,
        },
        ForumTopic::ForumTopic(topic) => ForumTopicDto {
            id: topic.id,
            title: topic.title.clone(),
            icon_color: topic.icon_color,
            icon_emoji_id: topic.icon_emoji_id,
            top_message: topic.top_message,
            date: topic.date,
            unread_count: topic.unread_count,
            unread_mentions_count: topic.unread_mentions_count,
            read_inbox_max_id: topic.read_inbox_max_id,
            pinned: topic.pinned.is_some(),
            closed: topic.closed.is_some(),
            hidden: topic.hidden.is_some(),
            short: topic.short.is_some(),
            deleted: false,
            last_message_preview: preview,
        },
        _ => ForumTopicDto {
            id: 0,
            title: String::new(),
            icon_color: 0,
            icon_emoji_id: None,
            top_message: 0,
            date: 0,
            unread_count: 0,
            unread_mentions_count: 0,
            read_inbox_max_id: 0,
            pinned: false,
            closed: false,
            hidden: false,
            short: false,
            deleted: true,
            last_message_preview: None,
        },
    }
}
