//! Saved GIFs, reactions, and channel discussion RPCs (UniFFI).
//! https://core.telegram.org/method/messages.getSavedGifs
//! https://core.telegram.org/api/reactions
//! https://core.telegram.org/method/messages.getDiscussionMessage

use crate::HashMap;

use serde_json::json;
use tellers_mtproto::latest::api::{
    Chat as TlChat, Document, DocumentAttribute, HelpAppConfig, HelpGetAppConfigRequest,
    InputDocument, InputDocumentConstructor, InputGeoPoint, InputGeoPointConstructor, InputMedia,
    InputMediaDocumentConstructor, InputMediaGeoLiveConstructor, InputMediaGeoPointConstructor,
    JsonObjectValue, JsonValue, Message, MessagePeerReaction, MessagePeerVote, MessageReactions,
    MessageReplies, MessagesAppendTodoListRequest, MessagesBotCallbackAnswer,
    MessagesDiscussionMessage, MessagesGetBotCallbackAnswerRequest,
    MessagesGetCustomEmojiDocumentsRequest, MessagesGetDiscussionMessageRequest,
    MessagesGetMessageReactionsListRequest, MessagesGetPollVotesRequest,
    MessagesGetRecentReactionsRequest, MessagesGetSavedGifsRequest, MessagesGetTopReactionsRequest,
    MessagesMessageReactionsList, MessagesReactions, MessagesSavedGifs, MessagesSendMediaRequest,
    MessagesSendReactionRequest, MessagesSendVoteRequest, MessagesToggleTodoCompletedRequest,
    MessagesVotesList, Reaction, ReactionCount, ReactionCustomEmojiConstructor,
    ReactionEmojiConstructor, TextWithEntities, TextWithEntitiesConstructor, TodoItem,
    TodoItemConstructor, True, TrueConstructor, Updates, User, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::media::{self, MediaIndex, MediaLocation};
use crate::messages;
use crate::peers::{self, CachedPeer, input_peer_from_cached, peer_chat_id, vector_boxed_items, vector_items};
use crate::{
    BotCallbackAnswerDto, DiscussionDto, MessageDto, MtprotoError, ReactionChoiceDto, SavedGifDto,
};

pub fn reactions_to_json(reactions: Option<&MessageReactions>) -> Option<String> {
    let Some(MessageReactions::MessageReactions(body)) = reactions else {
        return None;
    };
    let mut rows = Vec::new();
    for count in vector_boxed_items(&body.results) {
        let ReactionCount::ReactionCount(rc) = count else {
            continue;
        };
        let chosen = rc.chosen_order.is_some();
        match rc.reaction.as_ref() {
            Reaction::ReactionEmoji(e) => rows.push(json!({
                "e": e.emoticon,
                "c": rc.count,
                "me": chosen,
            })),
            Reaction::ReactionCustomEmoji(e) => rows.push(json!({
                "d": e.document_id,
                "c": rc.count,
                "me": chosen,
            })),
            _ => {}
        }
    }
    if rows.is_empty() {
        None
    } else {
        serde_json::to_string(&rows).ok()
    }
}

pub fn replies_meta(replies: Option<&MessageReplies>) -> (i32, Option<i64>) {
    match replies {
        Some(MessageReplies::MessageReplies(r)) if r.comments.is_some() => {
            let peer = r.channel_id.map(crate::peers::chat_id_for_channel);
            (r.replies, peer)
        }
        Some(MessageReplies::MessageReplies(r)) => (r.replies, None),
        _ => (0, None),
    }
}

pub fn get_saved_gifs(
    snapshot: &mut Snapshot,
    api_id: i32,
    media_index: &mut MediaIndex,
    hash: i64,
) -> Result<(i64, Vec<SavedGifDto>), MtprotoError> {
    let response: MessagesSavedGifs =
        api_invoke::invoke_api(snapshot, api_id, MessagesGetSavedGifsRequest { hash })?;
    match response {
        MessagesSavedGifs::MessagesSavedGifsNotModified(_) => Ok((hash, Vec::new())),
        MessagesSavedGifs::MessagesSavedGifs(body) => {
            let mut out = Vec::new();
            for doc in vector_boxed_items(&body.gifs) {
                let Document::Document(d) = doc else {
                    continue;
                };
                let media = media::media_ref_from_document(d);
                let dto = SavedGifDto {
                    document_id: d.id,
                    cache_key: media.cache_key.clone(),
                    thumb_cache_key: media.thumb_cache_key.clone(),
                    width: None,
                    height: None,
                };
                media_index.insert((d.id, 0), media);
                out.push(dto);
            }
            Ok((body.hash, out))
        }
        _ => Ok((hash, Vec::new())),
    }
}

pub fn send_saved_gif(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    document_id: i64,
    reply_to_msg_id: i32,
    top_msg_id: i32,
) -> Result<MessageDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let media = media_index
        .get(&(document_id, 0))
        .cloned()
        .ok_or_else(|| MtprotoError::Message("unknown saved gif".into()))?;
    let MediaLocation::Document {
        id,
        access_hash,
        file_reference,
        ..
    } = media.location
    else {
        return Err(MtprotoError::Message("saved gif is not a document".into()));
    };
    let input = InputMedia::InputMediaDocument(InputMediaDocumentConstructor {
        flags: 0,
        spoiler: None,
        id: Box::new(InputDocument::InputDocument(InputDocumentConstructor {
            id,
            access_hash,
            file_reference,
        })),
        video_cover: None,
        video_timestamp: None,
        ttl_seconds: None,
        query: None,
    });
    let (reply_flag, reply_to) = messages::input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let request = MessagesSendMediaRequest {
        flags: reply_flag,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(cached)),
        reply_to,
        media: Box::new(input),
        message: String::new(),
        random_id: messages::random_id(),
        reply_markup: None,
        entities: None,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
        suggested_post: None,
    };
    let updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(messages::message_from_updates(
        updates,
        chat_id,
        "",
        media_index,
    ))
}

