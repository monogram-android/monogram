//! Shared InvokeWithLayer(InitConnection(...)) helpers for API RPCs.

use std::cell::Cell;

use tellers_mtproto::LATEST_API_LAYER;
use tellers_mtproto::codec::{Boxed, BoxedDecode, TlEncode};
use tellers_mtproto::latest::api::{
    InitConnectionRequest, InvokeWithLayerRequest, InvokeWithoutUpdatesRequest,
};
use tellers_mtproto_session::Snapshot;

use crate::MtprotoError;
use crate::rpc::{self, BoxedQuery};

thread_local! {
    static WITHOUT_UPDATES: Cell<bool> = const { Cell::new(false) };
}

/// Extra home-DC RPC sessions must not subscribe for updates.
/// https://core.telegram.org/api/invoking#disabling-updates
pub fn invoking_without_updates() -> bool {
    WITHOUT_UPDATES.with(Cell::get)
}

pub fn with_invoke_without_updates<T>(body: impl FnOnce() -> T) -> T {
    WITHOUT_UPDATES.with(|cell| {
        struct Restore<'a>(&'a Cell<bool>, bool);
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                self.0.set(self.1);
            }
        }
        let previous = cell.replace(true);
        let _restore = Restore(cell, previous);
        body()
    })
}

pub fn wrap_init_connection<X: TlEncode>(
    api_id: i32,
    query: X,
) -> InvokeWithLayerRequest<BoxedQuery<InitConnectionRequest<BoxedQuery<X>>>> {
    wrap_init_connection_at_layer(api_id, LATEST_API_LAYER as i32, query)
}

pub fn wrap_init_connection_at_layer<X: TlEncode>(
    api_id: i32,
    layer: i32,
    query: X,
) -> InvokeWithLayerRequest<BoxedQuery<InitConnectionRequest<BoxedQuery<X>>>> {
    InvokeWithLayerRequest {
        layer,
        query: BoxedQuery(InitConnectionRequest {
            flags: 0,
            api_id,
            device_model: "Android".into(),
            system_version: "14".into(),
            app_version: env!("CARGO_PKG_VERSION").into(),
            system_lang_code: "en".into(),
            lang_pack: "android".into(),
            lang_code: "en".into(),
            proxy: None,
            params: None,
            query: BoxedQuery(query),
        }),
    }
}

/// File queries must not subscribe the connection for updates.
/// https://core.telegram.org/api/invoking#disabling-updates
/// https://core.telegram.org/api/files (separate sessions; default for file queries)
pub fn wrap_init_connection_without_updates<X: TlEncode + Boxed>(
    api_id: i32,
    query: X,
) -> InvokeWithLayerRequest<
    BoxedQuery<InitConnectionRequest<BoxedQuery<InvokeWithoutUpdatesRequest<BoxedQuery<X>>>>>,
> {
    wrap_init_connection(
        api_id,
        InvokeWithoutUpdatesRequest {
            query: BoxedQuery(query),
        },
    )
}

pub fn wrap_without_updates<X: TlEncode + Boxed>(
    query: X,
) -> InvokeWithoutUpdatesRequest<BoxedQuery<X>> {
    InvokeWithoutUpdatesRequest {
        query: BoxedQuery(query),
    }
}

pub fn migrate_dc(err: &MtprotoError) -> Option<i32> {
    let MtprotoError::Message(msg) = err else {
        return None;
    };
    if let Some(idx) = msg.find("MIGRATE_") {
        let rest = &msg[idx + "MIGRATE_".len()..];
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(dc) = digits.parse::<i32>() {
            return Some(dc);
        }
    }
    None
}

pub fn invoke_api<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    request: Req,
) -> Result<Res, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    if invoking_without_updates() {
        invoke_api_without_updates(snapshot, api_id, request)
    } else {
        // The main connection receives both correlated RPC results and updates.
        invoke_api_allow_updates(snapshot, api_id, request)
    }
}

/// `updates.getDifference` / `updates.getState` / channel difference: this
/// lane is the updates subscriber. Do not wrap `invokeWithoutUpdates`.
/// https://core.telegram.org/api/invoking#disabling-updates
pub fn invoke_api_allow_updates<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    request: Req,
) -> Result<Res, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    let span = crate::perf::span_dyn(|| perf_op(Req::CONSTRUCTOR_ID));
    span.with(|| {
        invoke_encoded(
            snapshot,
            replay_safe_method(Req::CONSTRUCTOR_ID),
            |new_connection| {
                if new_connection {
                    rpc::encode_boxed_bytes(&wrap_init_connection(api_id, request.clone()))
                } else {
                    rpc::encode_boxed_bytes(&request)
                }
            },
        )
    })
}

