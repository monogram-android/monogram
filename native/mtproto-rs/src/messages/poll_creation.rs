use crate::media::MediaIndex;
use crate::peers::{CachedPeer, input_peer_from_cached, require_usable_peer};
use crate::{HashMap, MtprotoError, OutgoingMessageResult, SendOptions};
use tellers_mtproto::latest::api::*;
use tellers_mtproto_session::Snapshot;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct PollSendSpec {
    pub question: String,
    pub answers: Vec<String>,
    pub anonymous: bool,
    pub multiple_choice: bool,
    pub quiz: bool,
    pub correct_answer: Option<u32>,
    pub solution: Option<String>,
}

fn text(value: &str) -> Box<TextWithEntities> {
    Box::new(TextWithEntities::TextWithEntities(
        TextWithEntitiesConstructor {
            text: value.to_owned(),
            entities: Box::new(Vector::Vector(VectorConstructor {
                field_0: 0,
                field_1: vec![],
            })),
        },
    ))
}

fn poll_media(spec: &PollSendSpec) -> Result<InputMedia, MtprotoError> {
    if spec.question.trim().is_empty()
        || spec.question.encode_utf16().count() > 255
        || !(2..=10).contains(&spec.answers.len())
        || spec
            .answers
            .iter()
            .any(|v| v.trim().is_empty() || v.encode_utf16().count() > 100)
        || spec.quiz && (spec.multiple_choice || spec.correct_answer.is_none())
        || !spec.quiz && (spec.correct_answer.is_some() || spec.solution.is_some())
        || spec
            .correct_answer
            .is_some_and(|v| v as usize >= spec.answers.len())
        || spec
            .solution
            .as_ref()
            .is_some_and(|v| v.trim().is_empty() || v.encode_utf16().count() > 200)
    {
        return Err(MtprotoError::Message("poll configuration invalid".into()));
    }
    let yes = || Some(Box::new(True::True(TrueConstructor {})));
    let poll = PollConstructor {
        id: 0,
        flags: if spec.anonymous {
            0
        } else {
            PollConstructor::PUBLIC_VOTERS_FLAG
        } | if spec.multiple_choice {
            PollConstructor::MULTIPLE_CHOICE_FLAG
        } else {
            0
        } | if spec.quiz {
            PollConstructor::QUIZ_FLAG
        } else {
            0
        },
        closed: None,
        public_voters: if spec.anonymous { None } else { yes() },
        multiple_choice: if spec.multiple_choice { yes() } else { None },
        quiz: if spec.quiz { yes() } else { None },
        open_answers: None,
        revoting_disabled: None,
        shuffle_answers: None,
        hide_results_until_close: None,
        creator: None,
        subscribers_only: None,
        question: text(&spec.question),
        answers: Box::new(Vector::Vector(VectorConstructor {
            field_0: spec.answers.len() as u32,
            field_1: spec
                .answers
                .iter()
                .enumerate()
                .map(|(i, v)| {
                    Box::new(PollAnswer::PollAnswer(PollAnswerConstructor {
                        flags: 0,
                        text: text(v),
                        option: vec![b'0' + i as u8],
                        media: None,
                        added_by: None,
                        date: None,
                    }))
                })
                .collect(),
        })),
        close_period: None,
        close_date: None,
        countries_iso2: None,
        hash: 0,
    };
    Ok(InputMedia::InputMediaPoll(InputMediaPollConstructor {
        flags: if spec.correct_answer.is_some() {
            InputMediaPollConstructor::CORRECT_ANSWERS_FLAG
        } else {
            0
        } | if spec.solution.is_some() {
            InputMediaPollConstructor::SOLUTION_FLAG
        } else {
            0
        },
        poll: Box::new(Poll::Poll(poll)),
        correct_answers: spec.correct_answer.map(|i| {
            Box::new(Vector::Vector(VectorConstructor {
                field_0: 1,
                field_1: vec![i as i32],
            }))
        }),
        attached_media: None,
        solution: spec.solution.clone(),
        solution_entities: spec.solution.as_ref().map(|_| {
            Box::new(Vector::Vector(VectorConstructor {
                field_0: 0,
                field_1: vec![],
            }))
        }),
        solution_media: None,
    }))
}

pub fn send_poll_with_options(
    snapshot: &mut Snapshot,
    api_id: i32,
    peers: &HashMap<i64, CachedPeer>,
    media: &mut MediaIndex,
    chat_id: i64,
    spec: &PollSendSpec,
    reply_to_msg_id: i32,
    top_msg_id: i32,
    random_id: i64,
    options: &SendOptions,
) -> Result<OutgoingMessageResult, MtprotoError> {
    options.validate(random_id)?;
    if random_id == 0 || reply_to_msg_id < 0 || top_msg_id < 0 {
        return Err(MtprotoError::Message("poll send identity invalid".into()));
    }
    let poll = poll_media(spec)?;
    let peer = require_usable_peer(peers, chat_id)?;
    let (flags, reply_to) = super::input_reply_to_thread(reply_to_msg_id, top_msg_id);
    let mut request = MessagesSendMediaRequest {
        flags,
        silent: None,
        background: None,
        clear_draft: None,
        noforwards: None,
        update_stickersets_order: None,
        invert_media: None,
        allow_paid_floodskip: None,
        peer: Box::new(input_peer_from_cached(peer)),
        reply_to,
        media: Box::new(poll),
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
    let updates: Updates = crate::api_invoke::invoke_api(snapshot, api_id, request)?;
    let mut staged = media.clone();
    let result = super::send_result(updates, chat_id, "", random_id, peers, &mut staged)?;
    let message = match &result {
        OutgoingMessageResult::Sent { message } => message,
        OutgoingMessageResult::Scheduled { message } => &message.message,
    };
    if message.media_kind.as_deref() != Some("poll") {
        return Err(MtprotoError::Message(
            "poll response missing authoritative poll".into(),
        ));
    }
    *media = staged;
    Ok(result)
}

#[cfg(test)]
#[path = "../../tests/unit/messages_poll_creation_tests.rs"]
mod tests;