pub fn send_reaction(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
    emoticon: String,
    document_id: i64,
) -> Result<(), MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let reaction = if !emoticon.is_empty() {
        Some(Box::new(Reaction::ReactionEmoji(
            ReactionEmojiConstructor { emoticon },
        )))
    } else if document_id != 0 {
        Some(Box::new(Reaction::ReactionCustomEmoji(
            ReactionCustomEmojiConstructor { document_id },
        )))
    } else {
        None
    };
    let items = reaction.into_iter().collect::<Vec<_>>();
    let flag = if items.is_empty() {
        0
    } else {
        MessagesSendReactionRequest::REACTION_FLAG | MessagesSendReactionRequest::ADD_TO_RECENT_FLAG
    };
    let request = MessagesSendReactionRequest {
        flags: flag,
        big: None,
        add_to_recent: if items.is_empty() {
            None
        } else {
            Some(Box::new(True::True(TrueConstructor {})))
        },
        peer: Box::new(input_peer_from_cached(cached)),
        msg_id: message_id,
        reaction: if items.is_empty() {
            None
        } else {
            Some(Box::new(Vector::Vector(VectorConstructor {
                field_0: items.len() as u32,
                field_1: items,
            })))
        },
    };
    let _: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(())
}

pub fn get_discussion_message(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &mut HashMap<i64, CachedPeer>,
    media_index: &mut MediaIndex,
    chat_id: i64,
    message_id: i32,
) -> Result<DiscussionDto, MtprotoError> {
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let response: MessagesDiscussionMessage = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetDiscussionMessageRequest {
            peer: Box::new(input_peer_from_cached(cached)),
            msg_id: message_id,
        },
    )?;
    let MessagesDiscussionMessage::MessagesDiscussionMessage(body) = response else {
        return Err(MtprotoError::Message("unexpected discussionMessage".into()));
    };
    let users: Vec<User> = vector_boxed_items(&body.users).cloned().collect();
    let chats: Vec<TlChat> = vector_boxed_items(&body.chats).cloned().collect();
    crate::dialogs::cache_from_users_chats(
        peers,
        media_index,
        users.into_iter(),
        chats.into_iter(),
    );
    // Reverse chronological: last Message is the auto-forwarded channel post
    // that starts the comment thread.
    // https://core.telegram.org/api/discussion
    let mut thread: Option<(i64, i32)> = None;
    for msg in vector_boxed_items(&body.messages) {
        if let Message::Message(m) = msg {
            thread = Some((peer_chat_id(&m.peer_id), m.id));
        }
    }
    thread
        .map(|(chat_id, message_id)| DiscussionDto {
            chat_id,
            message_id,
        })
        .ok_or_else(|| MtprotoError::Message("no discussion thread".into()))
}

