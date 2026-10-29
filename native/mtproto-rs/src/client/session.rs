use mono_proto_session::{OsRandom, Snapshot};

use crate::MtprotoError;
use crate::api_invoke;
use crate::tcp;

use super::*;

pub(crate) use mono_proto_client::session::{apply_home_auth, fork_session, sync_upload_snapshot};

pub(crate) use mono_proto_client::session::{copy_authorization_to_dc, ensure_auth_key, ensure_auth_key_on, is_foreign_auth_key_failure, is_unrecoverable_session, kills_session, mark_session_dead, reject_if_dead, session_dead_token, session_invalidated_error};

pub(crate) use mono_proto_client::session::{ensure_ready_after_migrate, ensure_ready_after_migrate_with_binding, temporary_key_failure, temporary_key_error, temporary_key_retry_error, expire_temporary_lane, mark_lane_session_dead, call_with_migrate, drop_updates_transport, drop_all_transports, recreate_mtproto_session};
