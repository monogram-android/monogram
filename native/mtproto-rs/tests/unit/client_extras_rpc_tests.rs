use super::*;
use tellers_mtproto::latest::api::{
    MessagePeerVoteConstructor, MessagePeerVoteInputOptionConstructor,
    MessagePeerVoteMultipleConstructor, Peer, PeerUserConstructor,
};

fn user_peer(id: i64) -> Box<Peer> {
    Box::new(Peer::PeerUser(PeerUserConstructor { user_id: id }))
}

#[test]
fn single_vote_keeps_option_bytes() {
    let row = MessagePeerVote::MessagePeerVote(MessagePeerVoteConstructor {
        peer: user_peer(7),
        option: vec![0x01, 0xa0],
        date: 11,
    });
    assert_eq!(poll_vote_options(&row), vec![vec![0x01, 0xa0]]);
}

#[test]
fn input_option_vote_has_no_bytes() {
    let row = MessagePeerVote::MessagePeerVoteInputOption(MessagePeerVoteInputOptionConstructor {
        peer: user_peer(7),
        date: 11,
    });
    assert!(poll_vote_options(&row).is_empty());
}

#[test]
fn multiple_vote_keeps_every_option() {
    let row = MessagePeerVote::MessagePeerVoteMultiple(MessagePeerVoteMultipleConstructor {
        peer: user_peer(7),
        options: Box::new(Vector::Vector(VectorConstructor {
            field_0: 2,
            field_1: vec![b"a".to_vec(), b"b".to_vec()],
        })),
        date: 11,
    });
    assert_eq!(poll_vote_options(&row), vec![b"a".to_vec(), b"b".to_vec()]);
}
