use super::*;
use crate::client::pipeline_parts;

use crate::client::set_pipeline_parts;
use tellers_mtproto::latest::api::{
    GeoPointConstructor, GeoPointEmptyConstructor, MessageEntity, MessageMediaContactConstructor,
    MessageMediaDiceConstructor, MessageMediaGeoConstructor, MessageMediaGeoLiveConstructor,
    MessageMediaPollConstructor, MessageMediaVenueConstructor, PollAnswerConstructor,
    PollAnswerVotersConstructor, PollConstructor, PollResultsConstructor,
    TextWithEntitiesConstructor,
};

fn text(value: &str) -> Box<TextWithEntities> {
    Box::new(TextWithEntities::TextWithEntities(
        TextWithEntitiesConstructor {
            text: value.to_owned(),
            entities: vector::<MessageEntity>(&[]),
        },
    ))
}

fn vector<T>(items: &[T]) -> Box<Vector<Box<T>>>
where
    T: Clone,
{
    Box::new(Vector::Vector(VectorConstructor {
        field_0: items.len() as u32,
        field_1: items.iter().cloned().map(Box::new).collect(),
    }))
}

fn geo(lat: f64, long: f64) -> Box<GeoPoint> {
    Box::new(GeoPoint::GeoPoint(GeoPointConstructor {
        flags: 0,
        long,
        lat,
        access_hash: 0,
        accuracy_radius: None,
    }))
}

#[test]
fn dice_payload_carries_value_and_emoji() {
    let json = dice_to_json(&MessageMediaDiceConstructor {
        flags: 0,
        value: 4,
        emoticon: "🎲".to_owned(),
        game_outcome: None,
    });
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["v"], 4);
    assert_eq!(parsed["e"], "🎲");
}

#[test]
fn contact_payload_carries_names_phone_and_user() {
    let json = contact_to_json(&MessageMediaContactConstructor {
        phone_number: "+79000000000".to_owned(),
        first_name: "Ada".to_owned(),
        last_name: "Lovelace".to_owned(),
        vcard: "BEGIN:VCARD".to_owned(),
        user_id: 42,
    });
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["p"], "+79000000000");
    assert_eq!(parsed["f"], "Ada");
    assert_eq!(parsed["l"], "Lovelace");
    assert_eq!(parsed["u"], 42);
}

#[test]
fn venue_payload_carries_coordinates_and_place_details() {
    let json = venue_to_json(&MessageMediaVenueConstructor {
        geo: geo(55.75, 37.61),
        title: "Red Square".to_owned(),
        address: "Moscow".to_owned(),
        provider: "foursquare".to_owned(),
        venue_id: "abc".to_owned(),
        venue_type: "square".to_owned(),
    })
    .expect("venue payload");
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["lat"], 55.75);
    assert_eq!(parsed["long"], 37.61);
    assert_eq!(parsed["t"], "Red Square");
    assert_eq!(parsed["y"], "square");
}

#[test]
fn live_geo_payload_marks_live_and_keeps_heading() {
    let json = geo_live_to_json(&MessageMediaGeoLiveConstructor {
        flags: 0,
        geo: geo(10.0, 20.0),
        heading: Some(90),
        period: 900,
        proximity_notification_radius: None,
    })
    .expect("live payload");
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["live"], 1);
    assert_eq!(parsed["p"], 900);
    assert_eq!(parsed["h"], 90);
}

#[test]
fn empty_geo_point_has_no_payload() {
    assert!(
        geo_to_json(&MessageMediaGeoConstructor {
            geo: Box::new(GeoPoint::GeoPointEmpty(GeoPointEmptyConstructor {})),
        })
        .is_none()
    );
}

#[test]
fn poll_payload_marks_answers_voters_and_quiz() {
    let answers = vector(&[
        PollAnswer::PollAnswer(PollAnswerConstructor {
            flags: 0,
            text: text("yes"),
            option: vec![0x01, 0xa0],
            media: None,
            added_by: None,
            date: None,
        }),
        PollAnswer::PollAnswer(PollAnswerConstructor {
            flags: 0,
            text: text("no"),
            option: b"b".to_vec(),
            media: None,
            added_by: None,
            date: None,
        }),
    ]);
    let voters = vector(&[PollAnswerVoters::PollAnswerVoters(
        PollAnswerVotersConstructor {
            flags: 0,
            chosen: Some(Box::new(True::True(TrueConstructor {}))),
            correct: Some(Box::new(True::True(TrueConstructor {}))),
            option: vec![0x01, 0xa0],
            voters: Some(7),
            recent_voters: None,
        },
    )]);
    let json = poll_to_json(&MessageMediaPollConstructor {
        flags: 0,
        poll: Box::new(Poll::Poll(PollConstructor {
            id: 5,
            flags: 0,
            closed: None,
            public_voters: None,
            multiple_choice: None,
            quiz: Some(Box::new(True::True(TrueConstructor {}))),
            open_answers: None,
            revoting_disabled: None,
            shuffle_answers: None,
            hide_results_until_close: None,
            creator: None,
            subscribers_only: None,
            question: text("ready?"),
            answers,
            close_period: None,
            close_date: None,
            countries_iso2: None,
            hash: 0,
        })),
        results: Box::new(PollResults::PollResults(PollResultsConstructor {
            flags: 0,
            min: None,
            has_unread_votes: None,
            can_view_stats: None,
            results: Some(voters),
            total_voters: Some(7),
            recent_voters: None,
            solution: Some("because".to_owned()),
            solution_entities: None,
            solution_media: None,
        })),
        attached_media: None,
    });
    let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed["q"], "ready?");
    assert_eq!(parsed["z"], 1);
    assert_eq!(parsed["n"], 7);
    assert_eq!(parsed["s"], "because");
    let answers = parsed["a"].as_array().expect("answers");
    assert_eq!(answers.len(), 2);
    assert_eq!(answers[0]["t"], "yes");
    assert_eq!(answers[0]["c"], 1);
    assert_eq!(answers[0]["k"], 1);
    assert_eq!(answers[0]["v"], 7);
    assert_eq!(answers[0]["o"], "01a0");
    assert_eq!(answers[1]["c"], 0);
    assert_eq!(answers[1]["v"], 0);
}
