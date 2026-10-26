use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn poll_creation_wire_preserves_anonymity_options_and_quiz_solution_with_validation() {
    let mut spec = PollSendSpec {
        question: "Question".into(),
        answers: vec!["First".into(), "Second".into()],
        anonymous: false,
        multiple_choice: false,
        quiz: true,
        correct_answer: Some(1),
        solution: Some("Explanation".into()),
    };
    let InputMedia::InputMediaPoll(input) = poll_media(&spec).unwrap() else {
        panic!()
    };
    let bytes = crate::rpc::encode_boxed_bytes(&input).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    let InputMedia::InputMediaPoll(decoded) = InputMedia::decode(&mut decoder).unwrap() else {
        panic!()
    };
    decoder.finish().unwrap();
    let Poll::Poll(poll) = *decoded.poll;
    assert!(poll.public_voters.is_some() && poll.quiz.is_some() && poll.multiple_choice.is_none());
    assert!(poll.creator.is_none());
    assert_eq!(poll.id, 0);
    let options = crate::peers::vector_boxed_items(&poll.answers)
        .map(|a| {
            let PollAnswer::PollAnswer(a) = a else {
                panic!("unexpected input answer")
            };
            a.option.clone()
        })
        .collect::<Vec<_>>();
    assert_eq!(options, vec![vec![b'0'], vec![b'1']]);
    let Vector::Vector(correct) = *decoded.correct_answers.unwrap();
    assert_eq!(correct.field_1, vec![1]);
    assert_eq!(decoded.solution.as_deref(), Some("Explanation"));
    assert!(decoded.solution_entities.is_some());
    spec.multiple_choice = true;
    assert!(poll_media(&spec).is_err());
    spec.multiple_choice = false;
    spec.correct_answer = Some(2);
    assert!(poll_media(&spec).is_err());
    spec.quiz = false;
    spec.correct_answer = None;
    assert!(poll_media(&spec).is_err());
    spec.solution = None;
    spec.anonymous = true;
    spec.multiple_choice = true;
    let InputMedia::InputMediaPoll(input) = poll_media(&spec).unwrap() else {
        panic!()
    };
    let Poll::Poll(poll) = *input.poll;
    assert!(poll.public_voters.is_none() && poll.multiple_choice.is_some() && poll.quiz.is_none());
    spec.answers[0] = " ".into();
    assert!(poll_media(&spec).is_err());
    spec.answers[0] = "😀".repeat(51);
    assert!(poll_media(&spec).is_err());
    spec.answers[0] = "First".into();
    spec.question = " ".into();
    assert!(poll_media(&spec).is_err());
}
