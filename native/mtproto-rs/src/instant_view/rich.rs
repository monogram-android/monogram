use serde_json::{json, Map, Value};
use tellers_mtproto::latest::api::{InlineButtonType, RichText};

use crate::media;
use crate::peers::vector_boxed_items;

pub(crate) fn inline_button_href(kind: &InlineButtonType) -> Option<String> {
    match kind {
        InlineButtonType::InlineButtonTypeUrl(url) => Some(url.url.clone()),
        InlineButtonType::InlineButtonTypeUrlAuth(url) => Some(url.url.clone()),
        InlineButtonType::InlineButtonTypeWebView(web) => Some(web.url.clone()),
        InlineButtonType::InlineButtonTypeUserProfile(user) => {
            Some(format!("tg://user?id={}", user.user_id))
        }
        _ => None,
    }
}

pub(crate) fn rich_json(text: &RichText) -> Value {
    formatted_json(&rich_unbounded(text))
}

pub(crate) fn formatted_json(formatted: &media::FormattedText) -> Value {
    json!({
        "t": formatted.text,
        "e": formatted.entities.iter().map(|entity| {
            let mut row = Map::new();
            row.insert("k".into(), json!(entity.kind));
            row.insert("o".into(), json!(entity.offset));
            row.insert("l".into(), json!(entity.length));
            if let Some(url) = &entity.url {
                row.insert("u".into(), json!(url));
            }
            Value::Object(row)
        }).collect::<Vec<_>>(),
    })
}

pub(crate) fn rich_unbounded(text: &RichText) -> media::FormattedText {
    let mut out = media::FormattedText::default();
    append_rich(text, &mut out);
    out
}

pub(crate) fn append_rich(text: &RichText, out: &mut media::FormattedText) {
    let start = out.text.encode_utf16().count() as i32;
    match text {
        RichText::TextEmpty(_) | RichText::TextImage(_) => {}
        RichText::TextPlain(t) => out.text.push_str(&t.text),
        RichText::TextConcat(t) => {
            for child in vector_boxed_items(&t.texts) {
                append_rich(child, out);
            }
        }
        RichText::TextBold(t) => {
            append_rich(&t.text, out);
            mark(out, start, "bold", None);
        }
        RichText::TextItalic(t) => {
            append_rich(&t.text, out);
            mark(out, start, "italic", None);
        }
        RichText::TextUnderline(t) => {
            append_rich(&t.text, out);
            mark(out, start, "underline", None);
        }
        RichText::TextStrike(t) => {
            append_rich(&t.text, out);
            mark(out, start, "strike", None);
        }
        RichText::TextFixed(t) => {
            append_rich(&t.text, out);
            mark(out, start, "code", None);
        }
        RichText::TextBankCard(t) => {
            append_rich(&t.text, out);
            mark(out, start, "code", None);
        }
        RichText::TextSpoiler(t) => {
            append_rich(&t.text, out);
            mark(out, start, "spoiler", None);
        }
        RichText::TextMarked(t) => {
            append_rich(&t.text, out);
            mark(out, start, "spoiler", None);
        }
        RichText::TextUrl(t) => {
            append_rich(&t.text, out);
            mark(out, start, "text_url", Some(t.url.clone()));
        }
        RichText::TextEmail(t) => {
            append_rich(&t.text, out);
            mark(out, start, "email", None);
        }
        RichText::TextAutoEmail(t) => {
            append_rich(&t.text, out);
            mark(out, start, "email", None);
        }
        RichText::TextPhone(t) => {
            append_rich(&t.text, out);
            mark(out, start, "phone", None);
        }
        RichText::TextAutoPhone(t) => {
            append_rich(&t.text, out);
            mark(out, start, "phone", None);
        }
        RichText::TextHashtag(t) => {
            append_rich(&t.text, out);
            mark(out, start, "hashtag", None);
        }
        RichText::TextMention(t) => {
            append_rich(&t.text, out);
            mark(out, start, "mention", None);
        }
        RichText::TextMentionName(t) => {
            append_rich(&t.text, out);
            mark(out, start, "mention", None);
        }
        RichText::TextBotCommand(t) => {
            append_rich(&t.text, out);
            mark(out, start, "bot_command", None);
        }
        RichText::TextCashtag(t) => {
            append_rich(&t.text, out);
            mark(out, start, "cashtag", None);
        }
        RichText::TextAutoUrl(t) => {
            append_rich(&t.text, out);
            mark(out, start, "url", None);
        }
        RichText::TextCustomEmoji(t) => out.text.push_str(&t.alt),
        RichText::TextSubscript(t) => {
            append_rich(&t.text, out);
            mark(out, start, "subscript", None);
        }
        RichText::TextSuperscript(t) => {
            append_rich(&t.text, out);
            mark(out, start, "superscript", None);
        }
        RichText::TextAnchor(t) => {
            append_rich(&t.text, out);
            mark(out, start, "anchor", Some(format!("#{}", t.name)));
        }
        RichText::TextMath(t) => out.text.push_str(&t.source),
        RichText::TextDate(t) => append_rich(&t.text, out),
        RichText::TextDiff(t) => append_rich(&t.text, out),
        RichText::TextButton(t) => {
            append_rich(&t.text, out);
            if let Some(url) = inline_button_href(t.type_.as_ref()) {
                mark(out, start, "text_url", Some(url));
            }
        }
    }
}

pub(crate) fn mark(out: &mut media::FormattedText, start: i32, kind: &str, url: Option<String>) {
    let length = out.text.encode_utf16().count() as i32 - start;
    if length > 0 {
        out.entities.push(media::FormatEntity {
            kind: kind.to_string(),
            offset: start,
            length,
            url,
        });
    }
}