fn reaction_choices(reactions: &MessagesReactions) -> Vec<ReactionChoiceDto> {
    let MessagesReactions::MessagesReactions(body) = reactions else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for item in vector_boxed_items(&body.reactions) {
        match item {
            Reaction::ReactionEmoji(e) => out.push(ReactionChoiceDto {
                emoticon: e.emoticon.clone(),
                document_id: 0,
            }),
            Reaction::ReactionCustomEmoji(e) => out.push(ReactionChoiceDto {
                emoticon: String::new(),
                document_id: e.document_id,
            }),
            _ => {}
        }
    }
    out
}

pub fn get_recent_reactions(
    snapshot: &mut Snapshot,
    api_id: i32,
) -> Result<Vec<ReactionChoiceDto>, MtprotoError> {
    let mut out = Vec::new();
    let recent: MessagesReactions = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetRecentReactionsRequest { limit: 32, hash: 0 },
    )?;
    out.extend(reaction_choices(&recent));
    let top: MessagesReactions = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetTopReactionsRequest { limit: 16, hash: 0 },
    )?;
    for choice in reaction_choices(&top) {
        if !out.iter().any(|existing| {
            existing.document_id == choice.document_id && existing.emoticon == choice.emoticon
        }) {
            out.push(choice);
        }
    }
    Ok(out)
}

pub fn reaction_update_json(reactions: &MessageReactions) -> String {
    reactions_to_json(Some(reactions)).unwrap_or_else(|| "[]".into())
}

/// https://core.telegram.org/api/config#message-animated-emoji-max
pub fn animated_emoji_max(snapshot: &mut Snapshot, api_id: i32) -> Result<i32, MtprotoError> {
    let cfg: HelpAppConfig =
        api_invoke::invoke_api(snapshot, api_id, HelpGetAppConfigRequest { hash: 0 })?;
    let HelpAppConfig::HelpAppConfig(body) = cfg else {
        return Ok(0);
    };
    Ok(json_object_int(body.config.as_ref(), "message_animated_emoji_max").unwrap_or(0))
}

fn json_object_int(value: &JsonValue, key: &str) -> Option<i32> {
    let JsonValue::JsonObject(obj) = value else {
        return None;
    };
    for item in vector_boxed_items(&obj.value) {
        let JsonObjectValue::JsonObjectValue(entry) = item else {
            continue;
        };
        if entry.key != key {
            continue;
        }
        return match entry.value.as_ref() {
            JsonValue::JsonNumber(n) => Some(n.value as i32),
            JsonValue::JsonString(s) => s.value.parse().ok(),
            _ => None,
        };
    }
    None
}

/// https://core.telegram.org/api/custom-emoji — the `free` flag on
/// `documentAttributeCustomEmoji`.
pub fn custom_emoji_is_free(
    snapshot: &mut Snapshot,
    api_id: i32,
    document_id: i64,
) -> Result<bool, MtprotoError> {
    let docs: Vector<Box<Document>> = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetCustomEmojiDocumentsRequest {
            document_id: Box::new(Vector::Vector(VectorConstructor {
                field_0: 1,
                field_1: vec![document_id],
            })),
        },
    )?;
    let Some(Document::Document(doc)) = vector_boxed_items(&docs).next() else {
        return Ok(false);
    };
    for attr in vector_boxed_items(&doc.attributes) {
        if let DocumentAttribute::DocumentAttributeCustomEmoji(emoji) = attr {
            // `free` is a flags.0 conditional field in the TL schema. Read the
            // wire flag directly so this remains compatible with generated
            // bindings that omit optional convenience fields.
            return Ok(emoji.flags & 1 != 0);
        }
    }
    Ok(false)
}

