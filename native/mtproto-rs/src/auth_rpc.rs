//! Auth API methods (`auth.sendCode` / `signIn` / `checkPassword`).
//! https://core.telegram.org/api/auth
//! https://core.telegram.org/method/auth.sendCode
//! https://core.telegram.org/api/srp

use tellers_mtproto::latest::api::{
    AccountGetPasswordRequest, AccountPassword, AuthAuthorization, AuthCheckPasswordRequest,
    AuthLogOutRequest, AuthLoggedOut, AuthResendCodeRequest, AuthSendCodeRequest, AuthSentCode,
    AuthSentCodeType, AuthSignInRequest, CodeSettings, CodeSettingsConstructor,
    InputCheckPasswordSrp, PasswordKdfAlgo, True, TrueConstructor, User, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::srp;
use crate::{AuthCodeSent, AuthSignedIn, MtprotoError};

fn code_settings(logout_tokens: &[Vec<u8>]) -> Box<CodeSettings> {
    let logout_tokens = (!logout_tokens.is_empty()).then(|| {
        Box::new(Vector::Vector(VectorConstructor {
            field_0: logout_tokens.len() as u32,
            field_1: logout_tokens.to_vec(),
        }))
    });
    let mut flags = CodeSettingsConstructor::ALLOW_APP_HASH_FLAG;
    if logout_tokens.is_some() {
        flags |= CodeSettingsConstructor::LOGOUT_TOKENS_FLAG;
    }
    Box::new(CodeSettings::CodeSettings(CodeSettingsConstructor {
        flags,
        allow_flashcall: None,
        current_number: None,
        allow_app_hash: Some(Box::new(True::True(TrueConstructor {}))),
        allow_missed_call: None,
        allow_firebase: None,
        unknown_number: None,
        logout_tokens,
        token: None,
        app_sandbox: None,
    }))
}

fn user_id(user: &User) -> Result<i64, MtprotoError> {
    match user {
        User::User(u) => Ok(u.id),
        User::UserEmpty(u) => Ok(u.id),
        _ => Err(MtprotoError::Message("unexpected user constructor".into())),
    }
}

fn authorization_to_signed(
    auth: AuthAuthorization,
    dc_id: i32,
) -> Result<AuthSignedIn, MtprotoError> {
    match auth {
        AuthAuthorization::AuthAuthorization(a) => Ok(AuthSignedIn {
            user_id: user_id(&a.user)?,
            dc_id,
        }),
        AuthAuthorization::AuthAuthorizationSignUpRequired(_) => {
            Err(MtprotoError::RegistrationRequired)
        }
        _ => Err(MtprotoError::Message("unexpected authorization".into())),
    }
}

/// Manual test accounts: `99966XYYYY` on test DCs (X = 1..3).
/// https://core.telegram.org/api/obtaining_api_id#test-accounts
pub fn test_dc_from_phone(phone: &str) -> Option<i32> {
    let digits: String = phone.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() != 10 || !digits.starts_with("99966") {
        return None;
    }
    let dc = (digits.as_bytes()[5] - b'0') as i32;
    (1..=3).contains(&dc).then_some(dc)
}

pub fn send_code(
    snapshot: &mut Snapshot,
    api_id: i32,
    api_hash: &str,
    phone: &str,
    logout_tokens: &[Vec<u8>],
) -> Result<AuthCodeSent, MtprotoError> {
    let phone = phone.to_string();
    let sent: AuthSentCode = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthSendCodeRequest {
            phone_number: phone.clone(),
            api_id,
            api_hash: api_hash.into(),
            settings: code_settings(logout_tokens),
        },
    )?;
    sent_code_to_dto(phone, sent)
}

/// https://core.telegram.org/method/auth.resendCode
pub fn resend_code(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    phone_code_hash: &str,
) -> Result<AuthCodeSent, MtprotoError> {
    let phone = phone.to_string();
    let sent: AuthSentCode = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthResendCodeRequest {
            flags: 0,
            phone_number: phone.clone(),
            phone_code_hash: phone_code_hash.into(),
            reason: None,
        },
    )?;
    sent_code_to_dto(phone, sent)
}

fn sent_code_to_dto(phone: String, sent: AuthSentCode) -> Result<AuthCodeSent, MtprotoError> {
    let (hash, code_type) = match sent {
        AuthSentCode::AuthSentCode(s) => (s.phone_code_hash, sent_code_type_name(s.type_.as_ref())),
        AuthSentCode::AuthSentCodeSuccess(_) => {
            return Err(MtprotoError::Message(
                "auth.sentCodeSuccess not supported in thin slice".into(),
            ));
        }
        _ => return Err(MtprotoError::Message("unexpected auth.sentCode".into())),
    };
    Ok(AuthCodeSent {
        phone,
        phone_code_hash: hash,
        code_type: code_type.into(),
    })
}

