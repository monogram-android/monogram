use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn synthetic_contact_import_wire_and_partial_result_identity_are_preserved() {
    let contacts = vec![
        ContactImportSpec {
            client_id: 7,
            phone: "+15555550123".into(),
            first_name: "Synthetic".into(),
            last_name: "One".into(),
        },
        ContactImportSpec {
            client_id: 9,
            phone: "+15555550124".into(),
            first_name: "Synthetic".into(),
            last_name: "Two".into(),
        },
    ];
    let request = import_request(&contacts).unwrap();
    let bytes = crate::rpc::encode_boxed_bytes(&request).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    assert_eq!(
        decoder.read_u32().unwrap(),
        ContactsImportContactsRequest::ID
    );
    let decoded = ContactsImportContactsRequest::decode(&mut decoder).unwrap();
    decoder.finish().unwrap();
    let rows = crate::peers::vector_boxed_items(&decoded.contacts).collect::<Vec<_>>();
    let InputContact::InputPhoneContact(first) = rows[0];
    assert_eq!(first.client_id, 7);
    assert_eq!(first.phone, contacts[0].phone);
    assert!(first.note.is_none());
    assert!(import_request(&[]).is_err());
    let mut duplicate = contacts.clone();
    duplicate[1].client_id = 7;
    assert!(import_request(&duplicate).is_err());
    duplicate = contacts.clone();
    duplicate[1].phone = "15555550123".into();
    assert!(import_request(&duplicate).is_err());
    let mut body = ContactsImportedContactsConstructor {
        imported: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![Box::new(ImportedContact::ImportedContact(
                ImportedContactConstructor {
                    client_id: 7,
                    user_id: 42,
                },
            ))],
        })),
        popular_invites: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![],
        })),
        retry_contacts: Box::new(Vector::Vector(VectorConstructor {
            field_0: 1,
            field_1: vec![9],
        })),
        users: Box::new(Vector::Vector(VectorConstructor {
            field_0: 0,
            field_1: vec![],
        })),
    };
    let result = import_result(&contacts, &body).unwrap();
    assert_eq!(
        result.imported,
        vec![ContactImportMatch {
            client_id: 7,
            user_id: 42
        }]
    );
    assert_eq!(result.retry_client_ids, vec![9]);
    body.retry_contacts = Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![7],
    }));
    assert!(import_result(&contacts, &body).is_err());
    body.retry_contacts = Box::new(Vector::Vector(VectorConstructor {
        field_0: 1,
        field_1: vec![99],
    }));
    assert!(import_result(&contacts, &body).is_err());
}