/// https://core.telegram.org/method/messages.getBotCallbackAnswer
/// https://core.telegram.org/api/bots/buttons
pub fn get_bot_callback_answer(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
    data_hex: &str,
) -> Result<BotCallbackAnswerDto, MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let data = crate::reply_markup::from_hex(data_hex)?;
    let response: MessagesBotCallbackAnswer = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetBotCallbackAnswerRequest {
            flags: MessagesGetBotCallbackAnswerRequest::DATA_FLAG,
            game: None,
            peer: Box::new(input_peer_from_cached(cached)),
            msg_id: message_id,
            data: Some(data),
            password: None,
        },
    )?;
    let MessagesBotCallbackAnswer::MessagesBotCallbackAnswer(body) = response;
    Ok(BotCallbackAnswerDto {
        alert: body.alert.is_some(),
        message: body.message.filter(|s| !s.is_empty()),
        url: body.url.filter(|s| !s.is_empty()),
        cache_time: body.cache_time,
    })
}

/// https://core.telegram.org/method/messages.toggleTodoCompleted
/// https://core.telegram.org/api/todo
pub fn toggle_todo_completed(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
    completed: &[i32],
    incompleted: &[i32],
) -> Result<(), MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let _updates: Updates = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesToggleTodoCompletedRequest {
            peer: Box::new(input_peer_from_cached(cached)),
            msg_id: message_id,
            completed: Box::new(Vector::Vector(VectorConstructor {
                field_0: completed.len() as u32,
                field_1: completed.to_vec(),
            })),
            incompleted: Box::new(Vector::Vector(VectorConstructor {
                field_0: incompleted.len() as u32,
                field_1: incompleted.to_vec(),
            })),
        },
    )?;
    Ok(())
}

