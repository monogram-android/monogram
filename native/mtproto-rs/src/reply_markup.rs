//! Compact JSON for message `reply_markup`.
//! https://core.telegram.org/api/bots/buttons

use serde_json::{Value, json};
use tellers_mtproto::latest::api::{
    ButtonType, InlineButtonType, KeyboardButton, KeyboardButtonRow, KeyboardInlineButton,
    KeyboardInlineButtonRow, ReplyMarkup,
};

use crate::peers::vector_boxed_items;

pub fn to_json(markup: Option<&ReplyMarkup>) -> Option<String> {
    let markup = markup?;
    let value = match markup {
        ReplyMarkup::ReplyKeyboardHide(_) => json!({"k":"hide"}),
        ReplyMarkup::ReplyKeyboardForceReply(body) => {
            let mut obj = serde_json::Map::new();
            obj.insert("k".into(), json!("force"));
            if body.single_use.is_some() {
                obj.insert("single".into(), json!(true));
            }
            if body.selective.is_some() {
                obj.insert("selective".into(), json!(true));
            }
            if let Some(placeholder) = &body.placeholder {
                if !placeholder.is_empty() {
                    obj.insert("placeholder".into(), json!(placeholder));
                }
            }
            Value::Object(obj)
        }
        ReplyMarkup::ReplyKeyboardMarkup(body) => {
            let mut obj = serde_json::Map::new();
            obj.insert("k".into(), json!("keyboard"));
            if body.resize.is_some() {
                obj.insert("resize".into(), json!(true));
            }
            if body.single_use.is_some() {
                obj.insert("single".into(), json!(true));
            }
            if body.selective.is_some() {
                obj.insert("selective".into(), json!(true));
            }
            if body.persistent.is_some() {
                obj.insert("persistent".into(), json!(true));
            }
            if body.force_reply.is_some() {
                obj.insert("force".into(), json!(true));
            }
            if let Some(placeholder) = &body.placeholder {
                if !placeholder.is_empty() {
                    obj.insert("placeholder".into(), json!(placeholder));
                }
            }
            obj.insert("rows".into(), Value::Array(reply_rows(&body.rows)));
            Value::Object(obj)
        }
        ReplyMarkup::ReplyInlineMarkup(body) => {
            json!({
                "k": "inline",
                "rows": inline_rows(&body.rows),
            })
        }
    };
    serde_json::to_string(&value).ok()
}

fn reply_rows(rows: &tellers_mtproto::latest::api::Vector<Box<KeyboardButtonRow>>) -> Vec<Value> {
    vector_boxed_items(rows)
        .filter_map(|row| match row {
            KeyboardButtonRow::KeyboardButtonRow(body) => Some(Value::Array(
                vector_boxed_items(&body.buttons)
                    .map(reply_button)
                    .collect(),
            )),
        })
        .collect()
}

fn inline_rows(
    rows: &tellers_mtproto::latest::api::Vector<Box<KeyboardInlineButtonRow>>,
) -> Vec<Value> {
    vector_boxed_items(rows)
        .filter_map(|row| match row {
            KeyboardInlineButtonRow::KeyboardInlineButtonRow(body) => Some(Value::Array(
                vector_boxed_items(&body.buttons)
                    .map(inline_button)
                    .collect(),
            )),
        })
        .collect()
}

