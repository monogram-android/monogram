use crate::HashMap;

use tellers_mtproto::latest::api::{
    GeoPoint, Poll, PollAnswer, PollAnswerVoters, PollResults, TextWithEntities, TodoCompletion,
    TodoItem, TodoList,
};

use crate::peers::{peer_chat_id, vector_boxed_items};

pub(crate) fn todo_title(
    media: &tellers_mtproto::latest::api::MessageMediaToDoConstructor,
) -> Option<String> {
    let TodoList::TodoList(list) = media.todo.as_ref();
    let TextWithEntities::TextWithEntities(title) = list.title.as_ref();
    (!title.text.is_empty()).then(|| title.text.clone())
}

pub(crate) fn todo_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaToDoConstructor,
) -> String {
    let TodoList::TodoList(list) = media.todo.as_ref();
    let TextWithEntities::TextWithEntities(title) = list.title.as_ref();
    let completions: HashMap<i32, (i64, i32)> = media
        .completions
        .as_ref()
        .map(|vector| {
            vector_boxed_items(vector)
                .filter_map(|item| {
                    let TodoCompletion::TodoCompletion(body) = item;
                    Some((body.id, (peer_chat_id(&body.completed_by), body.date)))
                })
                .collect()
        })
        .unwrap_or_default();
    let items: Vec<serde_json::Value> = vector_boxed_items(&list.list)
        .filter_map(|item| {
            let TodoItem::TodoItem(body) = item;
            let TextWithEntities::TextWithEntities(title) = body.title.as_ref();
            let (by, at) = completions.get(&body.id).copied().unwrap_or((0, 0));
            Some(serde_json::json!({
                "id": body.id,
                "t": title.text,
                "d": if completions.contains_key(&body.id) { 1 } else { 0 },
                "by": by,
                "at": at,
            }))
        })
        .collect();
    serde_json::json!({
        "t": title.text,
        "a": list.others_can_append.is_some(),
        "c": list.others_can_complete.is_some(),
        "i": items,
    })
    .to_string()
}

/// Poll/quiz payload for the chat bubble.
pub(crate) fn poll_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaPollConstructor,
) -> String {
    let Poll::Poll(poll) = media.poll.as_ref();
    let TextWithEntities::TextWithEntities(question) = poll.question.as_ref();
    let PollResults::PollResults(results) = media.results.as_ref();
    let by_option: HashMap<&[u8], (bool, bool, i32)> = results
        .results
        .as_ref()
        .map(|vector| {
            vector_boxed_items(vector)
                .map(|item| {
                    let PollAnswerVoters::PollAnswerVoters(voters) = item;
                    (
                        voters.option.as_slice(),
                        (
                            voters.chosen.is_some(),
                            voters.correct.is_some(),
                            voters.voters.unwrap_or(0),
                        ),
                    )
                })
                .collect()
        })
        .unwrap_or_default();
    let answers: Vec<serde_json::Value> = vector_boxed_items(&poll.answers)
        .filter_map(|item| {
            let PollAnswer::PollAnswer(answer) = item else {
                return None;
            };
            let TextWithEntities::TextWithEntities(text) = answer.text.as_ref();
            let (chosen, correct, voters) = by_option
                .get(answer.option.as_slice())
                .copied()
                .unwrap_or((false, false, 0));
            Some(serde_json::json!({
                "t": text.text,
                "c": if chosen { 1 } else { 0 },
                "k": if correct { 1 } else { 0 },
                "v": voters,
                "o": hex_bytes(&answer.option),
            }))
        })
        .collect();
    serde_json::json!({
        "q": question.text,
        "a": answers,
        "n": results.total_voters.unwrap_or(0),
        "z": if poll.quiz.is_some() { 1 } else { 0 },
        "m": if poll.multiple_choice.is_some() { 1 } else { 0 },
        "x": if poll.closed.is_some() { 1 } else { 0 },
        "o": if poll.public_voters.is_some() { 1 } else { 0 },
        "s": results.solution.clone().unwrap_or_default(),
    })
    .to_string()
}

/// Lowercase hex, the form the Kotlin parser turns back into vote bytes.
pub(crate) fn hex_bytes(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Static geo point, or `None` for the empty variant.
pub(crate) fn geo_point_json(geo: &GeoPoint) -> Option<serde_json::Value> {
    let GeoPoint::GeoPoint(point) = geo else {
        return None;
    };
    Some(serde_json::json!({ "lat": point.lat, "long": point.long }))
}

pub(crate) fn geo_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaGeoConstructor,
) -> Option<String> {
    geo_point_json(media.geo.as_ref()).map(|value| value.to_string())
}

/// Live location payload.
pub(crate) fn geo_live_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaGeoLiveConstructor,
) -> Option<String> {
    let mut value = geo_point_json(media.geo.as_ref())?;
    value["live"] = serde_json::json!(1);
    value["p"] = serde_json::json!(media.period);
    if let Some(heading) = media.heading {
        value["h"] = serde_json::json!(heading);
    }
    Some(value.to_string())
}

/// Venue payload.
pub(crate) fn venue_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaVenueConstructor,
) -> Option<String> {
    let mut value = geo_point_json(media.geo.as_ref())?;
    value["t"] = serde_json::json!(media.title);
    value["a"] = serde_json::json!(media.address);
    value["p"] = serde_json::json!(media.provider);
    value["i"] = serde_json::json!(media.venue_id);
    value["y"] = serde_json::json!(media.venue_type);
    Some(value.to_string())
}

/// Contact payload.
pub(crate) fn contact_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaContactConstructor,
) -> String {
    serde_json::json!({
        "p": media.phone_number,
        "f": media.first_name,
        "l": media.last_name,
        "c": media.vcard,
        "u": media.user_id,
    })
    .to_string()
}

/// Dice/emoji game payload.
pub(crate) fn dice_to_json(
    media: &tellers_mtproto::latest::api::MessageMediaDiceConstructor,
) -> String {
    serde_json::json!({ "v": media.value, "e": media.emoticon }).to_string()
}
