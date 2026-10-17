use super::*;
use tellers_mtproto::latest::api::{
    JsonNumberConstructor, JsonObjectConstructor, JsonObjectValueConstructor,
    JsonStringConstructor, VectorConstructor,
};

fn object(entries: Vec<(&str, JsonValue)>) -> JsonValue {
    JsonValue::JsonObject(JsonObjectConstructor {
        value: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0x1cb5_c415,
            field_1: entries
                .into_iter()
                .map(|(key, value)| {
                    Box::new(JsonObjectValue::JsonObjectValue(
                        JsonObjectValueConstructor {
                            key: key.to_string(),
                            value: Box::new(value),
                        },
                    ))
                })
                .collect(),
        })),
    })
}

fn number(value: f64) -> JsonValue {
    JsonValue::JsonNumber(JsonNumberConstructor { value })
}

fn text(value: &str) -> JsonValue {
    JsonValue::JsonString(JsonStringConstructor {
        value: value.to_string(),
    })
}

#[test]
fn reads_thresholds_from_config_object() {
    let mut out = default_config(false);
    collect_read_receipt_config(
        &object(vec![
            ("chat_read_mark_size_threshold", number(50.0)),
            ("chat_read_mark_expire_period", number(3600.0)),
            ("pm_read_date_expire_period", text("ignored")),
        ]),
        &mut out,
    );
    assert_eq!(out.chat_read_mark_size_threshold, 50);
    assert_eq!(out.chat_read_mark_expire_period, 3600);
    assert_eq!(
        out.pm_read_date_expire_period,
        DEFAULT_PM_READ_DATE_EXPIRE_PERIOD
    );
    assert!(out.from_server);
}

#[test]
fn ignores_non_object_config() {
    let mut out = default_config(false);
    collect_read_receipt_config(&number(1.0), &mut out);
    assert!(!out.from_server);
    assert_eq!(
        out.chat_read_mark_size_threshold,
        DEFAULT_CHAT_READ_MARK_SIZE_THRESHOLD
    );
}

#[test]
fn known_errors_are_reported_not_raised() {
    assert_eq!(
        known_error(&MtprotoError::Message("RPC 400: MSG_TOO_OLD".into())).as_deref(),
        Some("MSG_TOO_OLD")
    );
    assert_eq!(
        known_error(&MtprotoError::Message(
            "RPC 403: USER_PRIVACY_RESTRICTED".into()
        ))
        .as_deref(),
        Some("PRIVACY_RESTRICTED")
    );
    assert!(known_error(&MtprotoError::Message("connection reset".into())).is_none());
}
