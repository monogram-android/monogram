use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn title_edit_preserves_item_ids_rich_text_and_permissions_on_wire() {
    let title = TextWithEntities::TextWithEntities(TextWithEntitiesConstructor {
        text: "Old".into(),
        entities: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![],
        })),
    });
    let todo = TodoList::TodoList(TodoListConstructor {
        flags: TodoListConstructor::OTHERS_CAN_APPEND_FLAG
            | TodoListConstructor::OTHERS_CAN_COMPLETE_FLAG,
        others_can_append: Some(Box::new(True::True(TrueConstructor {}))),
        others_can_complete: Some(Box::new(True::True(TrueConstructor {}))),
        title: Box::new(title.clone()),
        list: Box::new(Vector::Vector(VectorConstructor {
            field_0: 2,
            field_1: vec![
                Box::new(TodoItem::TodoItem(TodoItemConstructor {
                    id: -7,
                    title: Box::new(title.clone()),
                })),
                Box::new(TodoItem::TodoItem(TodoItemConstructor {
                    id: i32::MAX,
                    title: Box::new(title),
                })),
            ],
        })),
    });
    assert!(title_media(&todo, " \n ").is_err());
    let InputMedia::InputMediaTodo(input) = title_media(&todo, " New title ").unwrap() else {
        panic!()
    };
    let bytes = crate::rpc::encode_boxed_bytes(&input).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    let InputMedia::InputMediaTodo(input) = InputMedia::decode(&mut decoder).unwrap() else {
        panic!()
    };
    decoder.finish().unwrap();
    let TodoList::TodoList(updated) = *input.todo;
    let mut row = raw_message(8, 37);
    let Message::Message(message) = &mut row else {
        panic!()
    };
    message.out = Some(Box::new(True::True(TrueConstructor {})));
    message.media = Some(Box::new(MessageMedia::MessageMediaToDo(
        MessageMediaToDoConstructor {
            flags: 0,
            todo: Box::new(todo.clone()),
            completions: None,
        },
    )));
    let rows = Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(row.clone())],
    });
    let peer = InputPeer::InputPeerSelf(InputPeerSelfConstructor {});
    let request = title_request(&rows, peer.clone(), -3, 37, "New").unwrap();
    assert_eq!(request.flags, MessagesEditMessageRequest::MEDIA_FLAG);
    assert!(
        request.message.is_none()
            && request.entities.is_none()
            && request.reply_markup.is_none()
            && request.schedule_date.is_none()
    );
    assert!(title_request(&rows, peer.clone(), -4, 37, "New").is_err());
    assert!(title_request(&rows, peer.clone(), -3, 38, "New").is_err());
    let peers = crate::HashMap::default();
    let mut edit = ChecklistTextEditDto {
        item_id: Some(-7),
        text: " 😀 task ".into(),
        entities_json: Some(r#"[{"kind":"bold","offset":4,"length":4}]"#.into()),
        expected_text: "Old".into(),
        expected_entities_json: None,
    };
    let rich = text_request(&rows, peer.clone(), -3, 37, &edit, &peers).unwrap();
    let InputMedia::InputMediaTodo(rich_media) = rich.media.as_deref().unwrap() else {
        panic!()
    };
    let bytes = crate::rpc::encode_boxed_bytes(rich_media).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    let InputMedia::InputMediaTodo(rich) = InputMedia::decode(&mut decoder).unwrap() else {
        panic!()
    };
    decoder.finish().unwrap();
    let TodoList::TodoList(rich) = *rich.todo;
    assert_eq!(
        rich.title,
        match &todo {
            TodoList::TodoList(t) => t.title.clone(),
        }
    );
    let rich_items = crate::peers::vector_boxed_items(&rich.list).collect::<Vec<_>>();
    let TodoItem::TodoItem(first) = rich_items[0];
    let TextWithEntities::TextWithEntities(text) = first.title.as_ref();
    assert_eq!(first.id, -7);
    assert_eq!(text.text, " 😀 task ");
    assert_eq!(crate::peers::vector_boxed_items(&text.entities).count(), 1);
    let TodoList::TodoList(original_list) = &todo;
    assert_eq!(
        rich_items[1],
        crate::peers::vector_boxed_items(&original_list.list)
            .nth(1)
            .unwrap()
    );
    assert_eq!(rich.flags, original_list.flags);
    edit.item_id = None;
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_ok());
    edit.expected_text = "Concurrent".into();
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_err());
    edit.expected_text = "Old".into();
    edit.expected_entities_json = edit.entities_json.clone();
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_err());
    edit.expected_entities_json = None;
    edit.item_id = Some(-7);
    let removed = content_request(&rows, peer.clone(), -3, 37, &edit, &peers, true).unwrap();
    let InputMedia::InputMediaTodo(media) = removed.media.as_deref().unwrap() else {
        panic!()
    };
    let TodoList::TodoList(list) = media.todo.as_ref();
    assert_eq!(crate::peers::vector_boxed_items(&list.list).count(), 1);
    assert_eq!(
        crate::peers::vector_boxed_items(&list.list).next().unwrap(),
        crate::peers::vector_boxed_items(&original_list.list)
            .nth(1)
            .unwrap()
    );
    assert_eq!(list.title, original_list.title);
    assert_eq!(list.flags, original_list.flags);
    let mut single = rows.clone();
    let Vector::Vector(values) = &mut single;
    let Message::Message(message) = values.field_1[0].as_mut() else {
        panic!()
    };
    let MessageMedia::MessageMediaToDo(media) = message.media.as_deref_mut().unwrap() else {
        panic!()
    };
    let TodoList::TodoList(list) = media.todo.as_mut();
    let Vector::Vector(items) = list.list.as_mut();
    items.field_1.truncate(1);
    items.field_0 = 1;
    assert!(content_request(&single, peer.clone(), -3, 37, &edit, &peers, true).is_err());
    edit.expected_text = "changed".into();
    assert!(content_request(&rows, peer.clone(), -3, 37, &edit, &peers, true).is_err());
    edit.expected_text = "Old".into();
    edit.item_id = None;
    assert!(content_request(&rows, peer.clone(), -3, 37, &edit, &peers, true).is_err());
    edit.item_id = Some(0);
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_err());
    let mut zero_rows = rows.clone();
    let Vector::Vector(values) = &mut zero_rows;
    let Message::Message(message) = values.field_1[0].as_mut() else {
        panic!()
    };
    let MessageMedia::MessageMediaToDo(media) = message.media.as_deref_mut().unwrap() else {
        panic!()
    };
    let TodoList::TodoList(list) = media.todo.as_mut();
    let Vector::Vector(items) = list.list.as_mut();
    let TodoItem::TodoItem(first) = items.field_1[0].as_mut();
    first.id = 0;
    assert!(text_request(&zero_rows, peer.clone(), -3, 37, &edit, &peers).is_ok());
    edit.item_id = Some(i32::MAX);
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_ok());
    edit.entities_json = Some(r#"[{"kind":"bold","offset":2,"length":1}]"#.into());
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_err());
    edit.entities_json = Some(r#"[{"kind":"unsupported","offset":4,"length":4}]"#.into());
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_err());
    edit.entities_json = None;
    edit.text = "x".repeat(65);
    assert!(text_request(&rows, peer.clone(), -3, 37, &edit, &peers).is_err());
    let Message::Message(message) = &mut row else {
        panic!()
    };
    message.out = None;
    let rows = Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![Box::new(row)],
    });
    assert!(title_request(&rows, peer, -3, 37, "New").is_err());
    let TodoList::TodoList(original) = todo;
    assert_eq!(updated.flags, original.flags);
    assert_eq!(updated.others_can_append, original.others_can_append);
    assert_eq!(updated.others_can_complete, original.others_can_complete);
    assert_eq!(updated.list, original.list);
    let TextWithEntities::TextWithEntities(title) = *updated.title;
    assert_eq!(title.text, "New title");
    assert_eq!(crate::peers::vector_boxed_items(&title.entities).count(), 0);
    let empty = Vector::Vector(VectorConstructor {
        field_0: 0,
        field_1: vec![],
    });
    assert!(
        title_request(
            &empty,
            InputPeer::InputPeerSelf(InputPeerSelfConstructor {}),
            7,
            37,
            "New"
        )
        .is_err()
    );
}

