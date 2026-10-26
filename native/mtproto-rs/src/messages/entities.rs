//! https://core.telegram.org/type/MessageEntity

use tellers_mtproto::latest::api::{
    MessageEntity, MessageEntityBlockquoteConstructor, MessageEntityBoldConstructor,
    MessageEntityCodeConstructor, MessageEntityCustomEmojiConstructor,
    MessageEntityItalicConstructor, MessageEntityPreConstructor, MessageEntitySpoilerConstructor,
    MessageEntityStrikeConstructor, MessageEntityTextUrlConstructor,
    MessageEntityUnderlineConstructor, MessagesSendMessageRequest, True, TrueConstructor, Vector,
    VectorConstructor,
};

use crate::MtprotoError;
use tellers_mtproto::latest::api as tl;

#[derive(serde::Deserialize)]
struct WireEntity {
    kind: String,
    offset: i32,
    length: i32,
    url: Option<String>,
}

pub(crate) fn input_entities_from_json(
    json: Option<&str>,
    peers: &crate::HashMap<i64, crate::peers::CachedPeer>,
) -> Result<(u32, Option<Box<Vector<Box<MessageEntity>>>>), MtprotoError> {
    let (flag, mut entities) = entities_from_json(json)?;
    if let Some(vector) = entities.as_deref_mut() {
        if let Vector::Vector(items) = vector {
            for entity in &mut items.field_1 {
                if let MessageEntity::MessageEntityMentionName(mention) = entity.as_ref() {
                    let peer = crate::peers::require_usable_peer(peers, mention.user_id)?;
                    if peer.kind != crate::peers::PeerKind::User
                        || peer.id != mention.user_id
                        || peer.id <= 0
                    {
                        return Err(MtprotoError::Message(
                            "named mention requires a full user peer".into(),
                        ));
                    }
                    **entity = MessageEntity::InputMessageEntityMentionName(
                        tl::InputMessageEntityMentionNameConstructor {
                            offset: mention.offset,
                            length: mention.length,
                            user_id: Box::new(tl::InputUser::InputUser(tl::InputUserConstructor {
                                user_id: peer.id,
                                access_hash: peer.access_hash,
                            })),
                        },
                    );
                }
            }
        }
    }
    Ok((flag, entities))
}

pub(crate) fn entities_from_json(
    json: Option<&str>,
) -> Result<(u32, Option<Box<Vector<Box<MessageEntity>>>>), MtprotoError> {
    let Some(raw) = json.map(str::trim).filter(|s| !s.is_empty()) else {
        return Ok((0, None));
    };
    let parsed: Vec<WireEntity> = serde_json::from_str(raw)
        .map_err(|e| MtprotoError::Message(format!("entities json: {e}")))?;
    let items: Vec<Box<MessageEntity>> = parsed
        .into_iter()
        .filter_map(wire_entity)
        .map(Box::new)
        .collect();
    if items.is_empty() {
        return Ok((0, None));
    }
    Ok((
        MessagesSendMessageRequest::ENTITIES_FLAG,
        Some(Box::new(Vector::Vector(VectorConstructor {
            field_0: items.len() as u32,
            field_1: items,
        }))),
    ))
}

fn wire_entity(entity: WireEntity) -> Option<MessageEntity> {
    if entity.length <= 0 || entity.offset < 0 {
        return None;
    }
    let offset = entity.offset;
    let length = entity.length;
    Some(match entity.kind.as_str() {
        "bold" | "heading" => {
            MessageEntity::MessageEntityBold(MessageEntityBoldConstructor { offset, length })
        }
        "italic" => {
            MessageEntity::MessageEntityItalic(MessageEntityItalicConstructor { offset, length })
        }
        "underline" => MessageEntity::MessageEntityUnderline(MessageEntityUnderlineConstructor {
            offset,
            length,
        }),
        "strike" => {
            MessageEntity::MessageEntityStrike(MessageEntityStrikeConstructor { offset, length })
        }
        "code" => MessageEntity::MessageEntityCode(MessageEntityCodeConstructor { offset, length }),
        "pre" => MessageEntity::MessageEntityPre(MessageEntityPreConstructor {
            offset,
            length,
            language: entity.url.unwrap_or_default(),
        }),
        "spoiler" => {
            MessageEntity::MessageEntitySpoiler(MessageEntitySpoilerConstructor { offset, length })
        }
        "blockquote" => {
            let collapsed = entity.url.as_deref() == Some("collapsed");
            MessageEntity::MessageEntityBlockquote(MessageEntityBlockquoteConstructor {
                flags: if collapsed {
                    MessageEntityBlockquoteConstructor::COLLAPSED_FLAG
                } else {
                    0
                },
                collapsed: collapsed.then(|| Box::new(True::True(TrueConstructor {}))),
                offset,
                length,
            })
        }
        "url" => {
            MessageEntity::MessageEntityUrl(tl::MessageEntityUrlConstructor { offset, length })
        }
        "mention" => MessageEntity::MessageEntityMention(tl::MessageEntityMentionConstructor {
            offset,
            length,
        }),
        "hashtag" => MessageEntity::MessageEntityHashtag(tl::MessageEntityHashtagConstructor {
            offset,
            length,
        }),
        "bot_command" => {
            MessageEntity::MessageEntityBotCommand(tl::MessageEntityBotCommandConstructor {
                offset,
                length,
            })
        }
        "email" => {
            MessageEntity::MessageEntityEmail(tl::MessageEntityEmailConstructor { offset, length })
        }
        "phone" => {
            MessageEntity::MessageEntityPhone(tl::MessageEntityPhoneConstructor { offset, length })
        }
        "cashtag" => MessageEntity::MessageEntityCashtag(tl::MessageEntityCashtagConstructor {
            offset,
            length,
        }),
        "bank_card" => MessageEntity::MessageEntityBankCard(tl::MessageEntityBankCardConstructor {
            offset,
            length,
        }),
        "text_url" => {
            let url = entity.url.filter(|s| !s.is_empty())?;
            MessageEntity::MessageEntityTextUrl(MessageEntityTextUrlConstructor {
                offset,
                length,
                url,
            })
        }
        "custom_emoji" => {
            let document_id = entity.url.as_deref()?.parse().ok()?;
            MessageEntity::MessageEntityCustomEmoji(MessageEntityCustomEmojiConstructor {
                offset,
                length,
                document_id,
            })
        }
        "mention_name" => {
            let user_id = entity.url.as_deref()?.parse().ok()?;
            MessageEntity::MessageEntityMentionName(
                tellers_mtproto::latest::api::MessageEntityMentionNameConstructor {
                    offset,
                    length,
                    user_id,
                },
            )
        }
        _ => return None,
    })
}

#[cfg(test)]
#[path = "../../tests/unit/messages_entities_tests.rs"]
mod tests;
