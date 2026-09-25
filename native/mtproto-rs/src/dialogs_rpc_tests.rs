use super::*;
#[cfg(test)]
mod rights_tests {
    use super::resolve_permissions;

    #[test]
    fn broadcast_member_cannot_send() {
        let r = resolve_permissions(
            false, false, true, false, false, false, false, false, false, false, false, false,
            false, false, false, false, false,
        );
        assert_eq!(r, (true, false, false, true, false));
    }

    #[test]
    fn broadcast_admin_can_post() {
        let r = resolve_permissions(
            false, false, true, false, true, true, true, false, false, false, false, false, false,
            true, true, true, true,
        );
        assert_eq!(r, (true, true, true, true, true));
    }

    #[test]
    fn default_ban_plain_blocks_text_not_photos() {
        let r = resolve_permissions(
            false, false, false, false, false, false, false, false, false, false, false, false,
            false, false, true, false, false,
        );
        assert_eq!(r.1, false);
        assert_eq!(r.2, true);
        assert_eq!(r.3, true);
    }

    #[test]
    fn admin_overrides_default_ban() {
        let r = resolve_permissions(
            false, false, false, false, false, false, true, false, false, false, false, false,
            false, true, true, true, true,
        );
        assert_eq!(r, (true, true, true, true, false));
    }

    #[test]
    fn noforwards_and_kicked() {
        let protected = resolve_permissions(
            false, false, false, false, false, false, false, true, false, false, false, false,
            false, false, false, false, false,
        );
        assert!(!protected.3);
        let kicked = resolve_permissions(
            false, false, false, false, false, false, false, false, true, true, true, true, true,
            false, false, false, false,
        );
        assert_eq!(kicked, (false, false, false, false, false));
    }
}

#[cfg(test)]
mod header_tests {
    use super::{fill_missing_reply_quotes, fwd_from_label, reply_meta, via_bot_label};
    use crate::MessageDto;
    use crate::{HashMap, HashMapExt};
    use tellers_mtproto::latest::api::{
        MessageFwdHeader, MessageFwdHeaderConstructor, MessageReplyHeader,
        MessageReplyHeaderConstructor, True, TrueConstructor,
    };

    fn dto(id: i32, text: &str, reply_to: Option<i32>, quote: Option<&str>) -> MessageDto {
        MessageDto {
            chat_id: 1,
            id,
            sender_id: None,
            text: Some(text.into()),
            date: 0,
            edit_date: None,
            outgoing: false,
            media_kind: None,
            media_cache_key: None,
            thumb_cache_key: None,
            media_duration: None,
            media_width: None,
            media_height: None,
            reply_quote: quote.map(str::to_string),
            entities_json: None,
            noforwards: false,
            reply_to_msg_id: reply_to,
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
        }
    }

    #[test]
    fn reply_meta_reads_id_and_quote() {
        let header = MessageReplyHeader::MessageReplyHeader(MessageReplyHeaderConstructor {
            flags: 0,
            reply_to_scheduled: None,
            forum_topic: None,
            quote: None,
            reply_to_msg_id: Some(9),
            reply_to_peer_id: None,
            reply_from: None,
            reply_media: None,
            reply_to_top_id: Some(42),
            quote_text: Some("quoted".into()),
            quote_entities: None,
            quote_offset: None,
            todo_item_id: None,
            poll_option: None,
            reply_to_ephemeral: None,
        });
        assert_eq!(
            reply_meta(Some(&header)),
            (Some(9), Some(42), Some("quoted".into()), false),
        );
    }

    #[test]
    fn reply_meta_reads_forum_topic_flag() {
        let header = MessageReplyHeader::MessageReplyHeader(MessageReplyHeaderConstructor {
            flags: 0,
            reply_to_scheduled: None,
            forum_topic: Some(Box::new(True::True(TrueConstructor {}))),
            quote: None,
            reply_to_msg_id: Some(9),
            reply_to_peer_id: None,
            reply_from: None,
            reply_media: None,
            reply_to_top_id: Some(42),
            quote_text: None,
            quote_entities: None,
            quote_offset: None,
            todo_item_id: None,
            poll_option: None,
            reply_to_ephemeral: None,
        });
        assert_eq!(reply_meta(Some(&header)).3, true);
    }

