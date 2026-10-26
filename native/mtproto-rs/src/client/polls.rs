use super::*;
use crate::peers::input_peer_from_cached;
use tellers_mtproto::latest::api::*;

pub fn close_poll(handle: u64, chat_id: i64, message_id: i32) -> Result<(), MtprotoError> {
    if message_id <= 0 {
        return Err(MtprotoError::Message("message id required".into()));
    }
    with_client_mut(handle, |state| {
        call_with_migrate(state, |state| {
            with_peer_refresh(state, chat_id, |state| {
                let messages = crate::dialogs::fetch_message(
                    &mut state.snapshot,
                    state.api_id,
                    &state.peers,
                    chat_id,
                    message_id,
                )?;
                let peer = input_peer_from_cached(crate::peers::require_usable_peer(
                    &state.peers,
                    chat_id,
                )?);
                let Some(request) = close_request(&messages, peer, chat_id, message_id)? else {
                    return Ok(());
                };
                let response: Updates =
                    crate::api_invoke::invoke_api(&mut state.snapshot, state.api_id, request)?;
                super::management::cache_response_peers(state, &response);
                super::management::enqueue_response(state.snapshot.dc_id, &response)
            })
        })
    })
}

fn close_request(
    messages: &Vector<Box<Message>>,
    peer: InputPeer,
    chat_id: i64,
    message_id: i32,
) -> Result<Option<MessagesEditMessageRequest>, MtprotoError> {
    let mut rows = crate::peers::vector_boxed_items(messages);
    let Some(Message::Message(message)) = rows.next() else {
        return Err(MtprotoError::Message("poll unavailable".into()));
    };
    if rows.next().is_some()
        || message.id != message_id
        || crate::peers::peer_chat_id(&message.peer_id) != chat_id
    {
        return Err(MtprotoError::Message("unexpected message identity".into()));
    }
    let Some(MessageMedia::MessageMediaPoll(media)) = message.media.as_deref() else {
        return Err(MtprotoError::Message("poll unavailable".into()));
    };
    let Poll::Poll(poll) = media.poll.as_ref();
    close_poll_media(poll).map(|media| {
        media.map(|media| MessagesEditMessageRequest {
            flags: MessagesEditMessageRequest::MEDIA_FLAG,
            no_webpage: None,
            invert_media: None,
            peer: Box::new(peer),
            id: message_id,
            message: None,
            media: Some(Box::new(media)),
            reply_markup: None,
            entities: None,
            schedule_date: None,
            schedule_repeat_period: None,
            quick_reply_shortcut_id: None,
            rich_message: None,
        })
    })
}

fn close_poll_media(poll: &PollConstructor) -> Result<Option<InputMedia>, MtprotoError> {
    if poll.creator.is_none() {
        return Err(MtprotoError::Message("poll creator required".into()));
    }
    if poll.closed.is_some() {
        return Ok(None);
    }
    let closed = PollConstructor {
        id: poll.id,
        flags: PollConstructor::CLOSED_FLAG,
        closed: Some(Box::new(True::True(TrueConstructor {}))),
        public_voters: None,
        multiple_choice: None,
        quiz: None,
        open_answers: None,
        revoting_disabled: None,
        shuffle_answers: None,
        hide_results_until_close: None,
        creator: None,
        subscribers_only: None,
        question: poll.question.clone(),
        answers: poll.answers.clone(),
        close_period: None,
        close_date: None,
        countries_iso2: None,
        hash: 0,
    };
    Ok(Some(InputMedia::InputMediaPoll(
        InputMediaPollConstructor {
            flags: 0,
            poll: Box::new(Poll::Poll(closed)),
            correct_answers: None,
            attached_media: None,
            solution: None,
            solution_entities: None,
            solution_media: None,
        },
    )))
}

#[cfg(test)]
#[path = "../../tests/unit/client_poll_tests.rs"]
mod tests;
