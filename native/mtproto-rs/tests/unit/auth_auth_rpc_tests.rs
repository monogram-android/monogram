use super::{code_settings, is_srp_id_invalid, test_dc_from_phone};
use crate::MtprotoError;
use tellers_mtproto::latest::api::{CodeSettings, CodeSettingsConstructor, Vector};

#[test]
fn detects_stale_srp_id() {
    assert!(is_srp_id_invalid(&MtprotoError::Message(
        "RPC 400: SRP_ID_INVALID".into(),
    )));
    assert!(!is_srp_id_invalid(&MtprotoError::Message(
        "RPC 400: PASSWORD_HASH_INVALID".into(),
    )));
}

#[test]
fn test_phone_maps_to_test_dc() {
    assert_eq!(test_dc_from_phone("+9996621234"), Some(2));
    assert_eq!(test_dc_from_phone("9996610000"), Some(1));
    assert_eq!(test_dc_from_phone("9996639999"), Some(3));
    assert_eq!(test_dc_from_phone("9996640000"), None);
    assert_eq!(test_dc_from_phone("+79180705210"), None);
    assert_eq!(test_dc_from_phone("99966"), None);
}

#[test]
fn code_settings_allows_telegram_app_code() {
    let settings = code_settings(&[]);
    let CodeSettings::CodeSettings(settings) = *settings;
    assert_eq!(settings.flags, CodeSettingsConstructor::ALLOW_APP_HASH_FLAG);
    assert!(settings.allow_app_hash.is_some());
    assert!(settings.logout_tokens.is_none());
}

#[test]
fn code_settings_carries_bounded_logout_tokens() {
    let settings = code_settings(&[vec![1, 2, 3]]);
    let CodeSettings::CodeSettings(settings) = *settings;
    assert_eq!(
        settings.flags,
        CodeSettingsConstructor::ALLOW_APP_HASH_FLAG | CodeSettingsConstructor::LOGOUT_TOKENS_FLAG,
    );
    assert!(settings.allow_app_hash.is_some());
    let Some(tokens) = settings.logout_tokens else {
        panic!("logout tokens missing");
    };
    let Vector::Vector(tokens) = *tokens;
    assert_eq!(tokens.field_0, 1);
    assert_eq!(tokens.field_1, vec![vec![1, 2, 3]]);
}

#[test]
fn sent_code_to_dto_extracts_code_length() {
    use super::{sent_code_to_dto, sent_code_type_length};
    use tellers_mtproto::latest::api::{
        AuthSentCode, AuthSentCodeConstructor, AuthSentCodeType, AuthSentCodeTypeAppConstructor,
        AuthSentCodeTypeSmsConstructor,
    };

    let app_type =
        AuthSentCodeType::AuthSentCodeTypeApp(AuthSentCodeTypeAppConstructor { length: 6 });
    assert_eq!(sent_code_type_length(&app_type), 6);

    let sent = AuthSentCode::AuthSentCode(AuthSentCodeConstructor {
        flags: 0,
        type_: Box::new(app_type),
        phone_code_hash: "hash123".into(),
        next_type: None,
        timeout: None,
    });
    let dto = sent_code_to_dto("+123456789".into(), sent).expect("dto");
    assert_eq!(dto.phone, "+123456789");
    assert_eq!(dto.phone_code_hash, "hash123");
    assert_eq!(dto.code_type, "app");
    assert_eq!(dto.code_length, 6);

    let sms_type =
        AuthSentCodeType::AuthSentCodeTypeSms(AuthSentCodeTypeSmsConstructor { length: 5 });
    assert_eq!(sent_code_type_length(&sms_type), 5);
}
