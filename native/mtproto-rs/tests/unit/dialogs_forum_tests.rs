use super::map_forum_topic;
use tellers_mtproto::latest::api::{
    ForumTopic, ForumTopicConstructor, ForumTopicDeletedConstructor, Peer, PeerChannelConstructor,
    PeerNotifySettings, PeerNotifySettingsConstructor, PeerUserConstructor, True, TrueConstructor,
};

fn flag(on: bool) -> Option<Box<True>> {
    on.then(|| Box::new(True::True(TrueConstructor {})))
}

fn topic(id: i32, title: &str, short: bool, unread: i32) -> ForumTopic {
    ForumTopic::ForumTopic(ForumTopicConstructor {
        flags: 0,
        my: None,
        closed: None,
        pinned: flag(id == 1),
        short: flag(short),
        hidden: None,
        title_missing: None,
        id,
        date: 10,
        peer: Box::new(Peer::PeerChannel(PeerChannelConstructor { channel_id: 5 })),
        title: title.into(),
        icon_color: 0x6FB9F0,
        icon_emoji_id: None,
        top_message: 99,
        read_inbox_max_id: 80,
        read_outbox_max_id: 90,
        unread_count: unread,
        unread_mentions_count: 1,
        unread_reactions_count: 3,
        unread_poll_votes_count: 0,
        from_id: Box::new(Peer::PeerUser(PeerUserConstructor { user_id: 7 })),
        notify_settings: Box::new(PeerNotifySettings::PeerNotifySettings(
            PeerNotifySettingsConstructor {
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
            },
        )),
        draft: None,
    })
}

#[test]
fn maps_general_topic() {
    let mapped = map_forum_topic(&topic(1, "General", false, 2), Some("hi".into()));
    assert_eq!(mapped.id, 1);
    assert_eq!(mapped.title, "General");
    assert!(mapped.pinned);
    assert!(!mapped.deleted);
    assert_eq!(mapped.unread_count, 2);
    assert_eq!(mapped.unread_reactions_count, 3);
    assert_eq!(mapped.last_message_preview.as_deref(), Some("hi"));
    assert_eq!(mapped.icon_color, 0x6FB9F0);
}

#[test]
fn maps_short_topic_unread() {
    let mapped = map_forum_topic(&topic(8, "Bugs", true, 4), None);
    assert!(mapped.short);
    assert_eq!(mapped.unread_count, 4);
    assert_eq!(mapped.unread_mentions_count, 1);
}

#[test]
fn maps_deleted_topic() {
    let mapped = map_forum_topic(
        &ForumTopic::ForumTopicDeleted(ForumTopicDeletedConstructor { id: 9 }),
        Some("gone".into()),
    );
    assert_eq!(mapped.id, 9);
    assert!(mapped.deleted);
    assert!(mapped.title.is_empty());
    assert!(mapped.last_message_preview.is_none());
}