fn sent_code_type_name(sent: &AuthSentCodeType) -> &'static str {
    match sent {
        AuthSentCodeType::AuthSentCodeTypeApp(_) => "app",
        AuthSentCodeType::AuthSentCodeTypeSms(_) => "sms",
        AuthSentCodeType::AuthSentCodeTypeCall(_) => "call",
        AuthSentCodeType::AuthSentCodeTypeFlashCall(_) => "flash_call",
        AuthSentCodeType::AuthSentCodeTypeMissedCall(_) => "missed_call",
        AuthSentCodeType::AuthSentCodeTypeEmailCode(_) => "email",
        AuthSentCodeType::AuthSentCodeTypeSetUpEmailRequired(_) => "email_setup",
        AuthSentCodeType::AuthSentCodeTypeFragmentSms(_) => "fragment",
        AuthSentCodeType::AuthSentCodeTypeFirebaseSms(_) => "firebase",
        AuthSentCodeType::AuthSentCodeTypeSmsWord(_) => "sms_word",
        AuthSentCodeType::AuthSentCodeTypeSmsPhrase(_) => "sms_phrase",
    }
}

pub fn sign_in(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    phone_code_hash: &str,
    phone_code: &str,
) -> Result<AuthSignedIn, MtprotoError> {
    let auth: AuthAuthorization = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthSignInRequest {
            flags: 1,
            phone_number: phone.into(),
            phone_code_hash: phone_code_hash.into(),
            phone_code: Some(phone_code.into()),
            email_verification: None,
        },
    )?;
    authorization_to_signed(auth, snapshot.dc_id)
}

pub(crate) fn is_srp_id_invalid(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if message.contains("SRP_ID_INVALID"))
}

/// `account.getPassword` then `auth.checkPassword`. Retry once on `SRP_ID_INVALID`
/// (srp_id is short-lived; https://core.telegram.org/api/srp).
pub fn check_password(
    snapshot: &mut Snapshot,
    api_id: i32,
    password: &str,
) -> Result<AuthSignedIn, MtprotoError> {
    match check_password_once(snapshot, api_id, password) {
        Ok(signed) => Ok(signed),
        Err(err) if is_srp_id_invalid(&err) => check_password_once(snapshot, api_id, password),
        Err(err) => Err(err),
    }
}

/// Revoke the current authorization on Telegram. Local teardown is owned by
/// the client manager even when this RPC fails because the key was already
/// revoked or the connection is unavailable.
pub fn log_out(snapshot: &mut Snapshot, api_id: i32) -> Result<Option<Vec<u8>>, MtprotoError> {
    let logged: AuthLoggedOut = api_invoke::invoke_api(snapshot, api_id, AuthLogOutRequest {})?;
    let AuthLoggedOut::AuthLoggedOut(logged) = logged;
    Ok(logged.future_auth_token)
}

fn check_password_once(
    snapshot: &mut Snapshot,
    api_id: i32,
    password: &str,
) -> Result<AuthSignedIn, MtprotoError> {
    let pwd: AccountPassword =
        api_invoke::invoke_api(snapshot, api_id, AccountGetPasswordRequest {})?;
    let AccountPassword::AccountPassword(pwd) = pwd else {
        return Err(MtprotoError::Message("unexpected account.password".into()));
    };
    let Some(algo) = pwd.current_algo else {
        return Err(MtprotoError::Message("no current password algo".into()));
    };
    let PasswordKdfAlgo::PasswordKdfAlgoSha256sha256pbkdf2hmacsha512iter100000Sha256ModPow(algo) =
        *algo
    else {
        return Err(MtprotoError::Message(
            "unsupported password KDF (client outdated?)".into(),
        ));
    };
    let srp_b = pwd
        .srp_b
        .ok_or_else(|| MtprotoError::Message("missing srp_B".into()))?;
    let srp_id = pwd
        .srp_id
        .ok_or_else(|| MtprotoError::Message("missing srp_id".into()))?;
    let srp_input: InputCheckPasswordSrp =
        srp::input_check_password(password, &algo, &srp_b, srp_id)?;
    let auth: AuthAuthorization = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthCheckPasswordRequest {
            password: Box::new(srp_input),
        },
    )?;
    authorization_to_signed(auth, snapshot.dc_id)
}

#[cfg(test)]
mod tests {
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
            CodeSettingsConstructor::ALLOW_APP_HASH_FLAG
                | CodeSettingsConstructor::LOGOUT_TOKENS_FLAG,
        );
        assert!(settings.allow_app_hash.is_some());
        let Some(tokens) = settings.logout_tokens else {
            panic!("logout tokens missing");
        };
        let Vector::Vector(tokens) = *tokens;
        assert_eq!(tokens.field_0, 1);
        assert_eq!(tokens.field_1, vec![vec![1, 2, 3]]);
    }
}
