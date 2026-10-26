use crate::media::MediaIndex;
use crate::peers::{CachedPeer, input_peer_from_cached, require_usable_peer};
use crate::{HashMap, MtprotoError, OutgoingMessageResult, SendOptions};
use tellers_mtproto::latest::api::*;
use tellers_mtproto_session::Snapshot;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct LocationSendSpec {
    pub latitude: f64,
    pub longitude: f64,
    pub live_period: i32,
    pub heading: Option<i32>,
}

pub(crate) fn location_send_request(
    peers: &HashMap<i64, CachedPeer>,
    chat_id: i64,
    spec: &LocationSendSpec,
    reply: i32,
    top: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<MessagesSendMediaRequest, MtprotoError> {
    options.validate(random_id)?;
    if random_id == 0
        || reply < 0
        || top < 0
        || !spec.latitude.is_finite()
        || !spec.longitude.is_finite()
        || !(-90.0..=90.0).contains(&spec.latitude)
        || !(-180.0..=180.0).contains(&spec.longitude)
        || spec.live_period < 0
        || (spec.live_period > 0
            && spec.live_period != i32::MAX
            && !(60..=86400).contains(&spec.live_period))
        || spec.heading.is_some_and(|h| !(1..=360).contains(&h))
        || (spec.live_period == 0 && spec.heading.is_some())
        || (spec.live_period > 0 && options.schedule_date.is_some())
    {
        return Err(MtprotoError::Message(
            "location send configuration invalid".into(),
        ));
    }
    let point = Box::new(InputGeoPoint::InputGeoPoint(InputGeoPointConstructor {
        flags: 0,
        lat: spec.latitude,
        long: spec.longitude,
        accuracy_radius: None,
    }));
    let media = if spec.live_period == 0 {
        InputMedia::InputMediaGeoPoint(InputMediaGeoPointConstructor { geo_point: point })
    } else {
        InputMedia::InputMediaGeoLive(InputMediaGeoLiveConstructor {
            flags: InputMediaGeoLiveConstructor::PERIOD_FLAG
                | if spec.heading.is_some() {
                    InputMediaGeoLiveConstructor::HEADING_FLAG
                } else {
                    0
                },
            stopped: None,
            geo_point: point,
            heading: spec.heading,
            period: Some(spec.live_period),
            proximity_notification_radius: None,
        })
    };
    let (flags, reply_to) = super::input_reply_to_thread(reply, top);
    let mut request = MessagesSendMediaRequest {
        flags,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(require_usable_peer(peers, chat_id)?)),
        reply_to,
        media: Box::new(media),
        message: String::new(),
        random_id,
        reply_markup: None,
        entities: None,
        schedule_date: None,
        schedule_repeat_period: None,
        send_as: None,
        quick_reply_shortcut: None,
        effect: None,
        allow_paid_stars: None,
        suggested_post: None,
    };
    options.apply_media(&mut request);
    Ok(request)
}

pub fn send_location_with_options(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    spec: &LocationSendSpec,
    reply: i32,
    top: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<OutgoingMessageResult, MtprotoError> {
    let request = location_send_request(peers, chat_id, spec, reply, top, random_id, options)?;
    let updates: Updates = crate::api_invoke::invoke_api(snapshot, api_id, request)?;
    let mut staged = media.clone();
    let result = super::send_result(updates, chat_id, "", random_id, peers, &mut staged)?;
    let message = match &result {
        OutgoingMessageResult::Sent { message } => message,
        OutgoingMessageResult::Scheduled { message } => &message.message,
    };
    if message.media_kind.as_deref() != Some("geo") {
        return Err(MtprotoError::Message(
            "location response missing authoritative media".into(),
        ));
    }
    *media = staged;
    Ok(result)
}
