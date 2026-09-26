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

#[derive(serde::Deserialize)]
struct WireEntity {
    kind: String,
    offset: i32,
    length: i32,
    url: Option<String>,
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