    #[test]
    fn fwd_prefers_from_name() {
        let header = MessageFwdHeader::MessageFwdHeader(MessageFwdHeaderConstructor {
            flags: 0,
            imported: None,
            saved_out: None,
            from_id: None,
            from_name: Some("Alice".into()),
            date: 1,
            channel_post: None,
            post_author: Some("Bob".into()),
            saved_from_peer: None,
            saved_from_msg_id: None,
            saved_from_id: None,
            saved_from_name: None,
            saved_date: None,
            psa_type: None,
        });
        assert_eq!(
            fwd_from_label(Some(&header), &HashMap::new()).as_deref(),
            Some("Alice")
        );
        assert_eq!(super::fwd_origin(Some(&header)), (None, Some(1)));
        let MessageFwdHeader::MessageFwdHeader(mut visible) = header else {
            panic!("expected forward header");
        };
        visible.from_id = Some(Box::new(tellers_mtproto::latest::api::Peer::PeerUser(
            tellers_mtproto::latest::api::PeerUserConstructor { user_id: 7 },
        )));
        assert_eq!(
            super::fwd_origin(Some(&MessageFwdHeader::MessageFwdHeader(visible))),
            (Some(7), Some(1))
        );
    }

    #[test]
    fn via_bot_uses_username() {
        let mut names = HashMap::new();
        names.insert(7_i64, "Helper Bot".into());
        let mut users = HashMap::new();
        users.insert(7_i64, "helperbot".into());
        assert_eq!(
            via_bot_label(Some(7), &users, &names).as_deref(),
            Some("helperbot")
        );
    }

    #[test]
    fn fill_reply_quote_from_sibling() {
        let mut dtos = vec![
            dto(1, "original", None, None),
            dto(2, "reply", Some(1), None),
        ];
        fill_missing_reply_quotes(&mut dtos);
        assert_eq!(dtos[1].reply_quote.as_deref(), Some("original"));
    }
}

#[cfg(test)]
mod entity_tests {
    use super::entity_json;

    #[test]
    fn entity_json_roundtrip_shape() {
        let payload = serde_json::to_string(&vec![entity_json("bold", 0, 4, None)]).expect("json");
        assert!(payload.contains("\"kind\":\"bold\""));
        assert!(payload.contains("\"offset\":0"));
        assert!(payload.contains("\"length\":4"));
    }
}

#[cfg(test)]
mod history_tests {
    use super::{get_history, refresh_message_media};
    use crate::{HashMap, HashMapExt};
    use tellers_mtproto_session::{OsRandom, Snapshot};