/// https://core.telegram.org/method/messages.sendMedia
/// https://core.telegram.org/constructor/inputMediaGeoPoint
/// https://core.telegram.org/constructor/inputMediaGeoLive
pub fn send_location(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    latitude: f64,
    longitude: f64,
    live_period: i32,
    heading: i32,
    reply_to_msg_id: i32,
) -> Result<(), MtprotoError> {
    if !latitude.is_finite() || !longitude.is_finite() {
        return Err(MtprotoError::Message("location is not finite".into()));
    }
    if !(-90.0..=90.0).contains(&latitude) || !(-180.0..=180.0).contains(&longitude) {
        return Err(MtprotoError::Message("location out of range".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let point = InputGeoPoint::InputGeoPoint(InputGeoPointConstructor {
        flags: 0,
        lat: latitude,
        long: longitude,
        accuracy_radius: None,
    });
    let media = if live_period > 0 {
        let mut flags = InputMediaGeoLiveConstructor::PERIOD_FLAG;
        if heading > 0 {
            flags |= InputMediaGeoLiveConstructor::HEADING_FLAG;
        }
        InputMedia::InputMediaGeoLive(InputMediaGeoLiveConstructor {
            flags,
            stopped: None,
            geo_point: Box::new(point),
            heading: (heading > 0).then_some(heading),
            period: Some(live_period),
            proximity_notification_radius: None,
        })
    } else {
        InputMedia::InputMediaGeoPoint(InputMediaGeoPointConstructor {
            geo_point: Box::new(point),
        })
    };
    let (reply_flag, reply_to) = messages::input_reply_to_thread(reply_to_msg_id, 0);
    let request = MessagesSendMediaRequest {
        flags: reply_flag,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(cached)),
        reply_to,
        media: Box::new(media),
        message: String::new(),
        random_id: messages::random_id(),
        reply_markup: None,
        entities: None,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
        suggested_post: None,
    };
    let _updates: Updates = api_invoke::invoke_api(snapshot, api_id, request)?;
    Ok(())
}

/// https://core.telegram.org/method/messages.sendVote
pub fn send_poll_vote(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
    options: &[Vec<u8>],
) -> Result<(), MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    if options.is_empty() {
        return Err(MtprotoError::Message("no poll options selected".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let _updates: Updates = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesSendVoteRequest {
            peer: Box::new(input_peer_from_cached(cached)),
            msg_id: message_id,
            options: Box::new(Vector::Vector(VectorConstructor {
                field_0: options.len() as u32,
                field_1: options.to_vec(),
            })),
        },
    )?;
    Ok(())
}

/// https://core.telegram.org/method/messages.appendTodoList
/// https://core.telegram.org/api/todo
pub fn append_todo_items(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
    first_id: i32,
    titles: &[String],
) -> Result<(), MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    let cleaned: Vec<&String> = titles
        .iter()
        .filter(|title| !title.trim().is_empty())
        .collect();
    if cleaned.is_empty() {
        return Err(MtprotoError::Message("no checklist items given".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let items: Vec<Box<TodoItem>> = cleaned
        .iter()
        .enumerate()
        .map(|(offset, title)| {
            Box::new(TodoItem::TodoItem(TodoItemConstructor {
                id: first_id + offset as i32,
                title: Box::new(TextWithEntities::TextWithEntities(
                    TextWithEntitiesConstructor {
                        text: title.trim().to_owned(),
                        entities: Box::new(Vector::Vector(VectorConstructor {
                            field_0: 0,
                            field_1: Vec::new(),
                        })),
                    },
                )),
            }))
        })
        .collect();
    let _updates: Updates = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesAppendTodoListRequest {
            peer: Box::new(input_peer_from_cached(cached)),
            msg_id: message_id,
            list: Box::new(Vector::Vector(VectorConstructor {
                field_0: items.len() as u32,
                field_1: items,
            })),
        },
    )?;
    Ok(())
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReactionPeerDto {
    pub peer_id: i64,
    pub title: String,
    pub date: i32,
    pub emoticon: String,
    pub document_id: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReactionPeersDto {
    pub peers: Vec<ReactionPeerDto>,
    pub count: i32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PollVoterDto {
    pub peer_id: i64,
    pub title: String,
    pub date: i32,
    pub options: Vec<Vec<u8>>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PollVotersDto {
    pub voters: Vec<PollVoterDto>,
    pub count: i32,
}

fn user_title(user: &User) -> (i64, crate::CompactString) {
    match user {
        User::User(u) => {
            let name = [
                u.first_name.as_deref().unwrap_or(""),
                u.last_name.as_deref().unwrap_or(""),
            ]
            .into_iter()
            .filter(|part| !part.is_empty())
            .collect::<crate::SmallVec<[&str; 2]>>()
            .join(" ");
            (
                u.id,
                if name.is_empty() {
                    crate::CompactString::from(format!("User {}", u.id))
                } else {
                    crate::CompactString::from(name)
                },
            )
        }
        User::UserEmpty(u) => (u.id, crate::CompactString::from(format!("User {}", u.id))),
        _ => (0, crate::CompactString::from("User")),
    }
}

pub fn get_message_reactions_list(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
) -> Result<ReactionPeersDto, MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let response: MessagesMessageReactionsList = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetMessageReactionsListRequest {
            flags: 0,
            peer: Box::new(input_peer_from_cached(cached)),
            id: message_id,
            reaction: None,
            offset: None,
            limit: 50,
        },
    )?;
    let MessagesMessageReactionsList::MessagesMessageReactionsList(body) = response else {
        return Err(MtprotoError::Message(
            "unexpected messageReactionsList".into(),
        ));
    };
    let titles: HashMap<i64, crate::CompactString> = vector_boxed_items(&body.users)
        .map(|user| user_title(user))
        .collect();
    let mut peers_out = Vec::new();
    for row in vector_boxed_items(&body.reactions) {
        let MessagePeerReaction::MessagePeerReaction(item) = row else {
            continue;
        };
        let peer_id = peers::peer_chat_id(&item.peer_id);
        let (emoticon, document_id) = match item.reaction.as_ref() {
            Reaction::ReactionEmoji(e) => (e.emoticon.clone(), 0),
            Reaction::ReactionCustomEmoji(e) => (String::new(), e.document_id),
            _ => (String::new(), 0),
        };
        peers_out.push(ReactionPeerDto {
            peer_id,
            title: titles
                .get(&peer_id)
                .map(|name| name.to_string())
                .unwrap_or_default(),
            date: item.date,
            emoticon,
            document_id,
        });
    }
    Ok(ReactionPeersDto {
        peers: peers_out,
        count: body.count,
    })
}

pub(crate) fn poll_vote_options(row: &MessagePeerVote) -> Vec<Vec<u8>> {
    match row {
        MessagePeerVote::MessagePeerVote(v) => vec![v.option.clone()],
        MessagePeerVote::MessagePeerVoteInputOption(_) => Vec::new(),
        MessagePeerVote::MessagePeerVoteMultiple(v) => vector_items(v.options.as_ref()).to_vec(),
    }
}

pub fn get_poll_votes(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    message_id: i32,
) -> Result<PollVotersDto, MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    let cached = peers::require_usable_peer(peers, chat_id)?;
    let response: MessagesVotesList = api_invoke::invoke_api(
        snapshot,
        api_id,
        MessagesGetPollVotesRequest {
            flags: 0,
            peer: Box::new(input_peer_from_cached(cached)),
            id: message_id,
            option: None,
            offset: None,
            limit: 50,
        },
    )?;
    let MessagesVotesList::MessagesVotesList(body) = response else {
        return Err(MtprotoError::Message("unexpected votesList".into()));
    };
    let titles: HashMap<i64, crate::CompactString> = vector_boxed_items(&body.users)
        .map(|user| user_title(user))
        .collect();
    let mut voters = Vec::new();
    for row in vector_boxed_items(&body.votes) {
        let (peer, date) = match row {
            MessagePeerVote::MessagePeerVote(v) => (*v.peer.clone(), v.date),
            MessagePeerVote::MessagePeerVoteInputOption(v) => (*v.peer.clone(), v.date),
            MessagePeerVote::MessagePeerVoteMultiple(v) => (*v.peer.clone(), v.date),
        };
        let options = poll_vote_options(row);
        let peer_id = peers::peer_chat_id(&peer);
        voters.push(PollVoterDto {
            peer_id,
            title: titles
                .get(&peer_id)
                .map(|name| name.to_string())
                .unwrap_or_default(),
            date,
            options,
        });
    }
    Ok(PollVotersDto {
        voters,
        count: body.count,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{
        MessagePeerVoteConstructor, MessagePeerVoteInputOptionConstructor,
        MessagePeerVoteMultipleConstructor, Peer, PeerUserConstructor,
    };

    fn user_peer(id: i64) -> Box<Peer> {
        Box::new(Peer::PeerUser(PeerUserConstructor { user_id: id }))
    }

    #[test]
    fn single_vote_keeps_option_bytes() {
        let row = MessagePeerVote::MessagePeerVote(MessagePeerVoteConstructor {
            peer: user_peer(7),
            option: vec![0x01, 0xa0],
            date: 11,
        });
        assert_eq!(poll_vote_options(&row), vec![vec![0x01, 0xa0]]);
    }

    #[test]
    fn input_option_vote_has_no_bytes() {
        let row = MessagePeerVote::MessagePeerVoteInputOption(
            MessagePeerVoteInputOptionConstructor {
                peer: user_peer(7),
                date: 11,
            },
        );
        assert!(poll_vote_options(&row).is_empty());
    }

    #[test]
    fn multiple_vote_keeps_every_option() {
        let row = MessagePeerVote::MessagePeerVoteMultiple(MessagePeerVoteMultipleConstructor {
            peer: user_peer(7),
            options: Box::new(Vector::Vector(VectorConstructor {
                field_0: 2,
                field_1: vec![b"a".to_vec(), b"b".to_vec()],
            })),
            date: 11,
        });
        assert_eq!(poll_vote_options(&row), vec![b"a".to_vec(), b"b".to_vec()]);
    }
}
