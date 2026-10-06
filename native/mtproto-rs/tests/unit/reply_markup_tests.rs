use super::*;
use tellers_mtproto::latest::api::{
    ButtonTypeDefaultConstructor, InlineButtonTypeCallbackConstructor,
    InlineButtonTypeUrlConstructor, KeyboardButtonConstructor, KeyboardButtonRowConstructor,
    KeyboardInlineButtonConstructor, KeyboardInlineButtonRowConstructor,
    ReplyInlineMarkupConstructor, ReplyKeyboardHideConstructor, ReplyKeyboardMarkupConstructor,
    Vector, VectorConstructor,
};

fn vec_of<T>(items: Vec<T>) -> Box<Vector<T>> {
    let n = items.len() as u32;
    Box::new(Vector::Vector(VectorConstructor {
        field_0: n,
        field_1: items,
    }))
}

#[test]
fn hide_serializes() {
    let markup = ReplyMarkup::ReplyKeyboardHide(ReplyKeyboardHideConstructor {
        flags: 0,
        selective: None,
    });
    assert_eq!(to_json(Some(&markup)).as_deref(), Some(r#"{"k":"hide"}"#));
}

#[test]
fn keyboard_text_row_serializes() {
    let button = KeyboardButton::KeyboardButton(KeyboardButtonConstructor {
        flags: 0,
        style: None,
        text: "Yes".into(),
        type_: Box::new(ButtonType::ButtonTypeDefault(
            ButtonTypeDefaultConstructor {},
        )),
    });
    let row = KeyboardButtonRow::KeyboardButtonRow(KeyboardButtonRowConstructor {
        buttons: vec_of(vec![Box::new(button)]),
    });
    let markup = ReplyMarkup::ReplyKeyboardMarkup(ReplyKeyboardMarkupConstructor {
        flags: 1,
        resize: Some(Box::new(tellers_mtproto::latest::api::True::True(
            tellers_mtproto::latest::api::TrueConstructor {},
        ))),
        single_use: None,
        selective: None,
        persistent: None,
        force_reply: None,
        rows: vec_of(vec![Box::new(row)]),
        placeholder: None,
    });
    let json = to_json(Some(&markup)).expect("json");
    assert!(json.contains(r#""k":"keyboard""#));
    assert!(json.contains(r#""t":"text""#));
    assert!(json.contains(r#""x":"Yes""#));
    assert!(json.contains(r#""resize":true"#));
}

#[test]
fn inline_url_and_callback_serialize() {
    let url = KeyboardInlineButton::KeyboardInlineButton(KeyboardInlineButtonConstructor {
        flags: 0,
        style: None,
        text: "Open".into(),
        type_: Box::new(InlineButtonType::InlineButtonTypeUrl(
            InlineButtonTypeUrlConstructor {
                url: "https://t.me".into(),
            },
        )),
    });
    let cb = KeyboardInlineButton::KeyboardInlineButton(KeyboardInlineButtonConstructor {
        flags: 0,
        style: None,
        text: "Go".into(),
        type_: Box::new(InlineButtonType::InlineButtonTypeCallback(
            InlineButtonTypeCallbackConstructor {
                flags: 0,
                requires_password: None,
                data: b"ab".to_vec(),
            },
        )),
    });
    let row =
        KeyboardInlineButtonRow::KeyboardInlineButtonRow(KeyboardInlineButtonRowConstructor {
            buttons: vec_of(vec![Box::new(url), Box::new(cb)]),
        });
    let markup = ReplyMarkup::ReplyInlineMarkup(ReplyInlineMarkupConstructor {
        flags: 0,
        force_reply: None,
        rows: vec_of(vec![Box::new(row)]),
    });
    let json = to_json(Some(&markup)).expect("json");
    assert!(json.contains(r#""k":"inline""#));
    assert!(json.contains(r#""t":"url""#));
    assert!(json.contains("https://t.me"));
    assert!(json.contains(r#""t":"cb""#));
    assert!(json.contains("6162"));
}

#[test]
fn hex_roundtrip_callback_data() {
    assert_eq!(from_hex("6162").expect("hex"), b"ab");
    assert!(from_hex("6").is_err());
    assert!(from_hex("zz").is_err());
}