/// `upload.getFile` / other file RPCs: wrap `invokeWithoutUpdates` so the
/// file TCP does not receive `updates#74ae4240` while waiting for `rpc_result`.
pub fn invoke_api_without_updates<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    request: Req,
) -> Result<Res, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    let span = crate::perf::span_dyn(|| perf_op(Req::CONSTRUCTOR_ID));
    span.with(|| {
        invoke_encoded(
            snapshot,
            replay_safe_method(Req::CONSTRUCTOR_ID),
            |new_connection| {
                if new_connection {
                    rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
                        api_id,
                        request.clone(),
                    ))
                } else {
                    rpc::encode_boxed_bytes(&wrap_without_updates(request.clone()))
                }
            },
        )
    })
}

/// File RPCs sent as one batch on one session, each wrapped with `invokeWithoutUpdates`;
/// the first query on a fresh connection carries InitConnection.
pub fn invoke_api_batch_without_updates<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    requests: Vec<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    invoke_api_batch_without_updates_streaming(snapshot, api_id, requests, &mut |_, _| None)
}

pub fn invoke_api_batch_without_updates_streaming<Req, Res>(
    snapshot: &mut Snapshot,
    api_id: i32,
    requests: Vec<Req>,
    on_chunk: &mut dyn FnMut(usize, Result<Res, MtprotoError>) -> Option<Req>,
) -> Result<Vec<Result<Res, MtprotoError>>, MtprotoError>
where
    Req: TlEncode + Boxed + Clone,
    Res: BoxedDecode,
{
    if requests.is_empty() {
        return Ok(Vec::new());
    }
    let replay_safe = replay_safe_method(Req::CONSTRUCTOR_ID);
    let span = crate::perf::span("rpc:pipeline");
    let raw = rpc::invoke_batch_raw_with_retry_streaming(
        snapshot,
        replay_safe,
        |reused| {
            let mut bodies = Vec::with_capacity(requests.len());
            for (index, request) in requests.iter().enumerate() {
                let encoded = if !reused && index == 0 {
                    rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
                        api_id,
                        request.clone(),
                    ))?
                } else {
                    rpc::encode_boxed_bytes(&wrap_without_updates(request.clone()))?
                };
                bodies.push(encoded);
            }
            Ok(bodies)
        },
        &mut |index, raw_res| {
            let res = match raw_res {
                Ok(bytes) => decode_response::<Res>(bytes),
                Err(err) => Err((*err).clone()),
            };
            let refill = on_chunk(index, res)?;
            rpc::encode_boxed_bytes(&wrap_without_updates(refill)).ok()
        },
    );
    drop(span);
    Ok(raw?
        .into_iter()
        .map(|entry| entry.and_then(|body| decode_response::<Res>(&body)))
        .collect())
}

/// Short method label for perf spans. Unknown ids stay as hex so no method is
/// silently mislabelled.
fn perf_op(id: u32) -> String {
    use tellers_mtproto::latest::api::*;
    let name = match id {
        HelpGetConfigRequest::ID => "getConfig",
        MessagesGetDialogsRequest::ID => "getDialogs",
        MessagesGetPinnedDialogsRequest::ID => "getPinnedDialogs",
        MessagesGetWebPagePreviewRequest::ID => "getWebPagePreview",
        MessagesGetDialogFiltersRequest::ID => "getDialogFilters",
        MessagesGetHistoryRequest::ID => "getHistory",
        MessagesGetMessagesRequest::ID => "getMessages",
        MessagesGetRepliesRequest::ID => "getReplies",
        MessagesGetForumTopicsRequest::ID => "getForumTopics",
        UploadGetFileRequest::ID => "getFile",
        UsersGetFullUserRequest::ID => "getFullUser",
        ChannelsGetFullChannelRequest::ID => "getFullChannel",
        UpdatesGetDifferenceRequest::ID => "getDifference",
        UpdatesGetChannelDifferenceRequest::ID => "getChannelDifference",
        UpdatesGetStateRequest::ID => "getState",
        MessagesSendMessageRequest::ID => "sendMessage",
        MessagesSendMediaRequest::ID => "sendMedia",
        _ => return format!("rpc:{id:#010x}"),
    };
    format!("rpc:{name}")
}

