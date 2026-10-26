use super::*;
use tellers_mtproto::codec::{Decoder, Limits, TlDecode};

#[test]
fn synthetic_contact_wire_preserves_card_and_rejects_invalid_configuration() {
    let mut spec = ContactSendSpec {
        phone: "+15555550123".into(),
        first_name: "Synthetic".into(),
        last_name: "Contact".into(),
        vcard: "BEGIN:VCARD\nVERSION:3.0\nFN:Synthetic Contact\nEND:VCARD".into(),
    };
    let InputMedia::InputMediaContact(input) = contact_media(&spec).unwrap() else {
        panic!()
    };
    let bytes = crate::rpc::encode_boxed_bytes(&input).unwrap();
    let mut decoder = Decoder::new(&bytes, Limits::default()).unwrap();
    let InputMedia::InputMediaContact(input) = InputMedia::decode(&mut decoder).unwrap() else {
        panic!()
    };
    decoder.finish().unwrap();
    assert_eq!(input.phone_number, spec.phone);
    assert_eq!(input.first_name, spec.first_name);
    assert_eq!(input.last_name, spec.last_name);
    assert_eq!(input.vcard, spec.vcard);
    spec.phone = "invalid".into();
    assert!(contact_media(&spec).is_err());
    spec.phone = "+15555550123".into();
    spec.first_name = " ".into();
    assert!(contact_media(&spec).is_err());
    spec.first_name = "Synthetic".into();
    spec.vcard = "x".repeat(65537);
    assert!(contact_media(&spec).is_err());
}
