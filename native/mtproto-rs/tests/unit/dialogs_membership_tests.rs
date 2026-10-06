use super::*;
use crate::{HashMap, HashMapExt};
use tellers_mtproto::latest::api::{
    ChannelForbiddenConstructor, ChatConstructor, ChatForbiddenConstructor, ChatPhoto,
    ChatPhotoEmptyConstructor, Dialog, DialogConstructor, Message, Peer, PeerChatConstructor,
    PeerNotifySettings, PeerNotifySettingsConstructor, True, TrueConstructor, User, Vector,
    VectorConstructor,
};

fn flag(on: bool) -> Option<Box<True>> {
    on.then(|| Box::new(True::True(TrueConstructor {})))
}

fn basic_group(left: bool, deactivated: bool) -> TlChat {
    group(11, left, deactivated)
}

fn group(id: i64, left: bool, deactivated: bool) -> TlChat {
    TlChat::Chat(ChatConstructor {
        flags: 0,
        creator: None,
        left: flag(left),
        deactivated: flag(deactivated),
        call_active: None,
        call_not_empty: None,
        noforwards: None,
        id,
        title: "Comment group".into(),
        photo: Box::new(ChatPhoto::ChatPhotoEmpty(ChatPhotoEmptyConstructor {})),
        participants_count: 3,
        date: 20,
        version: 1,
        migrated_to: None,
        admin_rights: None,
        default_banned_rights: None,
    })
}

#[test]
fn joined_group_is_not_left() {
    let (id, meta) = chat_meta_from_tl(&basic_group(false, false)).expect("meta");
    assert_eq!(id, 11);
    assert!(meta.is_group);
    assert!(!meta.left);
}

#[test]
fn left_group_reports_membership_loss() {
    let (_, meta) = chat_meta_from_tl(&basic_group(true, false)).expect("meta");
    assert!(meta.left);
}

#[test]
fn migrated_group_reports_membership_loss() {
    let (_, meta) = chat_meta_from_tl(&basic_group(false, true)).expect("meta");
    assert!(meta.left);
}

#[test]
fn forbidden_peers_are_left() {
    let chat = TlChat::ChatForbidden(ChatForbiddenConstructor {
        id: 12,
        title: "Gone".into(),
    });
    let (_, meta) = chat_meta_from_tl(&chat).expect("meta");
    assert!(meta.left, "a forbidden group cannot be a membership");

    let channel = TlChat::ChannelForbidden(ChannelForbiddenConstructor {
        flags: 0,
        broadcast: None,
        megagroup: flag(true),
        monoforum: None,
        id: 13,
        access_hash: 99,
        title: "Gone too".into(),
        until_date: None,
    });
    let (_, meta) = chat_meta_from_tl(&channel).expect("meta");
    assert!(meta.left, "a forbidden channel cannot be a membership");
}

fn boxed_vec<T>(items: Vec<T>) -> Box<Vector<Box<T>>> {
    Box::new(Vector::Vector(VectorConstructor {
        field_0: items.len() as u32,
        field_1: items.into_iter().map(Box::new).collect(),
    }))
}

fn notify() -> PeerNotifySettings {
    PeerNotifySettings::PeerNotifySettings(PeerNotifySettingsConstructor {
        flags: 0,
        show_previews: None,
        silent: None,
        mute_until: None,
        ios_sound: None,
        android_sound: None,
        other_sound: None,
        stories_muted: None,
        stories_hide_sender: None,
        stories_ios_sound: None,
        stories_android_sound: None,
        stories_other_sound: None,
    })
}

fn chat_dialog(chat_id: i64) -> Dialog {
    Dialog::Dialog(DialogConstructor {
        flags: 0,
        pinned: None,
        unread_mark: None,
        view_forum_as_messages: None,
        peer: Box::new(Peer::PeerChat(PeerChatConstructor { chat_id })),
        top_message: 1,
        read_inbox_max_id: 1,
        read_outbox_max_id: 0,
        unread_count: 6,
        unread_mentions_count: 0,
        unread_reactions_count: 0,
        unread_poll_votes_count: 0,
        notify_settings: Box::new(notify()),
        pts: None,
        draft: None,
        folder_id: None,
        ttl_period: None,
    })
}

#[test]
fn left_dialogs_are_kept_so_room_can_hide_them() {
    let mut peers = HashMap::new();
    let mut media = crate::media::MediaIndex::new();
    let mut channel_pts = HashMap::new();
    let out = map_peer_dialogs(
        &mut peers,
        &mut media,
        &mut channel_pts,
        boxed_vec(vec![chat_dialog(11), chat_dialog(12)]),
        boxed_vec(Vec::<Message>::new()),
        boxed_vec(vec![group(11, true, false), group(12, false, false)]),
        boxed_vec(Vec::<User>::new()),
    );
    let left = out
        .iter()
        .find(|c| c.id == crate::peers::chat_id_for_chat(11));
    let joined = out
        .iter()
        .find(|c| c.id == crate::peers::chat_id_for_chat(12));
    assert!(left.expect("left dialog").left);
    assert!(!joined.expect("joined dialog").left);
}

#[test]
fn unknown_peer_dialogs_are_dropped() {
    let mut peers = HashMap::new();
    let mut media = crate::media::MediaIndex::new();
    let mut channel_pts = HashMap::new();
    let out = map_peer_dialogs(
        &mut peers,
        &mut media,
        &mut channel_pts,
        boxed_vec(vec![chat_dialog(99)]),
        boxed_vec(Vec::<Message>::new()),
        boxed_vec(Vec::<TlChat>::new()),
        boxed_vec(Vec::<User>::new()),
    );
    assert!(out.is_empty());
}
