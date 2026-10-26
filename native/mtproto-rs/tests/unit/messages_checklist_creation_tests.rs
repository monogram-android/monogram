use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn checklist_creation_wire_keeps_stable_items_and_independent_permissions_with_bounds() {
    let mut spec = ChecklistSendSpec {
        title: "Synthetic list".into(),
        items: vec!["First".into(), "Second".into()],
        others_can_append: false,
        others_can_complete: false,
    };
    for append in [false, true] {
        for complete in [false, true] {
            spec.others_can_append = append;
            spec.others_can_complete = complete;
            let InputMedia::InputMediaTodo(input) = checklist_media(&spec).unwrap() else {
                panic!()
            };
            let bytes = crate::rpc::encode_boxed_bytes(&input).unwrap();
            let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
            let InputMedia::InputMediaTodo(input) = InputMedia::decode(&mut decoder).unwrap()
            else {
                panic!()
            };
            decoder.finish().unwrap();
            let TodoList::TodoList(todo) = *input.todo;
            assert_eq!(todo.others_can_append.is_some(), append);
            assert_eq!(todo.others_can_complete.is_some(), complete);
            let TextWithEntities::TextWithEntities(title) = *todo.title;
            assert_eq!(title.text, spec.title);
            let items = crate::peers::vector_boxed_items(&todo.list)
                .map(|item| {
                    let TodoItem::TodoItem(item) = item;
                    let TextWithEntities::TextWithEntities(title) = item.title.as_ref();
                    (item.id, title.text.clone())
                })
                .collect::<Vec<_>>();
            assert_eq!(items, vec![(1, "First".into()), (2, "Second".into())]);
        }
    }
    spec.items = vec!["Valid".into(); 30];
    assert!(checklist_media(&spec).is_ok());
    spec.items.push("Extra".into());
    assert!(checklist_media(&spec).is_err());
    spec.items.clear();
    assert!(checklist_media(&spec).is_err());
    spec.items = vec![" ".into()];
    assert!(checklist_media(&spec).is_err());
    spec.items = vec!["😀".repeat(33)];
    assert!(checklist_media(&spec).is_err());
    spec.items = vec!["Valid".into()];
    spec.title = "😀".repeat(17);
    assert!(checklist_media(&spec).is_err());
    spec.title = " ".into();
    assert!(checklist_media(&spec).is_err());
}