/// Only read methods can be replayed after an uncertain transport failure.
/// Mutations need application-level correlation; unknown methods default to no replay.
fn replay_safe_method(id: u32) -> bool {
    use tellers_mtproto::latest::api::*;
    matches!(
        id,
        HelpGetConfigRequest::ID
            | HelpGetAppConfigRequest::ID
            | AccountGetPasswordRequest::ID
            | MessagesGetDialogsRequest::ID
            | MessagesGetPinnedDialogsRequest::ID
            | MessagesGetWebPagePreviewRequest::ID
            | MessagesGetDialogFiltersRequest::ID
            | MessagesGetHistoryRequest::ID
            | MessagesGetMessagesRequest::ID
            | MessagesGetRepliesRequest::ID
            | MessagesGetForumTopicsRequest::ID
            | MessagesGetForumTopicsByIdRequest::ID
            | ChannelsGetMessagesRequest::ID
            | UpdatesGetDifferenceRequest::ID
            | UpdatesGetChannelDifferenceRequest::ID
            | UpdatesGetStateRequest::ID
            | UploadGetFileRequest::ID
            | UploadSaveFilePartRequest::ID
            | UploadSaveBigFilePartRequest::ID
            | UsersGetFullUserRequest::ID
            | ChannelsGetFullChannelRequest::ID
            | ChannelsGetParticipantsRequest::ID
            | MessagesGetFullChatRequest::ID
            | MessagesGetDiscussionMessageRequest::ID
            | MessagesGetRecentReactionsRequest::ID
            | MessagesGetTopReactionsRequest::ID
            | MessagesGetSavedGifsRequest::ID
            | MessagesGetCustomEmojiDocumentsRequest::ID
            | MessagesGetStickerSetRequest::ID
            | MessagesSearchRequest::ID
            | MessagesGetWebPageRequest::ID
            | UploadGetWebFileRequest::ID
    )
}

fn invoke_encoded<Res>(
    snapshot: &mut Snapshot,
    replay_safe: bool,
    encode: impl FnMut(bool) -> Result<Vec<u8>, MtprotoError>,
) -> Result<Res, MtprotoError>
where
    Res: BoxedDecode,
{
    match rpc::invoke_raw_with_retry_factory(snapshot, replay_safe, encode) {
        Ok(body) => decode_response(&body),
        Err(err) => {
            if let Some(dc) = migrate_dc(&err) {
                rpc::drop_live_transport();
                let MtprotoError::Message(message) = &err else {
                    return Err(err);
                };
                // Keep FILE_MIGRATE distinguishable: media moves only its
                // file lane, while USER/NETWORK_MIGRATE may transfer the
                // account authorization to a new home DC.
                let prefix = if message.contains("FILE_MIGRATE") {
                    "FILE_MIGRATE"
                } else {
                    "MIGRATE"
                };
                return Err(MtprotoError::Message(format!("{prefix}_{dc}")));
            }
            Err(err)
        }
    }
}

