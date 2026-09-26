//! https://core.telegram.org/constructor/emojiStatus
//! https://core.telegram.org/constructor/emojiStatusCollectible

use tellers_mtproto::latest::api::EmojiStatus;

pub fn emoji_status_document_id(status: Option<&EmojiStatus>) -> Option<i64> {
    let status = status?;
    let now = unix_now();
    match status {
        EmojiStatus::EmojiStatusEmpty(_) => None,
        EmojiStatus::EmojiStatus(value) => {
            if expired(value.until, now) {
                None
            } else {
                Some(value.document_id)
            }
        }
        EmojiStatus::EmojiStatusCollectible(value) => {
            if expired(value.until, now) {
                None
            } else {
                Some(value.document_id)
            }
        }
        EmojiStatus::InputEmojiStatusCollectible(_) => None,
        _ => None,
    }
}

fn expired(until: Option<i32>, now: i64) -> bool {
    until.is_some_and(|until| i64::from(until) <= now)
}

fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::emoji_status_document_id;
    use tellers_mtproto::latest::api::{
        EmojiStatus, EmojiStatusCollectibleConstructor, EmojiStatusConstructor,
        EmojiStatusEmptyConstructor,
    };

    #[test]
    fn empty_and_missing_are_none() {
        assert_eq!(emoji_status_document_id(None), None);
        assert_eq!(
            emoji_status_document_id(Some(&EmojiStatus::EmojiStatusEmpty(
                EmojiStatusEmptyConstructor {}
            ))),
            None
        );
    }

    #[test]
    fn regular_and_collectible_use_document_id() {
        let regular = EmojiStatus::EmojiStatus(EmojiStatusConstructor {
            flags: 0,
            document_id: 42,
            until: None,
        });
        assert_eq!(emoji_status_document_id(Some(&regular)), Some(42));
        let gift = EmojiStatus::EmojiStatusCollectible(EmojiStatusCollectibleConstructor {
            flags: 0,
            collectible_id: 9,
            document_id: 77,
            title: "Star".into(),
            slug: "star".into(),
            pattern_document_id: 0,
            center_color: 0,
            edge_color: 0,
            pattern_color: 0,
            text_color: 0,
            until: None,
        });
        assert_eq!(emoji_status_document_id(Some(&gift)), Some(77));
    }

    #[test]
    fn expired_until_hides_status() {
        let expired = EmojiStatus::EmojiStatus(EmojiStatusConstructor {
            flags: 1,
            document_id: 42,
            until: Some(1),
        });
        assert_eq!(emoji_status_document_id(Some(&expired)), None);
    }
}
