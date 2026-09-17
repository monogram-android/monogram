//! https://core.telegram.org/method/account.updateStatus

use tellers_mtproto::latest::api::{
    AccountUpdateStatusRequest, Bool, BoolFalseConstructor, BoolTrueConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::MtprotoError;

pub fn update_status(
    snapshot: &mut Snapshot,
    api_id: i32,
    offline: bool,
) -> Result<(), MtprotoError> {
    let flag = if offline {
        Bool::BoolTrue(BoolTrueConstructor {})
    } else {
        Bool::BoolFalse(BoolFalseConstructor {})
    };
    let _: Bool = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountUpdateStatusRequest {
            offline: Box::new(flag),
        },
    )?;
    Ok(())
}