fn decode_response<Res: BoxedDecode>(body: &[u8]) -> Result<Res, MtprotoError> {
    let mut decoder =
        tellers_mtproto::codec::Decoder::new(body, tellers_mtproto::codec::Limits::default())
            .map_err(|e| MtprotoError::Message(e.to_string()))?;
    let response =
        Res::decode_boxed(&mut decoder).map_err(|e| MtprotoError::Message(e.to_string()))?;
    decoder
        .finish()
        .map_err(|e| MtprotoError::Message(e.to_string()))?;
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tellers_mtproto::latest::api::{Bool, BoolTrueConstructor, HelpGetConfigRequest};

    #[test]
    fn reconnect_replays_reads_but_not_mutations_or_unknown_methods() {
        use tellers_mtproto::latest::api::*;
        assert!(replay_safe_method(MessagesGetHistoryRequest::ID));
        assert!(replay_safe_method(UploadGetFileRequest::ID));
        assert!(replay_safe_method(UploadSaveFilePartRequest::ID));
        assert!(replay_safe_method(UploadSaveBigFilePartRequest::ID));
        assert!(!replay_safe_method(AuthSendCodeRequest::ID));
        assert!(!replay_safe_method(MessagesSendMessageRequest::ID));
        assert!(!replay_safe_method(MessagesSendMediaRequest::ID));
        assert!(!replay_safe_method(MessagesForwardMessagesRequest::ID));
        assert!(!replay_safe_method(0));
    }

    fn contains_ctor(bytes: &[u8], ctor: u32) -> bool {
        let marker = ctor.to_le_bytes();
        bytes.windows(4).any(|w| w == marker)
    }

    #[test]
    fn file_wrap_includes_invoke_without_updates() {
        let query = rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
            1,
            HelpGetConfigRequest {},
        ))
        .expect("query wrap");
        let updates = rpc::encode_boxed_bytes(&wrap_init_connection(1, HelpGetConfigRequest {}))
            .expect("updates wrap");
        let file = rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
            1,
            HelpGetConfigRequest {},
        ))
        .expect("file wrap");
        assert!(
            contains_ctor(&query, InvokeWithoutUpdatesRequest::<()>::ID),
            "query RPCs must wrap invokeWithoutUpdates so updates do not starve rpc_result",
        );
        assert!(
            !contains_ctor(&updates, InvokeWithoutUpdatesRequest::<()>::ID),
            "getDifference/getState must subscribe the updates lane",
        );
        assert!(
            contains_ctor(&file, InvokeWithoutUpdatesRequest::<()>::ID),
            "file RPCs must wrap invokeWithoutUpdates (0xbf9459b7)",
        );
        assert!(contains_ctor(&file, InitConnectionRequest::<()>::ID));
        assert!(contains_ctor(&file, InvokeWithLayerRequest::<()>::ID));
        assert_ne!(query, updates);
    }

    #[test]
    fn established_query_uses_only_invoke_without_updates() {
        let first = rpc::encode_boxed_bytes(&wrap_init_connection_without_updates(
            1,
            HelpGetConfigRequest {},
        ))
        .expect("first query wrap");
        let established = rpc::encode_boxed_bytes(&wrap_without_updates(HelpGetConfigRequest {}))
            .expect("established query wrap");
        assert!(contains_ctor(&first, InitConnectionRequest::<()>::ID));
        assert!(contains_ctor(&first, InvokeWithLayerRequest::<()>::ID));
        assert!(contains_ctor(
            &established,
            InvokeWithoutUpdatesRequest::<()>::ID
        ));
        assert!(!contains_ctor(
            &established,
            InitConnectionRequest::<()>::ID
        ));
        assert!(!contains_ctor(
            &established,
            InvokeWithLayerRequest::<()>::ID
        ));
    }

    #[test]
    fn established_updates_lane_uses_the_method_without_wrappers() {
        let first = rpc::encode_boxed_bytes(&wrap_init_connection(1, HelpGetConfigRequest {}))
            .expect("first update-lane wrap");
        let established = rpc::encode_boxed_bytes(&HelpGetConfigRequest {})
            .expect("established update-lane request");
        assert!(contains_ctor(&first, InitConnectionRequest::<()>::ID));
        assert!(contains_ctor(&first, InvokeWithLayerRequest::<()>::ID));
        assert!(!contains_ctor(
            &established,
            InitConnectionRequest::<()>::ID
        ));
        assert!(!contains_ctor(
            &established,
            InvokeWithLayerRequest::<()>::ID
        ));
        assert!(!contains_ctor(
            &established,
            InvokeWithoutUpdatesRequest::<()>::ID
        ));
    }

    #[test]
    fn file_migrate_still_media_dc_only() {
        assert_eq!(
            migrate_dc(&MtprotoError::Message("FILE_MIGRATE_4".into())),
            Some(4)
        );
        assert_eq!(
            migrate_dc(&MtprotoError::Message("RPC 303: FILE_MIGRATE_2".into())),
            Some(2)
        );
        assert_eq!(
            migrate_dc(&MtprotoError::Message("USER_MIGRATE_5".into())),
            Some(5)
        );
        assert_eq!(
            migrate_dc(&MtprotoError::Message("RPC timeout recv=0".into())),
            None
        );
    }

    #[test]
    fn extra_read_flag_selects_without_updates_wrap() {
        assert!(!invoking_without_updates());
        with_invoke_without_updates(|| {
            assert!(invoking_without_updates());
            let established =
                rpc::encode_boxed_bytes(&wrap_without_updates(HelpGetConfigRequest {}))
                    .expect("extra-lane wrap");
            assert!(contains_ctor(
                &established,
                InvokeWithoutUpdatesRequest::<()>::ID,
            ));
            assert!(!contains_ctor(
                &established,
                InitConnectionRequest::<()>::ID
            ));
        });
        assert!(!invoking_without_updates());
    }

    #[test]
    fn response_decode_rejects_trailing_tl_bytes() {
        let mut encoded = rpc::encode_boxed_bytes(&BoolTrueConstructor {}).expect("encode bool");
        encoded.extend_from_slice(&0_u32.to_le_bytes());

        assert!(decode_response::<Bool>(&encoded).is_err());
    }
}
