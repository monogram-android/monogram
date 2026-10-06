use super::*;
use tellers_mtproto::latest::api::{
    PeerChannelConstructor, PeerChatConstructor, PeerUserConstructor,
};

#[test]
fn people_are_user_peers() {
    let user = Peer::PeerUser(PeerUserConstructor { user_id: 7 });
    let chat = Peer::PeerChat(PeerChatConstructor { chat_id: 3 });
    let channel = Peer::PeerChannel(PeerChannelConstructor { channel_id: 9 });
    assert!(is_people_peer(&user));
    assert!(!is_people_peer(&chat));
    assert!(!is_people_peer(&channel));
}

#[test]
fn empty_page_clears_offsets() {
    let (rate, peer, id) = next_global_page(&[], 44);
    assert_eq!((rate, peer, id), (0, 0, 0));
}

#[test]
fn next_page_uses_last_message_and_rate() {
    let last = MessageDto {
        chat_id: -1_000_000_000_042,
        id: 88,
        sender_id: None,
        text: None,
        date: 1,
        edit_date: None,
        outgoing: false,
        media_kind: None,
        media_cache_key: None,
        thumb_cache_key: None,
        media_duration: None,
        media_width: None,
        media_height: None,
        reply_quote: None,
        entities_json: None,
        noforwards: false,
        reply_to_msg_id: None,
        reply_to_top_id: None,
        fwd_from: None,
        fwd_from_id: None,
        fwd_date: None,
        via_bot: None,
        sender_name: None,
        sender_emoji_status_document_id: None,
        grouped_id: None,
        file_name: None,
        file_size: None,
        supports_streaming: false,
        reactions_json: None,
        replies_count: 0,
        discussion_peer_id: None,
        reply_markup_json: None,
        forum_topic: false,
    };
    let (rate, peer, id) = next_global_page(&[last], 17);
    assert_eq!(rate, 17);
    assert_eq!(peer, -1_000_000_000_042);
    assert_eq!(id, 88);
}

#[test]
fn first_page_uses_empty_offset_peer() {
    let peers = HashMap::new();
    let peer = offset_input_peer(&peers, 0).expect("empty");
    assert!(matches!(peer, InputPeer::InputPeerEmpty(_)));
}

#[test]
fn empty_contacts_query_is_rejected() {
    let err = contacts_search_query("   ").expect_err("blank");
    assert!(matches!(err, MtprotoError::Message(message) if message == "empty search query"));
    assert_eq!(contacts_search_query(" ada ").expect("trim"), "ada");
}

#[test]
fn search_global_always_sends_folder_id() {
    let (flags, folder) = search_global_folder(0);
    assert_eq!(flags, MessagesSearchGlobalRequest::FOLDER_ID_FLAG);
    assert_eq!(folder, Some(0));
    let (archive_flags, archive) = search_global_folder(1);
    assert_eq!(archive_flags, MessagesSearchGlobalRequest::FOLDER_ID_FLAG);
    assert_eq!(archive, Some(1));
}
