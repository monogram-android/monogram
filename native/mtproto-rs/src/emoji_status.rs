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
#[path = "../tests/unit/emoji_status_tests.rs"]
mod tests;