    #[test]
    fn unknown_peer_fails_before_rpc() {
        let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
        let mut peers = HashMap::new();
        let mut media = crate::media_rpc::MediaIndex::new();
        let err = get_history(
            &mut snapshot,
            1,
            &mut peers,
            &mut media,
            42,
            10,
            0,
            0,
            0,
            None,
        )
        .expect_err("unknown peer");
        match err {
            crate::MtprotoError::Message(message) => {
                assert!(message.contains("unknown peer 42"));
                assert!(message.contains("refresh chats first"));
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn get_dialogs_first_page_uses_empty_offset_peer() {
        use tellers_mtproto::latest::api::InputPeerEmptyConstructor;
        let request = super::MessagesGetDialogsRequest {
            flags: 0,
            exclude_pinned: None,
            folder_id: None,
            offset_date: 0,
            offset_id: 0,
            offset_peer: Box::new(super::InputPeer::InputPeerEmpty(
                InputPeerEmptyConstructor {},
            )),
            limit: 40,
            hash: 0,
        };
        let bytes = crate::rpc::encode_boxed_bytes(&request).expect("encode");
        assert!(
            bytes
                .windows(4)
                .any(|w| w == &super::MessagesGetDialogsRequest::ID.to_le_bytes()),
            "messages.getDialogs ctor",
        );
        assert!(
            bytes
                .windows(4)
                .any(|w| w == &InputPeerEmptyConstructor::ID.to_le_bytes()),
            "first page offset_peer is inputPeerEmpty",
        );
    }

    #[test]
    fn get_messages_request_encodes_input_message_id() {
        let req = super::MessagesGetMessagesRequest {
            id: super::input_message_ids(7),
        };
        let bytes = crate::rpc::encode_boxed_bytes(&req).expect("encode");
        assert!(
            bytes
                .windows(4)
                .any(|w| w == &super::MessagesGetMessagesRequest::ID.to_le_bytes()),
            "messages.getMessages ctor",
        );
        assert!(
            bytes
                .windows(4)
                .any(|w| w == &super::InputMessageIdConstructor::ID.to_le_bytes()),
            "inputMessageID ctor",
        );
        assert!(bytes.windows(4).any(|w| w == &7i32.to_le_bytes()));
    }

    #[test]
    fn refresh_message_media_unknown_peer_fails_before_rpc() {
        let mut snapshot = Snapshot::new(2, &mut OsRandom).expect("snapshot");
        let peers = HashMap::new();
        let mut media = crate::media_rpc::MediaIndex::new();
        let err = refresh_message_media(&mut snapshot, 1, &peers, &mut media, 42, 7)
            .expect_err("unknown peer");
        match err {
            crate::MtprotoError::Message(message) => {
                assert!(message.contains("unknown peer 42"));
            }
            other => panic!("unexpected {other:?}"),
        }
    }
}

#[cfg(test)]
mod channel_pts_tests {
    use crate::HashMap;

    use super::merge_channel_pts;

    #[test]
    fn dialog_pts_seed_is_positive_and_monotonic() {
        let mut cursors = HashMap::from_iter([(42_i64, 80_i32)]);

        merge_channel_pts(&mut cursors, 42, Some(64));
        merge_channel_pts(&mut cursors, 42, Some(96));
        merge_channel_pts(&mut cursors, 42, Some(0));
        merge_channel_pts(&mut cursors, 42, None);

        assert_eq!(cursors.get(&42), Some(&96));
    }
}

#[cfg(test)]
mod preview_tests {
    use super::{compose_chat_list_preview, preview_hides_file_name};

    #[test]
    fn dm_keeps_media_and_caption() {
        assert_eq!(
            compose_chat_list_preview(false, false, Some("Mike"), Some("Photo"), "hello")
                .as_deref(),
            Some("Photo, hello"),
        );
    }

    #[test]
    fn group_prefixes_sender() {
        assert_eq!(
            compose_chat_list_preview(true, false, Some("Mike"), Some("Photo"), "hello").as_deref(),
            Some("Mike: Photo, hello"),
        );
    }

    #[test]
    fn group_outgoing_uses_you() {
        assert_eq!(
            compose_chat_list_preview(true, true, Some("Ada"), None, "sent").as_deref(),
            Some("You: sent"),
        );
    }

    #[test]
    fn sticker_and_gif_file_names_stay_out_of_previews() {
        for kind in ["sticker", "sticker_animated", "sticker_video", "gif"] {
            assert!(preview_hides_file_name(Some(kind), "AnimatedSticker.tgs"));
            assert!(preview_hides_file_name(Some(kind), "sticker.webp"));
            assert!(preview_hides_file_name(Some(kind), "mp4.mp4"));
        }
        // A sticker file can also arrive as a plain document.
        assert!(preview_hides_file_name(Some("document"), "sticker.webm"));
        assert!(preview_hides_file_name(None, "AnimatedSticker.tgs"));
    }

    #[test]
    fn generated_media_names_stay_out_of_previews() {
        assert!(preview_hides_file_name(
            Some("photo"),
            "Screenshot_20200722-104614385.jpg"
        ));
        assert!(preview_hides_file_name(Some("video"), "VID_20200803.mp4"));
        assert!(preview_hides_file_name(Some("voice"), "audio.ogg"));
        assert!(preview_hides_file_name(Some("video_note"), "circle.mp4"));
        assert!(preview_hides_file_name(Some("poll"), "{\"question\":1}"));
    }

    #[test]
    fn documents_and_audio_keep_their_name() {
        assert!(!preview_hides_file_name(Some("document"), "report.pdf"));
        assert!(!preview_hides_file_name(Some("audio"), "Track — Artist"));
    }
}
#[cfg(test)]
mod forum_topic_tests {
    use super::map_forum_topic;
    use tellers_mtproto::latest::api::{
        ForumTopic, ForumTopicConstructor, ForumTopicDeletedConstructor, Peer,
        PeerChannelConstructor, PeerNotifySettings, PeerNotifySettingsConstructor,
        PeerUserConstructor, True, TrueConstructor,
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
}

#[cfg(test)]
mod mute_tests {
    use tellers_mtproto::latest::api::{PeerNotifySettings, PeerNotifySettingsConstructor};

    fn settings(mute_until: Option<i32>) -> PeerNotifySettings {
        PeerNotifySettings::PeerNotifySettings(PeerNotifySettingsConstructor {
            flags: 0,
            show_previews: None,
            silent: None,
            mute_until,
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

    fn now() -> i32 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i32)
            .unwrap_or(0)
    }

    #[test]
    fn future_mute_is_muted_and_is_an_override() {
        let s = settings(Some(now() + 3600));
        assert!(super::is_muted(&s));
        assert!(super::is_mute_override(&s));
    }

    #[test]
    fn absent_mute_until_inherits_the_type_default() {
        let s = settings(None);
        assert!(!super::is_muted(&s));
        assert!(!super::is_mute_override(&s));
    }

    #[test]
    fn expired_mute_is_unmuted_but_still_an_override() {
        let s = settings(Some(now() - 60));
        assert!(!super::is_muted(&s));
        assert!(super::is_mute_override(&s));
    }

    #[test]
    fn forever_mute_is_muted() {
        // Telegram uses i32::MAX for "mute forever".
        let s = settings(Some(i32::MAX));
        assert!(super::is_muted(&s));
        assert!(super::is_mute_override(&s));
    }
}
#[test]
fn only_documents_and_audio_preview_their_file_name() {
    for kind in [
        "todo",
        "poll",
        "geo",
        "venue",
        "contact",
        "dice",
        "photo",
        "video",
        "gif",
        "sticker",
        "sticker_animated",
        "sticker_video",
        "voice",
        "webpage",
    ] {
        assert!(
            preview_hides_file_name(Some(kind), "real-name.png"),
            "{kind} must not surface its file name"
        );
    }
    assert!(!preview_hides_file_name(Some("document"), "report.pdf"));
    assert!(!preview_hides_file_name(Some("audio"), "Track — Artist"));
    assert!(!preview_hides_file_name(None, "holiday.jpg"));
}
#[cfg(test)]
mod membership_tests {
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
        let mut media = crate::media_rpc::MediaIndex::new();
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
        let left = out.iter().find(|c| c.id == crate::peers::chat_id_for_chat(11));
        let joined = out.iter().find(|c| c.id == crate::peers::chat_id_for_chat(12));
        assert!(left.expect("left dialog").left);
        assert!(!joined.expect("joined dialog").left);
    }

    #[test]
    fn unknown_peer_dialogs_are_dropped() {
        let mut peers = HashMap::new();
        let mut media = crate::media_rpc::MediaIndex::new();
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
}
