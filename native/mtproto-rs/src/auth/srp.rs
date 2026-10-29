use crate::MtprotoError;
use mono_proto::latest::api::{
    InputCheckPasswordSrp,
    PasswordKdfAlgoSha256sha256pbkdf2hmacsha512iter100000Sha256ModPowConstructor,
};

pub fn input_check_password(
    password: &str,
    algo: &PasswordKdfAlgoSha256sha256pbkdf2hmacsha512iter100000Sha256ModPowConstructor,
    srp_b: &[u8],
    srp_id: i64,
) -> Result<InputCheckPasswordSrp, MtprotoError> {
    mono_proto_runtime::srp::input_check_password(password, algo, srp_b, srp_id)
        .map_err(MtprotoError::Message)
}