fn reply_button(button: &KeyboardButton) -> Value {
    let KeyboardButton::KeyboardButton(body) = button;
    let mut obj = serde_json::Map::new();
    obj.insert("x".into(), json!(body.text));
    match body.type_.as_ref() {
        ButtonType::ButtonTypeDefault(_) => {
            obj.insert("t".into(), json!("text"));
        }
        ButtonType::ButtonTypeRequestPhone(_) => {
            obj.insert("t".into(), json!("contact"));
        }
        ButtonType::ButtonTypeRequestGeoLocation(_) => {
            obj.insert("t".into(), json!("geo"));
        }
        ButtonType::ButtonTypeRequestPoll(_) => {
            obj.insert("t".into(), json!("poll"));
        }
        ButtonType::ButtonTypeRequestPeer(_) | ButtonType::InputButtonTypeRequestPeer(_) => {
            obj.insert("t".into(), json!("peer"));
        }
        ButtonType::ButtonTypeSimpleWebView(web) => {
            obj.insert("t".into(), json!("web"));
            obj.insert("u".into(), json!(web.url));
        }
        _ => {
            obj.insert("t".into(), json!("other"));
        }
    }
    Value::Object(obj)
}

fn inline_button(button: &KeyboardInlineButton) -> Value {
    let KeyboardInlineButton::KeyboardInlineButton(body) = button;
    let mut obj = serde_json::Map::new();
    obj.insert("x".into(), json!(body.text));
    match body.type_.as_ref() {
        InlineButtonType::InlineButtonTypeUrl(url) => {
            obj.insert("t".into(), json!("url"));
            obj.insert("u".into(), json!(url.url));
        }
        InlineButtonType::InlineButtonTypeCallback(cb) => {
            obj.insert("t".into(), json!("cb"));
            obj.insert("d".into(), json!(hex(&cb.data)));
            if cb.requires_password.is_some() {
                obj.insert("pw".into(), json!(true));
            }
        }
        InlineButtonType::InlineButtonTypeSwitchInline(sw) => {
            obj.insert("t".into(), json!("sw"));
            obj.insert("q".into(), json!(sw.query));
            if sw.same_peer.is_some() {
                obj.insert("same".into(), json!(true));
            }
        }
        InlineButtonType::InlineButtonTypeCopy(copy) => {
            obj.insert("t".into(), json!("copy"));
            obj.insert("c".into(), json!(copy.copy_text));
        }
        InlineButtonType::InlineButtonTypeWebView(web) => {
            obj.insert("t".into(), json!("web"));
            obj.insert("u".into(), json!(web.url));
        }
        InlineButtonType::InlineButtonTypeUrlAuth(auth) => {
            obj.insert("t".into(), json!("login"));
            obj.insert("u".into(), json!(auth.url));
        }
        InlineButtonType::InlineButtonTypeGame(_) => {
            obj.insert("t".into(), json!("game"));
        }
        InlineButtonType::InlineButtonTypeBuy(_) => {
            obj.insert("t".into(), json!("pay"));
        }
        InlineButtonType::InlineButtonTypeUserProfile(user) => {
            obj.insert("t".into(), json!("user"));
            obj.insert("u".into(), json!(user.user_id.to_string()));
        }
        InlineButtonType::InlineButtonTypeDisabled(_) => {
            obj.insert("t".into(), json!("off"));
        }
        _ => {
            obj.insert("t".into(), json!("other"));
        }
    }
    Value::Object(obj)
}

fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

pub(crate) fn from_hex(src: &str) -> Result<Vec<u8>, crate::MtprotoError> {
    if src.len() % 2 != 0 {
        return Err(crate::MtprotoError::Message("invalid callback data".into()));
    }
    let mut out = Vec::with_capacity(src.len() / 2);
    let bytes = src.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let hi = hex_nibble(bytes[i])?;
        let lo = hex_nibble(bytes[i + 1])?;
        out.push((hi << 4) | lo);
        i += 2;
    }
    Ok(out)
}

fn hex_nibble(ch: u8) -> Result<u8, crate::MtprotoError> {
    match ch {
        b'0'..=b'9' => Ok(ch - b'0'),
        b'a'..=b'f' => Ok(ch - b'a' + 10),
        b'A'..=b'F' => Ok(ch - b'A' + 10),
        _ => Err(crate::MtprotoError::Message("invalid callback data".into())),
    }
}

#[cfg(test)]
#[path = "reply_markup_tests.rs"]
mod tests;