fn raw_message(sender_id: i64, id: i32) -> tellers_mtproto::latest::api::Message {
    use tellers_mtproto::latest::api::{
        Message, MessageConstructor, Peer, PeerChatConstructor, PeerUserConstructor,
    };
    Message::Message(MessageConstructor {
        flags: 0,
        out: None,
        mentioned: None,
        media_unread: None,
        silent: None,
        post: None,
        from_scheduled: None,
        legacy: None,
        edit_hide: None,
        pinned: None,
        noforwards: None,
        invert_media: None,
        flags2: 0,
        offline: None,
        video_processing_pending: None,
        paid_suggested_post_stars: None,
        paid_suggested_post_ton: None,
        id,
        from_id: Some(Box::new(Peer::PeerUser(PeerUserConstructor {
            user_id: sender_id,
        }))),
        from_boosts_applied: None,
        from_rank: None,
        peer_id: Box::new(Peer::PeerChat(PeerChatConstructor { chat_id: 3 })),
        saved_peer_id: None,
        fwd_from: None,
        via_bot_id: None,
        via_business_bot_id: None,
        guestchat_via_from: None,
        reply_to: None,
        date: 1,
        message: "hi".into(),
        media: None,
        reply_markup: None,
        entities: None,
        views: None,
        forwards: None,
        replies: None,
        edit_date: None,
        post_author: None,
        grouped_id: None,
        reactions: None,
        restriction_reason: None,
        ttl_period: None,
        quick_reply_shortcut_id: None,
        effect: None,
        factcheck: None,
        report_delivery_until_date: None,
        paid_message_stars: None,
        suggested_post: None,
        schedule_repeat_period: None,
        summary_from_language: None,
        rich_message: None,
    })
}
